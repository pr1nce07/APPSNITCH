/* Offline replacement for TrustLoader. See HarnessLoaders.kt for why this exists. */

package com.riskauditor.trust

import verify.MiniJson
import verify.arr
import verify.obj
import verify.opt
import verify.req
import verify.str

object TrustLoader {

    fun fromJson(text: String): TrustRegistry {
        val root = MiniJson.parse(text).obj()
        val tools = root.req("tools").arr().map { node ->
            val t = node.obj()
            TrustedTool(
                packageName = t.req("package_name").str(),
                displayName = t.req("display_name").str(),
                kind = TrustedToolKind.valueOf(t.req("kind").str().uppercase()),
                certSha256 = (t.opt("cert_sha256")?.arr() ?: emptyList())
                    .map { it.str().lowercase() }
                    .toSet()
            )
        }
        return TrustRegistry(tools)
    }

    fun load(): TrustRegistry {
        val stream = TrustLoader::class.java.getResourceAsStream(TrustRegistry.RESOURCE_PATH)
            ?: return TrustRegistry.EMPTY
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }

    val default: TrustRegistry by lazy { load() }
}
