package com.riskauditor.hashintel

import java.io.File
import java.security.MessageDigest

/** A computed APK fingerprint, cached by the facts that make it stale. */
data class ApkHash(
    val packageName: String,
    val sha256: String,
    val sizeBytes: Long,
    val lastUpdateTime: Long
)

/**
 * Streaming SHA-256 of an installed APK.
 *
 * Streamed rather than read into memory: split APKs and large games routinely
 * exceed 100 MB, and `File.readBytes()` on one of those is an out-of-memory
 * crash on a mid-range phone. A fixed 64 KB buffer keeps the cost flat.
 *
 * Results are cached on (package, lastUpdateTime, size). Hashing every APK on
 * every scan would add seconds to a rescan for a value that only changes when
 * the app is updated.
 */
class Sha256Hasher(
    private val cache: MutableMap<String, ApkHash> = mutableMapOf()
) {

    /**
     * @return the hash, or null when the file cannot be read. A missing or
     *         unreadable APK is not an error worth failing a scan over: the app
     *         may have been uninstalled mid-scan.
     */
    fun hash(packageName: String, apkPath: String, lastUpdateTime: Long): ApkHash? {
        val file = File(apkPath)
        if (!file.isFile || !file.canRead()) return null

        val size = file.length()
        cache[packageName]?.let { cached ->
            if (cached.lastUpdateTime == lastUpdateTime && cached.sizeBytes == size) return cached
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)

        try {
            file.inputStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (t: Throwable) {
            return null
        }

        val result = ApkHash(
            packageName = packageName,
            sha256 = digest.digest().toHexString(),
            sizeBytes = size,
            lastUpdateTime = lastUpdateTime
        )
        cache[packageName] = result
        return result
    }

    /** Hash the base APK plus any splits, in a stable order. */
    fun hashAll(packageName: String, apkPaths: List<String>, lastUpdateTime: Long): List<ApkHash> =
        apkPaths.sorted().mapNotNull { hash("$packageName@$it", it, lastUpdateTime) }

    fun clearCache() = cache.clear()

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        fun ByteArray.toHexString(): String {
            val out = CharArray(size * 2)
            forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }
    }
}
