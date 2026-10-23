package com.riskauditor.store

import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.AppRecord
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.PermissionRecord
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotStoreTest {

    private fun snapshot(pkg: String, vararg permissions: String) = AppSnapshot(
        app = AppRecord(packageName = pkg, label = pkg),
        permissions = permissions.map { PermissionRecord(it, granted = true) },
        scannedAt = 1L
    )

    // ---------------------------------------------------------- in memory ----

    @Test
    fun `a new store is empty`() {
        assertThat(InMemorySnapshotStore().isEmpty()).isTrue()
    }

    @Test
    fun `put then get round trips`() {
        val store = InMemorySnapshotStore()
        store.put(snapshot("com.a", "android.permission.CAMERA"))

        assertThat(store.get("com.a")?.permissions).hasSize(1)
        assertThat(store.isEmpty()).isFalse()
    }

    @Test
    fun `putAll replaces existing entries`() {
        val store = InMemorySnapshotStore()
        store.put(snapshot("com.a", "android.permission.CAMERA"))
        store.putAll(listOf(snapshot("com.a"), snapshot("com.b")))

        assertThat(store.get("com.a")?.permissions).isEmpty()
        assertThat(store.all()).hasSize(2)
    }

    @Test
    fun `retainOnly removes uninstalled packages`() {
        val store = InMemorySnapshotStore()
        store.putAll(listOf(snapshot("com.a"), snapshot("com.b"), snapshot("com.c")))

        val removed = store.retainOnly(setOf("com.a", "com.c"))

        assertThat(removed).containsExactly("com.b")
        assertThat(store.all().keys).containsExactly("com.a", "com.c")
    }

    @Test
    fun `retainOnly with everything present removes nothing`() {
        val store = InMemorySnapshotStore()
        store.putAll(listOf(snapshot("com.a"), snapshot("com.b")))

        assertThat(store.retainOnly(setOf("com.a", "com.b"))).isEmpty()
    }

    // ---------------------------------------------------------- json file ----

    private fun tempFile(): File =
        Files.createTempDirectory("snapshot-store").resolve("snapshots.json").toFile()

    @Test
    fun `json store persists across instances`() {
        val file = tempFile()

        JsonFileSnapshotStore(file).put(snapshot("com.a", "android.permission.READ_SMS"))

        val reopened = JsonFileSnapshotStore(file)
        assertThat(reopened.get("com.a")?.permissions?.map { it.name })
            .containsExactly("android.permission.READ_SMS")
    }

    @Test
    fun `a missing file reads as an empty store rather than throwing`() {
        val file = File(Files.createTempDirectory("missing").toFile(), "nope.json")
        assertThat(JsonFileSnapshotStore(file).isEmpty()).isTrue()
    }

    @Test
    fun `a corrupt file re-baselines instead of failing the scan`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{ this is not valid json")

        var reported = false
        val store = JsonFileSnapshotStore(file, onError = { _, _ -> reported = true })

        assertThat(store.isEmpty()).isTrue()
        assertThat(reported).isTrue()

        // And it must still be usable afterwards.
        store.put(snapshot("com.a"))
        assertThat(store.get("com.a")).isNotNull()
    }

    @Test
    fun `an empty file reads as an empty store`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("")

        assertThat(JsonFileSnapshotStore(file).isEmpty()).isTrue()
    }

    @Test
    fun `remove deletes one package and persists`() {
        val file = tempFile()
        val store = JsonFileSnapshotStore(file)
        store.putAll(listOf(snapshot("com.a"), snapshot("com.b")))

        store.remove("com.a")

        assertThat(JsonFileSnapshotStore(file).all().keys).containsExactly("com.b")
    }

    @Test
    fun `clear empties the store on disk`() {
        val file = tempFile()
        val store = JsonFileSnapshotStore(file)
        store.put(snapshot("com.a"))
        store.clear()

        assertThat(JsonFileSnapshotStore(file).isEmpty()).isTrue()
    }
}
