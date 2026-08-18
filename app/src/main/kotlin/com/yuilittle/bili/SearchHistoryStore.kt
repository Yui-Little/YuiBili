package com.yuilittle.bili

import android.content.Context
import org.json.JSONArray

/** Local-only, bounded search history. It is never sent to a server. */
object SearchHistoryStore {
    const val MAX_HISTORY_ITEMS = 12
    private const val PREFS = "search_history"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val values = ArrayList<String>(MAX_HISTORY_ITEMS)
            for (index in 0 until minOf(array.length(), MAX_HISTORY_ITEMS)) {
                val value = array.optString(index).trim()
                if (value.isNotBlank() && value !in values) values += value
            }
            values
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(context: Context, rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return
        val next = ArrayList<String>(MAX_HISTORY_ITEMS)
        next += keyword
        load(context).forEach { if (it != keyword && next.size < MAX_HISTORY_ITEMS) next += it }
        save(context, next)
    }

    fun remove(context: Context, rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return
        save(context, load(context).filterNot { it == keyword })
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    private fun save(context: Context, values: List<String>) {
        val array = JSONArray()
        values.take(MAX_HISTORY_ITEMS).forEach { value -> array.put(value) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
