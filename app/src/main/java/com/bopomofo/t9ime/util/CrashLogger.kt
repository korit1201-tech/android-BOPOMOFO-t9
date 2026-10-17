package com.bopomofo.t9ime.util

import android.content.Context
import android.os.Build
import com.bopomofo.t9ime.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全域異常與崩潰診斷記錄器 (CrashLogger)
 * 自動捕獲未處理之例外狀況，記錄堆疊資訊、裝置型號與記憶體狀態，
 * 供使用者在設定頁面直接檢視或複製提供給開發者除錯。
 */
object CrashLogger {
    private const val LOG_FILE_NAME = "crash_report.log"
    private const val MAX_LOG_SIZE_BYTES = 100 * 1024 // 100 KB
    private var isInstalled = false
    private val lock = Any()

    /**
     * 安裝全域未捕獲異常處理器
     */
    fun install(context: Context) {
        synchronized(lock) {
            if (isInstalled) return
            val appContext = context.applicationContext
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recordCrash(appContext, thread.name, throwable)
                } catch (_: Exception) {
                } finally {
                    defaultHandler?.uncaughtException(thread, throwable)
                }
            }
            isInstalled = true
        }
    }

    /**
     * 記錄崩潰詳細資訊
     */
    private fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.TAIWAN)
        val nowStr = dateFormat.format(Date())

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val runtime = Runtime.getRuntime()
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemMb = runtime.freeMemory() / (1024 * 1024)
        val usedMemMb = totalMemMb - freeMemMb

        val logEntry = buildString {
            append("========================================\n")
            append("【崩潰時間】: ").append(nowStr).append("\n")
            append("【執行緒】: ").append(threadName).append("\n")
            append("【App 版本】: ").append(BuildConfig.VERSION_NAME)
                .append(" (Build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("【裝置型號】: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            append("【Android 版本】: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("【記憶體狀態】: 已用 ").append(usedMemMb).append("MB / 堆上限 ").append(maxMemMb).append("MB\n")
            append("【異常類型】: ").append(throwable.javaClass.name).append("\n")
            append("【異常訊息】: ").append(throwable.message ?: "無詳細訊息").append("\n")
            append("【堆疊軌跡】:\n").append(stackTrace)
            append("========================================\n\n")
        }

        writeToFile(context, logEntry)
    }

    /**
     * 手動記錄非致命錯誤或嚴重效能異常事件
     */
    fun logNonFatal(context: Context, tag: String, throwable: Throwable) {
        try {
            val appContext = context.applicationContext
            recordCrash(appContext, "NonFatal-$tag", throwable)
        } catch (_: Exception) {}
    }

    private fun writeToFile(context: Context, content: String) {
        synchronized(lock) {
            try {
                val file = getLogFile(context)
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    // 保留後半段並加入修剪標記
                    val existing = file.readText()
                    val trimmed = if (existing.length > 50000) existing.takeLast(50000) else existing
                    file.writeText("...[已截斷歷史紀錄]...\n$trimmed")
                }
                file.appendText(content)
            } catch (_: Exception) {}
        }
    }

    private fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    /**
     * 讀取所有崩潰日誌
     */
    fun getCrashLogs(context: Context): String {
        return synchronized(lock) {
            try {
                val file = getLogFile(context)
                if (file.exists() && file.length() > 0) {
                    file.readText()
                } else {
                    ""
                }
            } catch (e: Exception) {
                "無法讀取日誌: ${e.localizedMessage}"
            }
        }
    }

    /**
     * 檢查是否有未清空的崩潰日誌
     */
    fun hasCrashLogs(context: Context): Boolean {
        return synchronized(lock) {
            try {
                val file = getLogFile(context)
                file.exists() && file.length() > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 清空崩潰日誌
     */
    fun clearCrashLogs(context: Context) {
        synchronized(lock) {
            try {
                val file = getLogFile(context)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }
}
