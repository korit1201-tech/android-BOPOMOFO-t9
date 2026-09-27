package com.bopomofo.t9ime.engine

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray

object ClipboardHistoryManager {
    private const val PREF_NAME = "ime_clipboard_prefs"
    private const val KEY_HISTORY = "clipboard_history"
    private const val MAX_ITEMS = 10

    private val historyList = mutableListOf<String>()
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, null)
        var needTrim = false
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    if (historyList.size < MAX_ITEMS) {
                        historyList.add(array.getString(i))
                    } else {
                        needTrim = true
                    }
                }
            } catch (_: Exception) {}
        }
        isInitialized = true
        if (needTrim) {
            save(context)
        }
    }

    fun getHistory(): List<String> = historyList.take(MAX_ITEMS).toList()

    fun addClip(context: Context, text: String) {
        if (text.isBlank() || text.length > 2000) return
        historyList.remove(text)
        historyList.add(0, text)
        while (historyList.size > MAX_ITEMS) {
            historyList.removeAt(historyList.lastIndex)
        }
        save(context)
    }

    fun removeClip(context: Context, text: String) {
        historyList.remove(text)
        save(context)
    }

    fun clearAll(context: Context) {
        historyList.clear()
        save(context)
    }

    private fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (item in historyList) {
            array.put(item)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
