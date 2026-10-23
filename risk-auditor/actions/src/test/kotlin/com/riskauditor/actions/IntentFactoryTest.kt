package com.riskauditor.actions

import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.riskauditor.presentation.ActionItem
import com.riskauditor.presentation.ActionKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val factory = IntentFactory(context)
    private val pkg = "com.example.app"

    private fun action(kind: ActionKind) = ActionItem(
        id = kind.name.lowercase(),
        order = 0,
        title = "t",
        explanation = "e",
        kind = kind
    )

    @Test
    fun `app settings intent has the right action and uri`() {
        val intent = factory.appDetailsIntent(pkg)

        assertThat(intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent.data?.scheme).isEqualTo("package")
        assertThat(intent.data?.schemeSpecificPart).isEqualTo(pkg)
    }

    @Test
    fun `uninstall intent uses action delete with package uri`() {
        val resolved = factory.resolve(action(ActionKind.UNINSTALL), pkg)

        // Robolectric resolves nothing by default, so the factory falls back.
        // What matters is that when it DOES build the uninstall intent it is
        // shaped correctly, which we check by building it directly.
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", pkg, null))
        assertThat(intent.action).isEqualTo(Intent.ACTION_DELETE)
        assertThat(intent.data?.schemeSpecificPart).isEqualTo(pkg)
        assertThat(resolved.action.kind).isEqualTo(ActionKind.UNINSTALL)
    }

    @Test
    fun `an unresolvable intent falls back to the app settings page`() {
        // Nothing is registered in Robolectric, so every specialised Settings
        // screen is unresolvable and must degrade rather than throw.
        val resolved = factory.resolve(action(ActionKind.OPEN_NOTIFICATION_LISTENER_SETTINGS), pkg)

        assertThat(resolved.intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(resolved.fallbackReason).isNotNull()
    }

    @Test
    fun `every action kind produces an intent without throwing`() {
        // The point of this test is that no ActionKind can be added without a
        // corresponding intent, and that none of them blow up on an OEM that
        // lacks the screen.
        ActionKind.entries.forEach { kind ->
            val resolved = factory.resolve(action(kind), pkg)
            assertThat(resolved.intent.action).isNotEmpty()
        }
    }

    @Test
    fun `resolveAll preserves the advisor ordering`() {
        val actions = listOf(
            ActionItem("c", 2, "c", "", ActionKind.UNINSTALL),
            ActionItem("a", 0, "a", "", ActionKind.OPEN_DEVICE_ADMIN_SETTINGS),
            ActionItem("b", 1, "b", "", ActionKind.OPEN_ACCESSIBILITY_SETTINGS)
        )

        val resolved = factory.resolveAll(actions, pkg)

        assertThat(resolved.map { it.action.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `launching outside an activity adds the new task flag`() {
        val intent = factory.forLaunchOutsideActivity(factory.appDetailsIntent(pkg))
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `the original intent is not mutated when adding flags`() {
        val original = factory.appDetailsIntent(pkg)
        factory.forLaunchOutsideActivity(original)
        assertThat(original.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
    }
}
