package com.riskauditor.store

import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.ModelJson
import com.riskauditor.model.SCHEMA_VERSION
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Snapshot store backed by a single JSON file.
 *
 * Chosen over Room deliberately: the data is a small map read once per scan and
 * written once per scan, so a database buys nothing but a schema, a migration
 * path and an Android dependency that would stop this module being pure Kotlin
 * and instantly testable.
 *
 * Failure policy: a missing, unreadable or corrupt file is treated as an empty
 * store. Losing the previous scan means the next one re-baselines and the user
 * sees no diff alerts that round — mildly annoying. Throwing instead would abort
 * the scan, which is much worse.
 *
 * Writes go to a temporary file and are then moved into place, so a process that
 * dies mid-write leaves the previous good file intact rather than a truncated one.
 */
class JsonFileSnapshotStore(
    private val file: File,
    private val onError: (String, Throwable) -> Unit = { _, _ -> }
) : SnapshotStore {

    private val serializer = MapSerializer(String.serializer(), AppSnapshot.serializer())

    private var cache: LinkedHashMap<String, AppSnapshot>? = null

    @Synchronized
    private fun load(): LinkedHashMap<String, AppSnapshot> {
        cache?.let { return it }

        val loaded = try {
            if (!file.exists()) {
                LinkedHashMap()
            } else {
                val text = file.readText()
                if (text.isBlank()) {
                    LinkedHashMap()
                } else {
                    val decoded = ModelJson.lenient.decodeFromString(serializer, text)
                    // Drop anything written by an incompatible schema rather than
                    // diffing against a shape we no longer understand.
                    LinkedHashMap(decoded.filterValues { it.schemaVersion == SCHEMA_VERSION })
                }
            }
        } catch (t: Throwable) {
            onError("snapshot store unreadable at ${file.path}; re-baselining", t)
            LinkedHashMap()
        }

        cache = loaded
        return loaded
    }

    @Synchronized
    private fun persist() {
        val data = cache ?: return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(ModelJson.lenient.encodeToString(serializer, data))
            if (!tmp.renameTo(file)) {
                // renameTo can fail across filesystems; fall back to a direct write.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            onError("could not persist snapshot store to ${file.path}", t)
        }
    }

    @Synchronized
    override fun get(packageName: String): AppSnapshot? = load()[packageName]

    @Synchronized
    override fun all(): Map<String, AppSnapshot> = LinkedHashMap(load())

    @Synchronized
    override fun put(snapshot: AppSnapshot) {
        load()[snapshot.packageName] = snapshot
        persist()
    }

    @Synchronized
    override fun putAll(snapshots: Collection<AppSnapshot>) {
        val map = load()
        snapshots.forEach { map[it.packageName] = it }
        persist()
    }

    @Synchronized
    override fun remove(packageName: String) {
        load().remove(packageName)
        persist()
    }

    @Synchronized
    override fun retainOnly(keep: Set<String>): Set<String> {
        val map = load()
        val removed = map.keys - keep
        removed.forEach { map.remove(it) }
        if (removed.isNotEmpty()) persist()
        return removed
    }

    @Synchronized
    override fun isEmpty(): Boolean = load().isEmpty()

    @Synchronized
    override fun clear() {
        load().clear()
        persist()
    }
}
