package com.riskauditor.model

import kotlinx.serialization.json.Json

/**
 * The one Json configuration every module uses.
 *
 * `ignoreUnknownKeys = true` is deliberate: a snapshot written by a newer build of
 * the app must still load in an older one rather than crashing the scan. The
 * schema version carried on each object tells us when a migration is needed.
 */
object ModelJson {

    val strict: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    val lenient: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        classDiscriminator = "type"
    }

    val pretty: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    /**
     * True when a persisted snapshot predates the current schema and should be
     * treated as a baseline rather than diffed against.
     */
    fun isCompatible(schemaVersion: Int): Boolean = schemaVersion == SCHEMA_VERSION
}
