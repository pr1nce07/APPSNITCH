package com.riskauditor.permissions

import android.content.pm.PermissionInfo
import com.google.common.truth.Truth.assertThat
import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionExtractorTest {

    private val catalog = CatalogLoader.default

    private fun sourceWithStandardPermissions() = FakePackageSource()
        .declarePermission("android.permission.READ_SMS", PermissionInfo.PROTECTION_DANGEROUS)
        .declarePermission("android.permission.SEND_SMS", PermissionInfo.PROTECTION_DANGEROUS)
        .declarePermission("android.permission.READ_CONTACTS", PermissionInfo.PROTECTION_DANGEROUS)
        .declarePermission("android.permission.INTERNET", PermissionInfo.PROTECTION_NORMAL)
        .declarePermission("android.permission.CAPTURE_AUDIO_OUTPUT", PermissionInfo.PROTECTION_SIGNATURE)

    @Test
    fun `declared and granted are distinguished`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf(
                "android.permission.READ_SMS" to true,
                "android.permission.SEND_SMS" to false
            )
        )

        val records = PermissionExtractor(source, catalog).extract("com.example.app")
            .associateBy { it.name }

        assertThat(records["android.permission.READ_SMS"]!!.granted).isTrue()
        assertThat(records["android.permission.SEND_SMS"]!!.granted).isFalse()
        // Both are declared; only one is held.
        assertThat(records.values.all { it.declared }).isTrue()
        assertThat(records["android.permission.SEND_SMS"]!!.isLatent).isTrue()
    }

    @Test
    fun `protection levels are resolved`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf(
                "android.permission.READ_SMS" to true,
                "android.permission.INTERNET" to true,
                "android.permission.CAPTURE_AUDIO_OUTPUT" to false
            )
        )

        val records = PermissionExtractor(source, catalog).extract("com.example.app")
            .associateBy { it.name }

        assertThat(records["android.permission.READ_SMS"]!!.protection).isEqualTo(Protection.DANGEROUS)
        assertThat(records["android.permission.INTERNET"]!!.protection).isEqualTo(Protection.NORMAL)
        assertThat(records["android.permission.CAPTURE_AUDIO_OUTPUT"]!!.protection)
            .isEqualTo(Protection.SIGNATURE)
    }

    @Test
    fun `special access permissions are marked special regardless of platform level`() {
        // The platform calls SYSTEM_ALERT_WINDOW "signature", which badly
        // understates what it allows. The catalog's classification wins.
        val source = sourceWithStandardPermissions()
            .declarePermission("android.permission.SYSTEM_ALERT_WINDOW", PermissionInfo.PROTECTION_SIGNATURE)
            .addPackage(
                "com.example.app",
                permissions = listOf("android.permission.SYSTEM_ALERT_WINDOW" to true)
            )

        val record = PermissionExtractor(source, catalog).extract("com.example.app").single()

        assertThat(record.protection).isEqualTo(Protection.SPECIAL)
        assertThat(record.category).isEqualTo(Category.SPECIAL_ACCESS)
    }

    @Test
    fun `a custom permission resolves to unknown`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf("com.vendor.oem.permission.SECRET" to true)
        )

        val record = PermissionExtractor(source, catalog).extract("com.example.app").single()

        assertThat(record.protection).isEqualTo(Protection.UNKNOWN)
        assertThat(record.category).isEqualTo(Category.UNCATEGORIZED)
        assertThat(record.grantable).isFalse()
    }

    @Test
    fun `an ungranted signature permission is marked not grantable`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf("android.permission.CAPTURE_AUDIO_OUTPUT" to false)
        )

        val record = PermissionExtractor(source, catalog).extract("com.example.app").single()

        // It cannot ever be granted to this app, so it is not latent risk.
        assertThat(record.grantable).isFalse()
    }

    @Test
    fun `a granted signature permission is grantable`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf("android.permission.CAPTURE_AUDIO_OUTPUT" to true)
        )
        assertThat(
            PermissionExtractor(source, catalog).extract("com.example.app").single().grantable
        ).isTrue()
    }

    @Test
    fun `an app declaring nothing produces an empty list`() {
        val source = sourceWithStandardPermissions().addPackage("com.empty.app")
        assertThat(PermissionExtractor(source, catalog).extract("com.empty.app")).isEmpty()
    }

    @Test
    fun `a package that is not installed produces an empty list`() {
        assertThat(
            PermissionExtractor(FakePackageSource(), catalog).extract("com.missing")
        ).isEmpty()
    }

    @Test
    fun `categories come from the catalog`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf(
                "android.permission.READ_SMS" to true,
                "android.permission.READ_CONTACTS" to true,
                "android.permission.INTERNET" to true
            )
        )

        val grouped = PermissionExtractor(source, catalog).extractGrouped("com.example.app")

        assertThat(grouped.keys).containsAtLeast(
            Category.SMS, Category.CONTACTS_AND_CALL_LOGS, Category.NETWORK
        )
    }

    @Test
    fun `duplicate declarations are collapsed`() {
        val source = sourceWithStandardPermissions().addPackage(
            "com.example.app",
            permissions = listOf(
                "android.permission.READ_SMS" to true,
                "android.permission.READ_SMS" to false
            )
        )
        assertThat(PermissionExtractor(source, catalog).extract("com.example.app")).hasSize(1)
    }
}
