package com.bopomofo.t9ime.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * 記憶體除錯日誌收集器，供使用者直接點擊複製回報
 */
object VoiceLogger {
    private const val TAG = "BopomofoIME_Voice"
    private val logs = LinkedList<String>()
    private const val MAX_LOGS = 60

    fun d(msg: String) {
        record("DEBUG", msg)
        Log.d(TAG, msg)
    }

    fun i(msg: String) {
        record("INFO", msg)
        Log.i(TAG, msg)
    }

    fun w(msg: String, tr: Throwable? = null) {
        record("WARN", if (tr != null) "$msg: ${tr.message}" else msg)
        Log.w(TAG, msg, tr)
    }

    fun e(msg: String, tr: Throwable? = null) {
        record("ERROR", if (tr != null) "$msg: ${tr.message}" else msg)
        Log.e(TAG, msg, tr)
    }

    private fun record(level: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$time][$level] $msg"
        synchronized(logs) {
            if (logs.size >= MAX_LOGS) logs.removeFirst()
            logs.add(line)
        }
    }

    fun dumpLogs(): String {
        val header = "=== BOPOMOFO IME VOICE DIAGNOSTICS ===\n" +
                "Model: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n" +
                "Default Locale: ${Locale.getDefault()}\n" +
                "----------------------------------------\n"
        val body = synchronized(logs) {
            if (logs.isEmpty()) "無日誌紀錄" else logs.joinToString("\n")
        }
        return header + body
    }

    fun copyToClipboard(context: Context) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = dumpLogs()
            cm?.setPrimaryClip(ClipData.newPlainText("Bopomofo Voice Logs", text))
            Toast.makeText(context, "📋 已複製語音診斷 Log，可直接貼上回報", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "複製 Log 失敗", e)
        }
    }
}
