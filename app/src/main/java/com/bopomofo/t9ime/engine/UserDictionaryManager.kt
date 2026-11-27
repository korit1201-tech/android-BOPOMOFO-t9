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
        /** 舊資料降級時，每筆 count 的上限（等於最多約 3 倍的排序加分） */
        const val LEGACY_COUNT_CAP = 2
        /** 個人加分上限（自然對數，e^2.5 約 12 倍）；一次糾正約 +0.69（2 倍） */
        const val MAX_BOOST_NAT = 2.5
        /** 次數的半衰期（天）：久沒選的詞自然讓位，個人詞庫不會越用越固化 */
        const val HALF_LIFE_DAYS = 60.0

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
        migrateLegacyCountsIfNeeded()
    }

    /**
     * 舊版（v2.13.x 以前）把「點第一名、空白、Enter」也算成選用，count 被灌高，且加權比詞庫大兩個級別。
     * 新版加分有上限、會衰退，舊的高 count 必須一次性降級，否則整份舊資料會繼續蓋過詞庫排序。
     * 以標記檔控制只跑一次；降級前先備份舊檔。保留所有詞條（自建詞也不會消失），只是 count 最多留 [LEGACY_COUNT_CAP]。
     */
    private fun migrateLegacyCountsIfNeeded() {
        try {
            val dir = context?.filesDir ?: return
            val marker = File(dir, "bopomofo_user_dict.schema3")
            if (marker.exists()) return
            val file = dictFile
            if (file != null && file.exists()) {
                file.copyTo(File(dir, "bopomofo_user_dict.json.bak_v2"), overwrite = true)
            }
            synchronized(memoryDict) {
                for (e in memoryDict.values) if (e.count > LEGACY_COUNT_CAP) e.count = LEGACY_COUNT_CAP
            }
            saveToFile()
            marker.writeText("3")
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "個人詞庫舊資料降級失敗", e)
        }
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
            entry.lastUsed = nowMs()

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
     * 個人化加分（自然對數，≥ 0）：ln(1 + 次數×時間衰退)，上限 [MAX_BOOST_NAT]。
     * 次數只來自「使用者偏離預設的明確選擇」（見 LearningPolicy），所以一次糾正 ≈ +0.69（候選權重 ×2），
     * 5 次 ≈ +1.79（×6），封頂 ×12：學習能翻轉接近的候選，但永遠蓋不過詞庫與上下文給出的大差距。
     * 讀音一致性保護：指定了期望注音而記錄讀音不符（去聲調後比對）時不給加分，多音字不會跨讀音互相污染。
     */
    fun boostNat(word: String, expectedZhuyin: String? = null): Double {
        val entry = synchronized(memoryDict) { memoryDict[word] } ?: return 0.0
        if (expectedZhuyin != null && entry.zhuyin.isNotBlank()) {
            val entryClean = entry.zhuyin.filter { it !in "ˇˋˊ˙ " }
            val expectedClean = expectedZhuyin.filter { it !in "ˇˋˊ˙ " }
            if (entryClean.isNotEmpty() && expectedClean.isNotEmpty() && entryClean != expectedClean) return 0.0
        }
        val days = maxOf(0L, nowMs() - entry.lastUsed) / 86_400_000.0
        val p = entry.count * Math.pow(0.5, days / HALF_LIFE_DAYS)
        return minOf(MAX_BOOST_NAT, Math.log1p(p))
    }

    /** 把個人加分乘進詞庫權重（乘法：加分是倍率，不是固定常數）。 */
    fun applyBoost(weight: Int, word: String, expectedZhuyin: String? = null): Int {
        val u = boostNat(word, expectedZhuyin)
        if (u <= 0.0) return weight
        val scaled = weight.toDouble() * Math.exp(u)
        return if (scaled >= Int.MAX_VALUE.toDouble()) Int.MAX_VALUE else scaled.toInt()
    }

    /** 使用者「接受預設」：已學過的詞只更新最後使用時間，不加次數（不新建、不強化）。 */
    fun touch(word: String) {
        val hit = synchronized(memoryDict) { memoryDict[word]?.also { it.lastUsed = nowMs() } }
        if (hit == null) return
        saveHandler?.removeCallbacks(saveRunnable)
        saveHandler?.postDelayed(saveRunnable, 2500L)
    }

    /** 可注入的時鐘，單元測試用它模擬經過多天。 */
    internal var clock: (() -> Long)? = null
    private fun nowMs(): Long = clock?.invoke() ?: System.currentTimeMillis()

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
