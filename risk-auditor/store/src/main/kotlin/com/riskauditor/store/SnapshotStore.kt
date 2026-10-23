package com.riskauditor.store

import com.riskauditor.model.AppSnapshot

/**
 * Persistence for the previous scan, so the next one can be diffed against it
 * (PS-01 should-have 10).
 *
 * Kept as an interface with an in-memory implementation because the diff logic
 * is the part worth testing, and testing it should not require a device, a
 * database or a filesystem.
 *
 * Implementations must be safe to call from a background dispatcher and must
 * never throw on a corrupt or missing store: a broken snapshot file is a reason
 * to re-baseline, not to fail a scan.
 */
interface SnapshotStore {

    /** The stored snapshot for a package, or null if we have never seen it. */
    fun get(packageName: String): AppSnapshot?

    /** Every stored snapshot, keyed by package name. */
    fun all(): Map<String, AppSnapshot>

    /** Replace the stored snapshot for one package. */
    fun put(snapshot: AppSnapshot)

    /** Replace stored snapshots for many packages in one operation. */
    fun putAll(snapshots: Collection<AppSnapshot>)

    /** Forget a package, e.g. after it is uninstalled. */
    fun remove(packageName: String)

    /**
     * Drop every stored package not present in [keep].
     *
     * @return the packages that were removed.
     */
    fun retainOnly(keep: Set<String>): Set<String>

    /** True before the first scan has ever been stored. Used to suppress a first-run alert flood. */
    fun isEmpty(): Boolean

    fun clear()
}

/** In-memory store. The reference implementation and the one the tests use. */
class InMemorySnapshotStore(
    initial: Map<String, AppSnapshot> = emptyMap()
) : SnapshotStore {

    private val snapshots = LinkedHashMap<String, AppSnapshot>(initial)

    @Synchronized
    override fun get(packageName: String): AppSnapshot? = snapshots[packageName]

    @Synchronized
    override fun all(): Map<String, AppSnapshot> = LinkedHashMap(snapshots)

    @Synchronized
    override fun put(snapshot: AppSnapshot) {
        snapshots[snapshot.packageName] = snapshot
    }

    @Synchronized
    override fun putAll(snapshots: Collection<AppSnapshot>) {
        snapshots.forEach { this.snapshots[it.packageName] = it }
    }

    @Synchronized
    override fun remove(packageName: String) {
        snapshots.remove(packageName)
    }

    @Synchronized
    override fun retainOnly(keep: Set<String>): Set<String> {
        val removed = snapshots.keys - keep
        removed.forEach { snapshots.remove(it) }
        return removed
    }

    @Synchronized
    override fun isEmpty(): Boolean = snapshots.isEmpty()

    @Synchronized
    override fun clear() = snapshots.clear()
}
