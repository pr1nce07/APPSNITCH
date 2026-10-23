package com.riskauditor.catalog

import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.Category
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Protection
import org.junit.Test

class PermissionCatalogTest {

    private val catalog = CatalogLoader.default

    @Test
    fun `bundled catalog loads and is not trivially small`() {
        assertThat(catalog.size).isAtLeast(70)
    }

    @Test
    fun `all nine mandated categories are populated`() {
        Category.MANDATED.forEach { category ->
            assertThat(catalog.entriesIn(category)).isNotEmpty()
        }
    }

    /**
     * Every example permission printed in PS-01 section 2 must land in the category
     * the problem statement puts it in. If a future edit moves one of these, this
     * test is the thing that catches it.
     */
    @Test
    fun `every example from the problem statement maps to its stated category`() {
        val expected = mapOf(
            // 01 Special access
            "android.permission.SYSTEM_ALERT_WINDOW" to Category.SPECIAL_ACCESS,
            "android.permission.REQUEST_INSTALL_PACKAGES" to Category.SPECIAL_ACCESS,
            "android.permission.PACKAGE_USAGE_STATS" to Category.SPECIAL_ACCESS,
            "android.permission.BIND_ACCESSIBILITY_SERVICE" to Category.SPECIAL_ACCESS,
            "android.permission.BIND_DEVICE_ADMIN" to Category.SPECIAL_ACCESS,
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to Category.SPECIAL_ACCESS,
            // 02 SMS
            "android.permission.READ_SMS" to Category.SMS,
            "android.permission.RECEIVE_SMS" to Category.SMS,
            "android.permission.SEND_SMS" to Category.SMS,
            // 03 Contacts and call logs
            "android.permission.READ_CONTACTS" to Category.CONTACTS_AND_CALL_LOGS,
            "android.permission.READ_CALL_LOG" to Category.CONTACTS_AND_CALL_LOGS,
            // 04 Camera and microphone
            "android.permission.CAMERA" to Category.CAMERA_AND_MICROPHONE,
            "android.permission.RECORD_AUDIO" to Category.CAMERA_AND_MICROPHONE,
            // 05 Location
            "android.permission.ACCESS_FINE_LOCATION" to Category.LOCATION,
            "android.permission.ACCESS_BACKGROUND_LOCATION" to Category.LOCATION,
            // 06 Phone and identity
            "android.permission.READ_PHONE_STATE" to Category.PHONE_AND_IDENTITY,
            "android.permission.READ_PHONE_NUMBERS" to Category.PHONE_AND_IDENTITY,
            "android.permission.CALL_PHONE" to Category.PHONE_AND_IDENTITY,
            // 07 Storage and media
            "android.permission.READ_MEDIA_IMAGES" to Category.STORAGE_AND_MEDIA,
            "android.permission.MANAGE_EXTERNAL_STORAGE" to Category.STORAGE_AND_MEDIA,
            // 08 Background and persistence
            "android.permission.RECEIVE_BOOT_COMPLETED" to Category.BACKGROUND_AND_PERSISTENCE,
            "android.permission.FOREGROUND_SERVICE" to Category.BACKGROUND_AND_PERSISTENCE,
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to Category.BACKGROUND_AND_PERSISTENCE,
            // 09 Network
            "android.permission.INTERNET" to Category.NETWORK,
            "android.permission.ACCESS_NETWORK_STATE" to Category.NETWORK
        )

        expected.forEach { (permission, category) ->
            assertThat(catalog.categoryOf(permission)).isEqualTo(category)
        }
    }

    @Test
    fun `permissions the spec calls out explicitly are all present`() {
        val required = listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.RECEIVE_MMS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.BIND_VPN_SERVICE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.QUERY_ALL_PACKAGES"
        )
        required.forEach { permission ->
            assertThat(catalog.find(permission)).isNotNull()
        }
    }

    @Test
    fun `catalog has no duplicate permissions`() {
        val names = catalog.entries.map { it.permission }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `every entry has non-blank plain language text that avoids the constant`() {
        catalog.entries.forEach { entry ->
            assertThat(entry.plainText).isNotEmpty()
            // The headline text must never leak the raw permission constant.
            assertThat(entry.plainText).doesNotContain("android.permission.")
            assertThat(entry.plainText).doesNotContain(entry.shortName)
        }
    }

    @Test
    fun `every sensitivity is within range`() {
        catalog.entries.forEach { entry ->
            assertThat(entry.sensitivity).isAtLeast(0.0)
            assertThat(entry.sensitivity).isAtMost(1.0)
        }
    }

    @Test
    fun `an unknown permission falls back to uncategorized without throwing`() {
        val custom = "com.vendor.oem.permission.SECRET_SAUCE"
        assertThat(catalog.categoryOf(custom)).isEqualTo(Category.UNCATEGORIZED)
        assertThat(catalog.protectionOf(custom)).isEqualTo(Protection.UNKNOWN)
        assertThat(catalog.sensitivityOf(custom)).isEqualTo(0.0)
        assertThat(catalog.plainTextOf(custom)).isNotEmpty()
        assertThat(catalog.find(custom)).isNull()
    }

    @Test
    fun `enrich keeps the protection level reported by the device`() {
        // The device says NORMAL even though the catalog says DANGEROUS: device wins.
        val fromDevice = PermissionRecord(
            name = "android.permission.READ_SMS",
            granted = true,
            protection = Protection.NORMAL
        )
        val enriched = catalog.enrich(fromDevice)
        assertThat(enriched.protection).isEqualTo(Protection.NORMAL)
        assertThat(enriched.category).isEqualTo(Category.SMS)
    }

    @Test
    fun `enrich fills protection from the catalog when the device could not resolve it`() {
        val unresolved = PermissionRecord(
            name = "android.permission.READ_SMS",
            granted = true,
            protection = Protection.UNKNOWN
        )
        assertThat(catalog.enrich(unresolved).protection).isEqualTo(Protection.DANGEROUS)
    }

    @Test
    fun `special access permissions are flagged as special`() {
        val special = catalog.specialAccessPermissions
        assertThat(special).contains("android.permission.SYSTEM_ALERT_WINDOW")
        assertThat(special).contains("android.permission.REQUEST_INSTALL_PACKAGES")
        assertThat(special).contains("android.permission.MANAGE_EXTERNAL_STORAGE")
        assertThat(special).doesNotContain("android.permission.INTERNET")
    }

    @Test
    fun `device wide summary counts apps not permissions`() {
        val perApp = mapOf(
            "com.a" to listOf(
                PermissionRecord("android.permission.READ_SMS", granted = true, category = Category.SMS),
                PermissionRecord("android.permission.SEND_SMS", granted = true, category = Category.SMS)
            ),
            "com.b" to listOf(
                PermissionRecord("android.permission.READ_SMS", granted = true, category = Category.SMS)
            ),
            // declared but not granted -> counted separately, never as "can read your SMS"
            "com.c" to listOf(
                PermissionRecord("android.permission.READ_SMS", granted = false, category = Category.SMS)
            )
        )

        val sms = catalog.summarise(perApp).first { it.category == Category.SMS }

        assertThat(sms.appsWithGranted).isEqualTo(2)
        assertThat(sms.appsWithDeclaredOnly).isEqualTo(1)
        assertThat(sms.grantedPermissionCount).isEqualTo(3)
        // com.a holds two SMS permissions so it outranks com.b
        assertThat(sms.worstOffenders.first()).isEqualTo("com.a")
    }

    @Test
    fun `summary is produced for every category even when empty`() {
        val summaries = catalog.summarise(emptyMap())
        assertThat(summaries).hasSize(Category.entries.size)
        assertThat(summaries.all { it.isEmpty }).isTrue()
    }

    @Test
    fun `loader rejects a catalog with duplicate permissions`() {
        val bad = """
            {"version":1,"entries":[
              {"permission":"android.permission.READ_SMS","category":"sms","protection":"dangerous","sensitivity":1.0,"plain_text":"a"},
              {"permission":"android.permission.READ_SMS","category":"sms","protection":"dangerous","sensitivity":1.0,"plain_text":"b"}
            ]}
        """.trimIndent()

        runCatching { CatalogLoader.fromJson(bad) }
            .onSuccess { error("loader should reject duplicate permissions") }
    }

    @Test
    fun `loader rejects an out of range sensitivity`() {
        val bad = """
            {"version":1,"entries":[
              {"permission":"android.permission.READ_SMS","category":"sms","protection":"dangerous","sensitivity":1.7,"plain_text":"a"}
            ]}
        """.trimIndent()

        runCatching { CatalogLoader.fromJson(bad) }
            .onSuccess { error("loader should reject sensitivity above 1.0") }
    }
}
