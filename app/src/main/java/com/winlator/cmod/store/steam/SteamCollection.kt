package com.winlator.cmod.store

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One static Steam library collection — what the Steam client UI calls a category.
 *
 * Collections are not part of PICS app data: Steam keeps them in its cloud config
 * store, so they arrive over a separate RPC rather than with the library sync.
 * See SteamCollectionStore, which owns that fetch.
 */
data class SteamCollection(
    val id: String,
    val name: String,
    val appIds: Set<Int>,
) {
    /** A raw cloud-config entry, as returned by CloudConfigStore.Download. */
    class Entry(val key: String, val value: String, val isDeleted: Boolean)

    /** Parsed collections, plus how many dynamic ones had to be ignored. */
    class ParseResult(val collections: List<SteamCollection>, val skippedDynamic: Int)

    companion object {
        /** Steam's built-in collections. Display names are localised, these ids are not. */
        const val ID_FAVORITE = "favorite"
        const val ID_HIDDEN   = "hidden"

        private const val TAG        = "SteamCollections"
        private const val KEY_PREFIX = "user-collections."

        /**
         * Turn cloud-config entries into collections.
         *
         * Only static collections can be represented — those carry an explicit "added"
         * array of appIds. Dynamic ("smart") collections store a "filterSpec" of rules
         * instead, and resolving one means reimplementing Steam's own tag/genre filter
         * engine, so they are counted and skipped.
         */
        fun parse(entries: List<Entry>): ParseResult {
            val out = mutableListOf<SteamCollection>()
            var skippedDynamic = 0
            for (e in entries) {
                if (e.isDeleted || !e.key.startsWith(KEY_PREFIX)) continue
                try {
                    val json = JSONObject(e.value)
                    val added = json.optJSONArray("added")
                    if (added == null) {
                        if (json.has("filterSpec")) skippedDynamic++
                        continue
                    }
                    val id = json.optString("id").orNull() ?: e.key.removePrefix(KEY_PREFIX)
                    if (id.isBlank()) continue
                    val appIds = buildSet(added.length()) {
                        for (i in 0 until added.length()) add(added.getInt(i))
                    }
                    out.add(SteamCollection(id, json.optString("name").orNull() ?: id, appIds))
                } catch (t: Throwable) {
                    Log.w(TAG, "Skipping malformed collection entry ${e.key}", t)
                }
            }
            out.sortBy { it.name.lowercase() }
            return ParseResult(out, skippedDynamic)
        }

        /** Serialise for the offline snapshot kept in SteamPrefs. */
        fun toJson(list: List<SteamCollection>): String {
            val arr = JSONArray()
            for (c in list) {
                val ids = JSONArray()
                c.appIds.forEach { ids.put(it) }
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("appIds", ids)
                })
            }
            return arr.toString()
        }

        /** Read back a snapshot written by [toJson]. Null if absent or unreadable. */
        fun fromJson(raw: String): List<SteamCollection>? {
            if (raw.isEmpty()) return null
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<SteamCollection>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ids = o.optJSONArray("appIds") ?: JSONArray()
                    out.add(SteamCollection(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        appIds = buildSet(ids.length()) {
                            for (j in 0 until ids.length()) add(ids.getInt(j))
                        },
                    ))
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "Discarding corrupt collections cache", t)
                null
            }
        }

        /**
         * optString() hands back the literal string "null" for a JSON null value, so
         * treat that and blank alike as absent rather than as a usable id or name.
         */
        private fun String?.orNull(): String? =
            if (this == null || isBlank() || this == "null") null else this
    }
}
