package com.bopomofo.t9ime.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executors

data class UserWordEntry(
    val word: String,
    val zhuyin: String,
    var count: Int,
    var lastUsed: Long = System.currentTimeMillis()
)

/**
 * 本機個人化詞庫管理器
 * 1. 本地記錄：每次打字選擇詞彙時自動記憶使用次數與時間
 * 2. 動態加權：依使用頻率自動為候選詞增幅權重（越常選越前排）
 * 3. 匯入匯出：支援標準 TSV 格式純文字檔案匯出備份與跨裝置匯入
 * 4. 100% 離線隱私：所有打字記憶完全保存在手機端，不聯網上傳
 */
class UserDictionaryManager private constructor(private val context: Context?) {

    private val memoryDict = mutableMapOf<String, UserWordEntry>()
    private val executor = Executors.newSingleThreadExecutor()
    private val dictFile: File?
        get() = try { context?.filesDir?.let { File(it, "bopomofo_user_dict.json") } } catch (_: Exception) { null }

    var onDictionaryChangedListener: (() -> Unit)? = null

    companion object {
        const val MAX_USER_ENTRIES = 500 // 個人詞庫上限，杜絕無限制膨脹

        @Volatile
        private var instance: UserDictionaryManager? = null

        fun getInstance(context: Context? = null): UserDictionaryManager {
            return instance ?: synchronized(this) {
                instance ?: UserDictionaryManager(context?.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadFromFile()
    }

    private fun loadFromFile() {
        try {
            val file = dictFile ?: return
            if (!file.exists()) return
            val jsonStr = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            synchronized(memoryDict) {
                memoryDict.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val word = obj.getString("word")
                    val zhuyin = obj.optString("zhuyin", "")
                    val count = obj.optInt("count", 1)
                    val lastUsed = obj.optLong("lastUsed", System.currentTimeMillis())
                    memoryDict[word] = UserWordEntry(word, zhuyin, count, lastUsed)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "載入個人詞庫失敗", e)
        }
    }

    private fun saveToFile() {
        try {
            val file = dictFile ?: return
            val jsonArray = JSONArray()
            synchronized(memoryDict) {
                for (entry in memoryDict.values) {
                    val obj = JSONObject().apply {
                        put("word", entry.word)
                        put("zhuyin", entry.zhuyin)
                        put("count", entry.count)
                        put("lastUsed", entry.lastUsed)
                    }
                    jsonArray.put(obj)
                }
            }
            file.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "儲存個人詞庫失敗", e)
        }
    }

    private var savePending = false
    private val saveHandler = try { android.os.Looper.getMainLooper()?.let { android.os.Handler(it) } } catch (_: Exception) { null }
    private val saveRunnable = Runnable {
        executor.execute {
            saveToFile()
            savePending = false
        }
    }


    /**
     * 記錄使用者選擇詞彙（每次點擊選字時調用）
     * 記憶體內即時累加 count，自動執行 LRU/頻率衰減淘汰，防抖合併寫入磁碟
     */
    fun recordUsage(word: String, zhuyin: String = "") {
        if (word.isBlank() || word.startsWith("【")) return
        synchronized(memoryDict) {
            val entry = memoryDict.getOrPut(word) {
                UserWordEntry(word, zhuyin, 0)
            }
            entry.count += 1
            entry.lastUsed = System.currentTimeMillis()

            // 超過上限時執行自動清理（淘汰單次且最久未使用的條目）
            if (memoryDict.size > MAX_USER_ENTRIES) {
                pruneDictionaryLocked()
            }
        }

        // 防抖延遲 2.5 秒儲存，打字期間合併寫檔
        saveHandler?.removeCallbacks(saveRunnable)
        saveHandler?.postDelayed(saveRunnable, 2500L)
    }

    /** 撤銷一次選用紀錄（使用者上屏後立刻退格刪除，代表那次並非真心選用）；次數歸零就整筆移除。 */
    fun revokeUsage(word: String) {
        synchronized(memoryDict) {
            val entry = memoryDict[word] ?: return
            entry.count -= 1
            if (entry.count <= 0) memoryDict.remove(word)
        }
        saveHandler?.removeCallbacks(saveRunnable)
        saveHandler?.postDelayed(saveRunnable, 2500L)
    }

    /**
     * 自動淘汰：保留高頻詞與近期活躍詞，淘汰低頻/一次性孤兒詞
     */
    private fun pruneDictionaryLocked() {
        val now = System.currentTimeMillis()
        // 評分 = count * 1000 - 天數衰減
        val sortedEntries = memoryDict.values.sortedBy { entry ->
            val daysAgo = (now - entry.lastUsed) / (1000 * 3600 * 24)
            entry.count * 1000 - daysAgo
        }
        val removeCount = memoryDict.size - 400 // 清除至 400 條，保留緩衝區
        if (removeCount > 0) {
            for (i in 0 until removeCount) {
                memoryDict.remove(sortedEntries[i].word)
            }
        }
    }

    /**
     * 計算候選詞個人化增幅權重
     * 使用者選定/確認過的字詞賦予絕對優選增幅 (≥ 1,000,000)，超越字典既定權重穩居首選
     * 支援時間衰減模型 (Recency Boost)：近期剛選過之詞享有即時優先置頂權重
     * 支援讀音一致性校驗 (Reading-Aware Guard)：若指定了期望注音且記錄讀音不符，則拒絕灌入加權
     */
    fun getBoost(word: String, expectedZhuyin: String? = null): Int {
        val entry = synchronized(memoryDict) { memoryDict[word] } ?: return 0

        // 讀音一致性保護：若有提供期望讀音，且使用者記錄中存有明確讀音，去聲調後比對不符則不予加成
        if (expectedZhuyin != null && entry.zhuyin.isNotBlank()) {
            val entryClean = entry.zhuyin.filter { it !in "ˇˋˊ˙ " }
            val expectedClean = expectedZhuyin.filter { it !in "ˇˋˊ˙ " }
            if (entryClean.isNotEmpty() && expectedClean.isNotEmpty() && entryClean != expectedClean) {
                return 0
            }
        }

        val baseBoost = if (word.length == 1) {
            1_000_000 + minOf(entry.count * 100_000, 10_000_000)
        } else {
            2_000_000 + minOf(entry.count * 200_000, 20_000_000)
        }

        val now = System.currentTimeMillis()
        val diffMs = maxOf(0L, now - entry.lastUsed)
        val recencyBonus = when {
            diffMs < 10 * 60 * 1000L -> 3_000_000      // 10 分鐘內剛選過
            diffMs < 24 * 3600 * 1000L -> 1_500_000    // 24 小時內
            diffMs < 7 * 24 * 3600 * 1000L -> 500_000 // 7 天內
            else -> 0
        }

        return baseBoost + recencyBonus
    }

    fun getUsageCount(word: String): Int {
        return synchronized(memoryDict) { memoryDict[word]?.count ?: 0 }
    }

    /**
     * 取得目前記錄的所有個人詞彙列表（按使用次數倒序）
     */
    fun getAllEntries(): List<UserWordEntry> {
        return synchronized(memoryDict) {
            memoryDict.values.sortedByDescending { it.count }
        }
    }

    /**
     * 專屬常用詞彙數（過濾只選過 1 次的單字，真正反映使用者專屬詞彙）
     */
    fun getEntryCount(): Int {
        return synchronized(memoryDict) {
            memoryDict.values.count { it.word.length >= 2 || it.count >= 2 }
        }
    }

    /**
     * 匯出個人詞庫至串流（標準 TSV 格式）
     */
    fun exportToStream(outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
        writer.write("# All's good注音九宮格輸入法 - 個人詞庫備份檔\n")
        writer.write("# 格式：詞彙\t注音\t使用次數\n")

        val entries = getAllEntries()
        for (entry in entries) {
            writer.write("${entry.word}\t${entry.zhuyin}\t${entry.count}\n")
        }
        writer.flush()
    }

    /**
     * 從串流匯入個人詞庫（支援 TSV 格式與純詞彙文字檔）
     * @return 成功匯入/合併的詞條數量
     */
    fun importFromStream(inputStream: InputStream): Int {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        var importedCount = 0

        synchronized(memoryDict) {
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine

                val parts = trimmed.split("\t")
                val word = parts[0].trim()
                if (word.isNotEmpty()) {
                    val zhuyin = if (parts.size >= 2) parts[1].trim() else ""
                    val count = if (parts.size >= 3) parts[2].trim().toIntOrNull() ?: 5 else 5

                    val existing = memoryDict[word]
                    if (existing != null) {
                        existing.count += count
                        existing.lastUsed = System.currentTimeMillis()
                    } else {
                        memoryDict[word] = UserWordEntry(word, zhuyin, count)
                    }
                    importedCount++
                }
            }
        }

        saveToFile()
        onDictionaryChangedListener?.invoke()
        return importedCount
    }

    /**
     * 刪除指定單筆個人詞彙
     */
    fun removeEntry(word: String): Boolean {
        val removed = synchronized(memoryDict) {
            memoryDict.remove(word) != null
        }
        if (removed) {
            executor.execute {
                saveToFile()
            }
            onDictionaryChangedListener?.invoke()
        }
        return removed
    }

    /**
     * 清空本機個人詞庫記錄
     */
    fun clearDictionary() {
        synchronized(memoryDict) {
            memoryDict.clear()
        }
        executor.execute {
            saveToFile()
        }
        onDictionaryChangedListener?.invoke()
    }
}
