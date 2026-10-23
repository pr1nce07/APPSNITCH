/*
 * Offline replacements for the three data-file loaders.
 *
 * These live in the production packages and expose exactly the same public API as
 * the real CatalogLoader / TrustLoader / RubricLoader, so every other production
 * source file compiles against them UNCHANGED. The only difference is that they
 * parse with [verify.MiniJson] instead of kotlinx-serialization, which cannot be
 * downloaded in the authoring environment.
 *
 * They read the SAME data files the real loaders read, off the same classpath
 * resource paths, so the shipped rubric.json / permission_catalog.json /
 * trusted_tools.json are genuinely parsed and validated by the harness.
 *
 * Never part of the Gradle build; never shipped.
 */

package com.riskauditor.catalog

import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import verify.MiniJson
import verify.arr
import verify.bool
import verify.int
import verify.num
import verify.obj
import verify.opt
import verify.req
import verify.str

private inline fun <reified E : Enum<E>> enumBySerialName(key: String): E =
    enumValues<E>().firstOrNull { it.name.lowercase() == key.lowercase() }
        ?: error("no ${E::class.simpleName} matches serial name '$key'")

object CatalogLoader {

    fun fromJson(text: String): PermissionCatalog {
        val root = MiniJson.parse(text).obj()
        val entries = root.req("entries").arr().map { node ->
            val e = node.obj()
            CatalogEntry(
                permission = e.req("permission").str(),
                category = enumBySerialName<Category>(e.req("category").str()),
                protection = enumBySerialName<Protection>(e.req("protection").str()),
                sensitivity = e.req("sensitivity").num(),
                plainText = e.req("plain_text").str(),
                minApi = e.opt("min_api")?.int() ?: 0,
                maxApi = e.opt("max_api")?.int(),
                special = e.opt("special")?.bool() ?: false
            )
        }
        return PermissionCatalog(entries)
    }

    fun load(): PermissionCatalog {
        val stream = CatalogLoader::class.java.getResourceAsStream(PermissionCatalog.RESOURCE_PATH)
            ?: error("permission catalog missing from the classpath at ${PermissionCatalog.RESOURCE_PATH}")
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }

    val default: PermissionCatalog by lazy { load() }
}
