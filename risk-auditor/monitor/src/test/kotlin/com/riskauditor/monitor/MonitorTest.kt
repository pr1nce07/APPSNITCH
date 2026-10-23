package com.riskauditor.monitor

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.riskauditor.diff.DiffAlert
import com.riskauditor.model.AppRecord
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Reason
import com.riskauditor.model.RiskBand
import com.riskauditor.model.RiskResult
import com.riskauditor.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class InstallMonitorTest {

    private fun snapshot(label: String = "Example") =
        AppSnapshot(app = AppRecord("com.example.app", label))

    private fun risk(band: RiskBand, topReason: String = "It can read your text messages") =
        RiskResult(
            score = when (band) {
                RiskBand.CRITICAL -> 90
                RiskBand.HIGH -> 65
                RiskBand.MEDIUM -> 40
                RiskBand.LOW -> 10
            },
            band = band,
            reasons = listOf(Reason("R", Severity.HIGH, topReason, "detail", points = 20))
        )

    @Test
    fun `high and critical notify, medium and low do not`() {
        assertThat(InstallMonitor.shouldNotify(risk(RiskBand.CRITICAL))).isTrue()
        assertThat(InstallMonitor.shouldNotify(risk(RiskBand.HIGH))).isTrue()
        // Notifying on Medium would fire for a large share of ordinary apps and
        // train people to mute the channel.
        assertThat(InstallMonitor.shouldNotify(risk(RiskBand.MEDIUM))).isFalse()
        assertThat(InstallMonitor.shouldNotify(risk(RiskBand.LOW))).isFalse()
    }

    @Test
    fun `install high produces a notification spec`() {
        val spec = InstallMonitor.specFor(snapshot(), risk(RiskBand.HIGH), MonitorEvent.INSTALLED)

        assertThat(spec).isNotNull()
        assertThat(spec!!.title).contains("was just installed")
        assertThat(spec.body).isEqualTo("It can read your text messages")
        assertThat(spec.priorityHigh).isFalse()
    }

    @Test
    fun `install low is silent`() {
        assertThat(
            InstallMonitor.specFor(snapshot(), risk(RiskBand.LOW), MonitorEvent.INSTALLED)
        ).isNull()
    }

    @Test
    fun `critical gets high priority and stronger wording`() {
        val spec = InstallMonitor.specFor(snapshot(), risk(RiskBand.CRITICAL), MonitorEvent.INSTALLED)!!
        assertThat(spec.priorityHigh).isTrue()
        assertThat(spec.title).contains("looks dangerous")
    }

    @Test
    fun `an update says updated rather than installed`() {
        val spec = InstallMonitor.specFor(snapshot(), risk(RiskBand.HIGH), MonitorEvent.UPDATED)!!
        assertThat(spec.title).contains("was just updated")
    }

    @Test
    fun `the notification id is stable per package so alerts replace rather than stack`() {
        val a = InstallMonitor.specFor(snapshot(), risk(RiskBand.HIGH), MonitorEvent.INSTALLED)!!
        val b = InstallMonitor.specFor(snapshot(), risk(RiskBand.CRITICAL), MonitorEvent.UPDATED)!!
        assertThat(a.id).isEqualTo(b.id)
    }

    @Test
    fun `notification ids are never negative`() {
        listOf("com.a", "com.b", "a".repeat(200), "").forEach {
            assertThat(InstallMonitor.notificationId(it)).isAtLeast(0)
        }
    }

    @Test
    fun `a diff alert becomes its own notification with a distinct id`() {
        val alert = DiffAlert(
            packageName = "com.example.app",
            label = "Example",
            severity = Severity.CRITICAL,
            headline = "Example was just given powerful access",
            detail = "detail",
            evidence = emptyList()
        )
        val diffSpec = InstallMonitor.specFor(alert)
        val installSpec = InstallMonitor.specFor(snapshot(), risk(RiskBand.HIGH), MonitorEvent.INSTALLED)!!

        assertThat(diffSpec.priorityHigh).isTrue()
        // A diff alert and an install alert about the same app must not
        // overwrite each other.
        assertThat(diffSpec.id).isNotEqualTo(installSpec.id)
    }

    @Test
    fun `a spec with no reasons still has a body`() {
        val spec = InstallMonitor.specFor(
            snapshot(),
            RiskResult(score = 90, band = RiskBand.CRITICAL, reasons = emptyList()),
            MonitorEvent.INSTALLED
        )!!
        assertThat(spec.body).isNotEmpty()
    }
}

@RunWith(RobolectricTestRunner::class)
class PackageEventReceiverTest {

    private fun intent(action: String, pkg: String, replacing: Boolean = false) =
        Intent(action).apply {
            data = Uri.fromParts("package", pkg, null)
            putExtra(Intent.EXTRA_REPLACING, replacing)
        }

    @Test
    fun `package added is an install`() {
        var seen: Pair<String, MonitorEvent>? = null
        val receiver = PackageEventReceiver { pkg, event -> seen = pkg to event }

        receiver.onReceive(
            ApplicationProvider.getApplicationContext(),
            intent(Intent.ACTION_PACKAGE_ADDED, "com.example.app")
        )

        assertThat(seen).isEqualTo("com.example.app" to MonitorEvent.INSTALLED)
    }

    @Test
    fun `package added while replacing is an update`() {
        // Without honouring EXTRA_REPLACING, an update looks like an uninstall
        // followed by an install and the user gets two notifications.
        var seen: Pair<String, MonitorEvent>? = null
        val receiver = PackageEventReceiver { pkg, event -> seen = pkg to event }

        receiver.onReceive(
            ApplicationProvider.getApplicationContext(),
            intent(Intent.ACTION_PACKAGE_ADDED, "com.example.app", replacing = true)
        )

        assertThat(seen).isEqualTo("com.example.app" to MonitorEvent.UPDATED)
    }

    @Test
    fun `package replaced is an update`() {
        var seen: Pair<String, MonitorEvent>? = null
        val receiver = PackageEventReceiver { pkg, event -> seen = pkg to event }

        receiver.onReceive(
            ApplicationProvider.getApplicationContext(),
            intent(Intent.ACTION_PACKAGE_REPLACED, "com.example.app")
        )

        assertThat(seen?.second).isEqualTo(MonitorEvent.UPDATED)
    }

    @Test
    fun `an intent with no package data is ignored`() {
        var called = false
        val receiver = PackageEventReceiver { _, _ -> called = true }

        receiver.onReceive(
            ApplicationProvider.getApplicationContext(),
            Intent(Intent.ACTION_PACKAGE_ADDED)
        )

        assertThat(called).isFalse()
    }

    @Test
    fun `an unrelated action is ignored`() {
        var called = false
        val receiver = PackageEventReceiver { _, _ -> called = true }

        receiver.onReceive(
            ApplicationProvider.getApplicationContext(),
            intent(Intent.ACTION_BATTERY_LOW, "com.example.app")
        )

        assertThat(called).isFalse()
    }

    @Test
    fun `the intent filter carries the package data scheme`() {
        // Without addDataScheme("package") these actions never match and the
        // receiver silently never fires.
        val filter = PackageEventReceiver.intentFilter()
        assertThat(filter.hasDataScheme("package")).isTrue()
        assertThat(filter.hasAction(Intent.ACTION_PACKAGE_ADDED)).isTrue()
        assertThat(filter.hasAction(Intent.ACTION_PACKAGE_REPLACED)).isTrue()
    }
}

@RunWith(RobolectricTestRunner::class)
class PeriodicSweepWorkerTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build()
        )
        PeriodicSweepWorker.sweepAction = null
    }

    @Test
    fun `worker retries when no sweep action is wired`() = runBlocking {
        val worker = TestListenableWorkerBuilder<PeriodicSweepWorker>(
            ApplicationProvider.getApplicationContext()
        ).build()

        // Reporting success for work that did not happen would silently disable
        // the safety net; retry is the honest result.
        assertThat(worker.doWork()).isEqualTo(androidx.work.ListenableWorker.Result.retry())
    }

    @Test
    fun `worker succeeds when the sweep action runs`() = runBlocking {
        var ran = false
        PeriodicSweepWorker.sweepAction = { ran = true }

        val worker = TestListenableWorkerBuilder<PeriodicSweepWorker>(
            ApplicationProvider.getApplicationContext()
        ).build()

        assertThat(worker.doWork()).isEqualTo(androidx.work.ListenableWorker.Result.success())
        assertThat(ran).isTrue()
    }

    @Test
    fun `a throwing sweep action retries rather than crashing`() = runBlocking {
        PeriodicSweepWorker.sweepAction = { throw IllegalStateException("scan blew up") }

        val worker = TestListenableWorkerBuilder<PeriodicSweepWorker>(
            ApplicationProvider.getApplicationContext()
        ).build()

        assertThat(worker.doWork()).isEqualTo(androidx.work.ListenableWorker.Result.retry())
    }
}
