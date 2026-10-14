package com.bopomofo.t9ime.engine

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
        if (historyList.firstOrNull() == text) return
        historyList.remove(text)
        historyList.add(0, text)
        while (historyList.size > MAX_ITEMS) {
            historyList.removeAt(historyList.lastIndex)
        }
        save(context)
    }

    /**
     * 從系統 ClipboardManager 主動同步最新內容
     * 專門解決 Android 10+ 背景輸入法無法收到 clipChanged 事件的問題
     * @return 成功取得並儲存的最新剪貼文字，若無或為密碼敏感內容則返回 null
     */
    fun syncFromSystem(context: Context, isPasswordInput: Boolean = false): String? {
        if (isPasswordInput) return null
        return try {
            val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            if (!clipManager.hasPrimaryClip()) return null
            val clipData = clipManager.primaryClip ?: return null

            // 檢查 Android 13+ 敏感資訊防護（如密碼管理器複製）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val extras = clipData.description?.extras
                if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
                    return null
                }
            }

            if (clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                val text = item?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank() && text.length <= 2000) {
                    addClip(context, text)
                    text
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun removeClip(context: Context, text: String) {
        if (historyList.remove(text)) {
            save(context)
        }
    }

    fun clearAll(context: Context) {
        if (historyList.isNotEmpty()) {
            historyList.clear()
            save(context)
        }
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
