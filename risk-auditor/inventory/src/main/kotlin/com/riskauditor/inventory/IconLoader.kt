package com.riskauditor.inventory

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Loads app icons on demand.
 *
 * Kept out of [com.riskauditor.model.AppRecord] on purpose. A `Drawable` holds a
 * `Resources` reference and therefore a `Context`, so putting one in the model
 * would make the model unserializable, untestable off-device, and a memory leak
 * waiting to happen. It would also mean a 200-app scan decodes 200 bitmaps
 * before showing anything.
 *
 * The UI calls this per visible row. A small LRU keeps scrolling smooth without
 * holding every icon on the device in memory.
 */
class IconLoader(
    context: Context,
    private val maxEntries: Int = 64
) {
    private val pm: PackageManager = context.applicationContext.packageManager

    private val cache = object : LinkedHashMap<String, Drawable>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>?): Boolean =
            size > maxEntries
    }

    /** @return the icon, or null when the package has gone. Callers show a placeholder. */
    @Synchronized
    fun load(packageName: String): Drawable? {
        cache[packageName]?.let { return it }
        return try {
            val icon = pm.getApplicationIcon(packageName)
            cache[packageName] = icon
            icon
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    fun clear() = cache.clear()
}
