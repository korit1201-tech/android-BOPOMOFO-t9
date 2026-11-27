package com.bopomofo.t9ime.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executors

/**
 * 個人詞庫的一筆學習紀錄（以「詞＋讀音」為單位，多音字各讀音互不影響）。
 *
 * - [pos]：正向分數（使用者明確選了它）。[neg]：負向分數（使用者跳過它、或選它後又刪掉改選別的）。
 *   兩者都是「在 [t0] 當下」的值，實際值要依時間衰退（見 [UserDictionaryManager.posNow]）。
 * - [count]：原始選用次數，只給介面顯示與相容舊版，不參與排序。
 * - [custom]：使用者自建詞，正向分數有下限、不隨時間消失，也不會被自動淘汰。
 */
data class UserWordEntry(
    val word: String,
    var zhuyin: String,
    var count: Int,
    var lastUsed: Long = System.currentTimeMillis(),
    var pos: Double = count.toDouble(),
    var neg: Double = 0.0,
    var t0: Long = lastUsed,
    var custom: Boolean = false
) {
    /** 排序加分的快取（自然對數），避免每次按鍵對每個候選重算指數與對數 */
    internal var cachedU: Double = 0.0
    internal var cachedAt: Long = Long.MIN_VALUE
}

/**
 * 本機個人化詞庫管理器
 *
 * 學習什麼（見 ZhuyinT9Engine.learnWord / acceptWord / learnPick）：
 * - 偏離預設的明確選擇才加正向分數；接受預設只維持新鮮；被跳過的前幾名加負向分數。
 * - 排序加分 u = ln(1+P) − 0.7·ln(1+N)，限制在 [[MIN_BOOST_NAT], [MAX_BOOST_NAT]]，以「倍率」乘進詞庫權重，
 *   所以學習能翻轉接近的候選，但永遠蓋不過詞庫與上下文給出的大差距。
 * - P 半衰期 [HALF_LIFE_POS_DAYS] 天、N 半衰期 [HALF_LIFE_NEG_DAYS] 天，久沒用的自然讓位。
 * 100% 離線：所有紀錄只存在手機端。
 */
class UserDictionaryManager private constructor(private val context: Context?) {

    private val memoryDict = LinkedHashMap<String, UserWordEntry>()
    private val byWord = HashMap<String, MutableList<UserWordEntry>>()
    private val executor = Executors.newSingleThreadExecutor()
    private val dictFile: File?
        get() = try { context?.filesDir?.let { File(it, "bopomofo_user_dict.json") } } catch (_: Exception) { null }

    var onDictionaryChangedListener: (() -> Unit)? = null

    /** 可注入的時鐘，單元測試用它模擬經過多天。 */
    internal var clock: (() -> Long)? = null
    private fun nowMs(): Long = clock?.invoke() ?: System.currentTimeMillis()

    /** 舊版資料尚未依詞庫降級（需在詞庫載入後由引擎呼叫 [migrateLegacy]） */
    @Volatile
    var needsLegacyMigration = false
        private set

    companion object {
        /** 非自建詞上限；超過時依 max(P,N) 由低到高淘汰 */
        const val MAX_LEARNED_ENTRIES = 3000
        /** 自建詞上限（只會手動刪除） */
        const val MAX_CUSTOM_ENTRIES = 5000
        /**
         * 個人加分上限（自然對數，e^6.5 約 665 倍）。上限要夠大：詞庫裡有些現代常用詞的權重被嚴重低估
         * （例如「手機」只有 10，而同鍵的「手記」是 4200），使用者反覆明確選它時必須有辦法追上。
         * 實際加多少由「縮小與被跳過者的差距」決定（見 ZhuyinT9Engine.learnPick），不是每次固定加一格。
         */
        const val MAX_BOOST_NAT = 6.5
        /** 負向最多讓候選降到 e^-1 約 0.37 倍，功能詞不會被永久打壓 */
        const val MIN_BOOST_NAT = -1.0
        const val NEG_WEIGHT = 0.7
        const val HALF_LIFE_POS_DAYS = 60.0
        const val HALF_LIFE_NEG_DAYS = 21.0
        /** 自建詞的正向分數下限 */
        const val CUSTOM_POS_FLOOR = 2.0
        /** 接受預設時對已學過詞的正向小增量（避免糾正過的詞慢慢衰退又回到原位） */
        const val ACCEPT_POS_DELTA = 0.2
        /** 排序加分快取的有效時間 */
        private const val CACHE_TTL_MS = 60_000L
        private const val MS_PER_DAY = 86_400_000.0
        private const val FLAG_CUSTOM = 1
        private const val SCHEMA_MARKER = "bopomofo_user_dict.schema3"

        @Volatile
        private var instance: UserDictionaryManager? = null

        fun getInstance(context: Context? = null): UserDictionaryManager {
            return instance ?: synchronized(this) {
                instance ?: UserDictionaryManager(context?.applicationContext).also { instance = it }
            }
        }

        /** 讀音去聲調與空白，當作索引鍵 */
        private fun cleanZhuyin(z: String): String = z.filter { it !in "ˇˋˊ˙ˉ " }

        private fun keyOf(word: String, zhuyin: String): String {
            val c = cleanZhuyin(zhuyin)
            return if (c.isEmpty()) word else "$word\u0001$c"
        }
    }

    init {
        loadFromFile()
        prepareLegacyMigration()
    }

    // ───────────── 索引與衰退 ─────────────

    private fun indexAdd(e: UserWordEntry) {
        memoryDict[keyOf(e.word, e.zhuyin)] = e
        val list = byWord.getOrPut(e.word) { ArrayList(1) }
        if (!list.contains(e)) list.add(e)
    }

    private fun indexRemove(e: UserWordEntry) {
        memoryDict.remove(keyOf(e.word, e.zhuyin))
        byWord[e.word]?.let { it.remove(e); if (it.isEmpty()) byWord.remove(e.word) }
    }

    /** 以「詞＋讀音」找紀錄；沒給讀音時取該詞分數最高的一筆。舊版沒有讀音的紀錄，任何讀音都適用。 */
    private fun lookupLocked(word: String, zhuyin: String?): UserWordEntry? {
        if (!zhuyin.isNullOrBlank()) {
            return memoryDict[keyOf(word, zhuyin)] ?: memoryDict[word]
        }
        val list = byWord[word] ?: return null
        return if (list.size == 1) list[0] else list.maxByOrNull { it.pos }
    }

    private fun halve(value: Double, deltaMs: Long, halfLifeDays: Double): Double =
        if (value <= 0.0) 0.0 else value * Math.pow(0.5, maxOf(0L, deltaMs) / MS_PER_DAY / halfLifeDays)

    /** 目前的正向分數（含時間衰退；自建詞有下限） */
    fun posNow(e: UserWordEntry): Double {
        val p = halve(e.pos, nowMs() - e.t0, HALF_LIFE_POS_DAYS)
        return if (e.custom) maxOf(p, CUSTOM_POS_FLOOR) else p
    }

    fun negNow(e: UserWordEntry): Double = halve(e.neg, nowMs() - e.t0, HALF_LIFE_NEG_DAYS)

    private fun computeU(e: UserWordEntry): Double {
        val u = Math.log1p(posNow(e)) - NEG_WEIGHT * Math.log1p(negNow(e))
        return u.coerceIn(MIN_BOOST_NAT, MAX_BOOST_NAT)
    }

    /** 在更新前把兩種分數結算到現在，之後 t0 = 現在 */
    private fun settleLocked(e: UserWordEntry) {
        val now = nowMs()
        e.pos = halve(e.pos, now - e.t0, HALF_LIFE_POS_DAYS)
        e.neg = halve(e.neg, now - e.t0, HALF_LIFE_NEG_DAYS)
        e.t0 = now
        e.cachedAt = Long.MIN_VALUE
    }

    // ───────────── 排序加分 ─────────────

    /**
     * 個人化加分（自然對數）：正值＝提前，負值＝降後。讀音有給就只認同讀音（多音字不跨讀音污染）。
     */
    fun boostNat(word: String, expectedZhuyin: String? = null): Double {
        synchronized(memoryDict) {
            val e = lookupLocked(word, expectedZhuyin) ?: return 0.0
            val now = nowMs()
            if (now - e.cachedAt in 0..CACHE_TTL_MS) return e.cachedU
            e.cachedU = computeU(e)
            e.cachedAt = now
            return e.cachedU
        }
    }

    /** 把個人加分乘進詞庫權重（乘法：加分是倍率，不是固定常數）。 */
    fun applyBoost(weight: Int, word: String, expectedZhuyin: String? = null): Int {
        val u = boostNat(word, expectedZhuyin)
        if (u == 0.0) return weight
        val scaled = weight.toDouble() * Math.exp(u)
        return if (scaled >= Int.MAX_VALUE.toDouble()) Int.MAX_VALUE else scaled.toInt()
    }

    // ───────────── 學習事件 ─────────────

    private fun scheduleSave() {
        saveHandler?.removeCallbacks(saveRunnable)
        saveHandler?.postDelayed(saveRunnable, 2500L)
    }

    /**
     * 記錄使用者「明確選用」（偏離預設的選擇、替換字等）。[amount] 是正向分數增量。
     * 回傳實際加上的分數，供上屏後立刻刪除時精準撤銷。
     */
    fun recordUsage(word: String, zhuyin: String = "", amount: Double = 1.0): Double {
        if (word.isBlank() || word.startsWith("【")) return 0.0
        synchronized(memoryDict) {
            val e = memoryDict[keyOf(word, zhuyin)] ?: UserWordEntry(word, zhuyin, 0, nowMs(), 0.0, 0.0, nowMs()).also { indexAdd(it) }
            settleLocked(e)
            e.pos += amount
            e.count += 1
            e.lastUsed = nowMs()
            if (e.zhuyin.isBlank() && zhuyin.isNotBlank()) e.zhuyin = zhuyin
            pruneIfNeededLocked()
        }
        scheduleSave()
        return amount
    }

    /** 要讓這個（詞, 讀音）的個人加分達到 [uTarget]，還需要補多少正向分數（已考慮目前的負向）。 */
    fun amountToReach(word: String, zhuyin: String?, uTarget: Double): Double {
        synchronized(memoryDict) {
            val e = lookupLocked(word, zhuyin)
            val p = if (e == null) 0.0 else posNow(e)
            val n = if (e == null) 0.0 else negNow(e)
            val needP = Math.exp(uTarget + NEG_WEIGHT * Math.log1p(n)) - 1.0
            return maxOf(0.0, needP - p)
        }
    }

    /** 使用者接受了排在它前面的詞：把這個對手累積的正向分數打折（自建詞不動），避免少數派靠累積搶走多數派。 */
    fun shrinkPositive(word: String, zhuyin: String?, factor: Double) {
        synchronized(memoryDict) {
            val e = lookupLocked(word, zhuyin) ?: return
            if (e.custom || e.pos <= 0.0) return
            settleLocked(e)
            e.pos *= factor
        }
        scheduleSave()
    }

    /** 負向訊號：使用者跳過了它，或選了它之後又刪掉改選別的。詞庫裡沒有紀錄時建立「只有負向」的條目（不會出現在詞彙清單）。 */
    fun recordNegative(word: String, zhuyin: String = "", amount: Double = 1.0) {
        if (word.isBlank() || word.startsWith("【") || amount <= 0.0) return
        synchronized(memoryDict) {
            val e = memoryDict[keyOf(word, zhuyin)] ?: UserWordEntry(word, zhuyin, 0, nowMs(), 0.0, 0.0, nowMs()).also { indexAdd(it) }
            settleLocked(e)
            e.neg += amount
            pruneIfNeededLocked()
        }
        scheduleSave()
    }

    /**
     * 使用者「接受預設」：不新建條目；負向減半，已學過（正向分數 > 0）的詞再小幅增加正向。
     * 這不是偏好表態，只是避免糾正過的詞慢慢衰退、候選順序來回跳。
     */
    fun acceptUsage(word: String, zhuyin: String? = null) {
        synchronized(memoryDict) {
            val e = lookupLocked(word, zhuyin) ?: return
            settleLocked(e)
            // 負向一律減半（只有負向、沒被選過的條目也要減，否則 9:1 混用時少數派的負向會一路累積而翻盤）
            e.neg *= 0.5
            if (e.pos > 0.0 || e.custom) {
                // 上限只擋「小幅累加」，不能把學習補差距得到的大分數硬壓下來（曾因此讓第一名接受後掉回第 14 名）
                e.pos = minOf(e.pos + ACCEPT_POS_DELTA, maxOf(e.pos, 30.0))
                e.lastUsed = nowMs()
            }
        }
        scheduleSave()
    }

    /** 撤銷一次記錄（精準回滾當時加上去的量）；分數歸零且不是自建詞就整筆移除。 */
    fun revoke(word: String, zhuyin: String, dPos: Double, dNeg: Double, countDelta: Int) {
        synchronized(memoryDict) {
            val e = memoryDict[keyOf(word, zhuyin)] ?: memoryDict[word] ?: return
            settleLocked(e)
            e.pos = maxOf(0.0, e.pos - dPos)
            e.neg = maxOf(0.0, e.neg - dNeg)
            e.count = maxOf(0, e.count - countDelta)
            if (e.pos < 0.01 && e.neg < 0.01 && !e.custom) indexRemove(e)
        }
        scheduleSave()
    }

    /** 自建詞：正向分數直接給 4（約 +1.6 nat），之後不衰退到 [CUSTOM_POS_FLOOR] 以下，也不被自動淘汰。 */
    fun markCustom(word: String, zhuyin: String) {
        if (word.isBlank()) return
        synchronized(memoryDict) {
            val e = memoryDict[keyOf(word, zhuyin)] ?: UserWordEntry(word, zhuyin, 0, nowMs(), 0.0, 0.0, nowMs()).also { indexAdd(it) }
            settleLocked(e)
            e.custom = true
            e.pos = maxOf(e.pos, 4.0)
            e.count = maxOf(e.count, 1)
            e.lastUsed = nowMs()
        }
        scheduleSave()
    }

    fun getUsageCount(word: String): Int {
        return synchronized(memoryDict) { byWord[word]?.sumOf { it.count } ?: 0 }
    }

    // ───────────── 淘汰 ─────────────

    private var eventsSincePrune = 0

    private fun pruneIfNeededLocked() {
        if (++eventsSincePrune < 200 && memoryDict.size <= MAX_LEARNED_ENTRIES + MAX_CUSTOM_ENTRIES) return
        eventsSincePrune = 0
        pruneLocked()
    }

    /** 低分條目清掉；非自建詞超過上限時依 max(P,N) 由低到高淘汰（同分先淘汰最舊的）。 */
    private fun pruneLocked() {
        val now = nowMs()
        for (e in memoryDict.values.toList()) {
            if (!e.custom && posNow(e) < 0.15 && negNow(e) < 0.15) indexRemove(e)
        }
        val learned = memoryDict.values.filter { !it.custom }
        if (learned.size > MAX_LEARNED_ENTRIES) {
            val sorted = learned.sortedWith(compareBy({ maxOf(posNow(it), negNow(it)) }, { it.lastUsed }))
            for (i in 0 until learned.size - MAX_LEARNED_ENTRIES) indexRemove(sorted[i])
        }
        val customs = memoryDict.values.filter { it.custom }
        if (customs.size > MAX_CUSTOM_ENTRIES) {
            val sorted = customs.sortedBy { it.lastUsed }
            for (i in 0 until customs.size - MAX_CUSTOM_ENTRIES) indexRemove(sorted[i])
        }
    }

    // ───────────── 檔案 ─────────────

    private fun loadFromFile() {
        try {
            val file = dictFile ?: return
            if (!file.exists()) return
            val jsonArray = JSONArray(file.readText(Charsets.UTF_8))
            synchronized(memoryDict) {
                memoryDict.clear(); byWord.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val word = obj.getString("word")
                    val zhuyin = obj.optString("zhuyin", "")
                    val count = obj.optInt("count", 1)
                    val lastUsed = obj.optLong("lastUsed", System.currentTimeMillis())
                    val v2 = obj.optInt("v", 1) >= 2
                    val e = UserWordEntry(
                        word, zhuyin, count, lastUsed,
                        pos = if (v2) obj.optDouble("pos", count.toDouble()) else count.toDouble(),
                        neg = if (v2) obj.optDouble("neg", 0.0) else 0.0,
                        t0 = if (v2) obj.optLong("t0", lastUsed) else lastUsed,
                        custom = v2 && (obj.optInt("f", 0) and FLAG_CUSTOM) != 0
                    )
                    indexAdd(e)
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
                for (e in memoryDict.values) {
                    jsonArray.put(JSONObject().apply {
                        put("word", e.word)
                        put("zhuyin", e.zhuyin)
                        // 舊欄位保留，回滾到舊版仍讀得到（count 以正向分數取整，至少 1）
                        put("count", maxOf(1, Math.round(e.pos).toInt()))
                        put("lastUsed", e.lastUsed)
                        put("v", 2)
                        put("pos", e.pos)
                        put("neg", e.neg)
                        put("t0", e.t0)
                        put("f", if (e.custom) FLAG_CUSTOM else 0)
                        put("picks", e.count)
                    })
                }
            }
            // 先寫暫存檔再改名，避免寫到一半被中斷毀掉整份學習紀錄
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(jsonArray.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) file.writeText(jsonArray.toString(), Charsets.UTF_8)
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

    // ───────────── 舊資料遷移 ─────────────

    /** 舊版資料：先備份，等詞庫載入後由引擎呼叫 [migrateLegacy] 依「是否在詞庫內」降級。 */
    private fun prepareLegacyMigration() {
        try {
            val dir = context?.filesDir ?: return
            if (File(dir, SCHEMA_MARKER).exists()) return
            val file = dictFile
            if (file != null && file.exists() && memoryDict.isNotEmpty()) {
                file.copyTo(File(dir, "bopomofo_user_dict.json.bak_v2"), overwrite = true)
                needsLegacyMigration = true
            } else {
                File(dir, SCHEMA_MARKER).writeText("3")
            }
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "個人詞庫遷移準備失敗", e)
        }
    }

    /**
     * 舊版把「點第一名、空白、Enter」都算成選用，count 被灌高。整體降級但不清空：
     * - 在詞庫內的詞：正向分數 = min(1.0, 0.25·ln(1+count))（count 200 也最多約 +0.69 nat）
     * - 不在詞庫、2 字以上、count ≥ 2：視為自建詞，正向 = min(4, 1+ln count)
     * - 不在詞庫、3~4 字、count = 1：可能是整句誤學，正向 0.3 讓它自然衰退
     * - 讀音含非注音字元的垃圾（舊 bug 學進去的注音原字串）：丟棄
     * @param inDictionary 該（詞, 讀音）詞庫是否本來就有
     */
    fun migrateLegacy(inDictionary: (word: String, zhuyin: String) -> Boolean) {
        if (!needsLegacyMigration) return
        synchronized(memoryDict) {
            val now = nowMs()
            for (e in memoryDict.values.toList()) {
                if (e.word.any { it in "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ" }) { indexRemove(e); continue }
                val c = maxOf(1, e.count)
                when {
                    inDictionary(e.word, e.zhuyin) -> { e.pos = minOf(1.0, 0.25 * Math.log1p(c.toDouble())); e.custom = false }
                    e.word.length >= 2 && c >= 2 -> { e.pos = minOf(4.0, 1.0 + Math.log(c.toDouble())); e.custom = true }
                    e.word.length in 3..4 -> { e.pos = 0.3; e.custom = false }
                    else -> { e.pos = minOf(1.0, 0.25 * Math.log1p(c.toDouble())); e.custom = false }
                }
                e.neg = 0.0
                e.t0 = now
                e.cachedAt = Long.MIN_VALUE
            }
        }
        needsLegacyMigration = false
        try { context?.filesDir?.let { File(it, SCHEMA_MARKER).writeText("3") } } catch (_: Exception) {}
        saveToFile()
    }

    /** 測試用：塞一筆「舊版格式」的紀錄並標記需要遷移 */
    internal fun seedLegacyForTest(word: String, zhuyin: String, count: Int) {
        synchronized(memoryDict) {
            indexAdd(UserWordEntry(word, zhuyin, count, nowMs(), count.toDouble(), 0.0, nowMs()))
        }
        needsLegacyMigration = true
    }

    // ───────────── 清單、匯出入 ─────────────

    /** 目前記錄的個人詞彙（只含有正向分數的，依正向分數倒序）；只有負向的條目不列出。 */
    fun getAllEntries(): List<UserWordEntry> {
        return synchronized(memoryDict) {
            memoryDict.values.filter { posNow(it) >= 0.05 || it.custom }.sortedByDescending { posNow(it) }
        }
    }

    /** 專屬常用詞彙數（過濾只選過 1 次的單字，真正反映使用者專屬詞彙） */
    fun getEntryCount(): Int {
        return synchronized(memoryDict) {
            memoryDict.values.count { (posNow(it) >= 0.05 || it.custom) && (it.word.length >= 2 || it.count >= 2) }
        }
    }

    /** 給介面顯示：正向、負向、目前排序加分 */
    fun describe(e: UserWordEntry): String {
        val u = synchronized(memoryDict) { computeU(e) }
        return String.format(java.util.Locale.US, "正向%.1f 負向%.1f 加分%+.2f%s", posNow(e), negNow(e), u, if (e.custom) " 自建" else "")
    }

    /** 匯出 TSV：詞彙、注音、次數(=round 正向)、負向、旗標；舊版匯入程式只讀前 3 欄，相容。 */
    fun exportToStream(outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
        writer.write("# All's good注音九宮格輸入法 - 個人詞庫備份檔\n")
        writer.write("# 格式：詞彙\\t注音\\t次數\\t負向\\t旗標(1=自建)\n")
        for (entry in getAllEntries()) {
            writer.write(String.format(java.util.Locale.US, "%s\t%s\t%d\t%.2f\t%d\n", entry.word, entry.zhuyin, maxOf(1, Math.round(posNow(entry)).toInt()), negNow(entry), if (entry.custom) 1 else 0))
        }
        writer.flush()
    }

    /**
     * 匯入 TSV（支援 5 欄新格式、3 欄舊格式與純詞彙文字檔）。
     * 舊格式的次數可能被舊版灌高，一律最多當 4；不把時間設成「剛剛」之外的值，衰退照常進行。
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
                if (word.isEmpty()) return@forEachLine
                val zhuyin = if (parts.size >= 2) parts[1].trim() else ""
                val count = if (parts.size >= 3) parts[2].trim().toIntOrNull() ?: 2 else 2
                val neg = if (parts.size >= 4) parts[3].trim().toDoubleOrNull() ?: 0.0 else 0.0
                val custom = parts.size >= 5 && parts[4].trim() == "1"
                val e = memoryDict[keyOf(word, zhuyin)] ?: UserWordEntry(word, zhuyin, 0, nowMs(), 0.0, 0.0, nowMs()).also { indexAdd(it) }
                settleLocked(e)
                e.pos += minOf(count, 4).toDouble()
                e.neg += neg
                e.count += count
                e.custom = e.custom || custom
                importedCount++
            }
        }
        saveToFile()
        onDictionaryChangedListener?.invoke()
        return importedCount
    }

    /** 刪除指定詞彙（所有讀音） */
    fun removeEntry(word: String): Boolean {
        val removed = synchronized(memoryDict) {
            val list = byWord[word]?.toList() ?: emptyList()
            list.forEach { indexRemove(it) }
            list.isNotEmpty()
        }
        if (removed) {
            executor.execute { saveToFile() }
            onDictionaryChangedListener?.invoke()
        }
        return removed
    }

    /** 清空本機個人詞庫記錄 */
    fun clearDictionary() {
        synchronized(memoryDict) { memoryDict.clear(); byWord.clear() }
        executor.execute { saveToFile() }
        onDictionaryChangedListener?.invoke()
    }

    /** 重設學習：清掉所有學到的加分與負向，保留自建詞（含其正向分數）。回傳被清掉的筆數。 */
    fun resetLearningKeepCustom(): Int {
        val n = synchronized(memoryDict) {
            val drop = memoryDict.values.filter { !it.custom }
            drop.forEach { indexRemove(it) }
            drop.size
        }
        executor.execute { saveToFile() }
        onDictionaryChangedListener?.invoke()
        return n
    }
}
