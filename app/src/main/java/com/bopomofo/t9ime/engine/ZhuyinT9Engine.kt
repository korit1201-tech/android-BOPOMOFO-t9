package com.bopomofo.t9ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 注音 T9 解碼引擎（基於 libchewing-data 詞庫）
 *
 * 設計原則：
 * - 完全依賴 libchewing 的頻率權重排序，不做自訂預測邏輯
 * - Trie 前綴樹查詢 + 個人化使用頻率加權（UserDictionary）
 * - 零韻母容錯已在 KeyMapping.getTolerantSequences() 處理
 * - 字典非同步背景極速載入，主線程 0 阻塞秒開鍵盤
 *
 * v1.6.5 新增：
 * - pollConfirmedPrefix 前綴確認門檻降低（≥6鍵、剩餘≥3鍵），讓連續輸入時「今天」等詞更快自動上屏
 * - getHomophonesFor() 同音字查詢，供候選詞長按替換功能使用
 */
class ZhuyinT9Engine(rawContext: Context? = null) {
    private val context = rawContext?.applicationContext ?: rawContext

    @Volatile
    private var trie = TrieDictionary()
    private var currentKeys = mutableListOf<Int>()
    private var currentToneIndex = 0

    private var cachedCandidates: List<DictEntry> = emptyList()
    private var cachedZhuyinCombos: List<String> = emptyList()
    private var lockedZhuyinCombo: String? = null

    private val userDict = UserDictionaryManager.getInstance(context)
    @Volatile
    private var nextWordMap = mutableMapOf<String, MutableList<DictEntry>>()
    @Volatile
    private var charZhuyinMap = mutableMapOf<Char, MutableList<String>>()
    @Volatile
    private var initialMap = mutableMapOf<String, MutableList<DictEntry>>()
    /**
     * 反向音節索引：cleanZhuyin(去聲調) -> DictEntry 列表（單字）
     * 在載入字典時同步建立，讓 searchFullZhuyin 步驟 5 從 O(n) 線性掃描降至 O(1)
     */
    @Volatile
    private var soundToCharMap = mutableMapOf<String, MutableList<DictEntry>>()

    /**
     * 聲母/首字符號 -> 單字列表 (O(1) 快速索引，取代每敲一鍵遍歷整個 soundToCharMap 的線性掃描)
     */
    @Volatile
    private var initialToCharMap = mutableMapOf<Char, MutableList<DictEntry>>()

    /**
     * 完整注音/去聲調注音 -> 詞彙條目反向索引（支援單字與多字詞，如 ㄓㄉㄠ -> [知道]）
     * 專供全鍵盤/實體鍵盤精準出詞與新酷音動態規劃組句使用
     */
    @Volatile
    private var fullZhuyinWordMap = mutableMapOf<String, MutableList<DictEntry>>()

    // Bigram 語境關聯索引：prevWord -> (nextWord -> weight)（手寫詞對與使用者打字時學到的，整數權重）
    private val bigramMap = HashMap<String, MutableMap<String, Int>>()

    /** 預先訓練的詞對關聯強度（來自 assets/bigram_core.bin，可再疊加使用者下載的完整資料包）。 */
    @Volatile
    private var bigramModel: BigramModel? = null

    fun setBigramModel(model: BigramModel?) {
        bigramModel = model
    }

    fun loadBigramModelForTest(file: java.io.File) {
        bigramModel = BigramModel.load(file)
    }

    /**
     * 載入詞對模型：優先用使用者下載的完整資料包（filesDir/bigram_full.bin，格式或版本不符就略過），
     * 沒有就用 APK 內建的核心包（assets/bigram_core.bin）。任何失敗都只記錄並退回「沒有模型」，不影響打字。
     */
    private fun loadBigramModelFromStorage(): BigramModel? {
        val ctx = context ?: return null
        try {
            val full = java.io.File(ctx.filesDir, BIGRAM_FULL_FILE)
            if (full.isFile) {
                BigramModel.load(full)?.let { return it }
                android.util.Log.w("BopomofoIME", "完整詞對資料包格式不符，改用內建核心包")
            }
        } catch (e: Exception) {
            android.util.Log.w("BopomofoIME", "讀取完整詞對資料包失敗，改用內建核心包", e)
        }
        return try {
            ctx.assets.open(BIGRAM_CORE_ASSET).use { BigramModel.load(it) }
        } catch (e: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "載入內建詞對模型失敗", e)
            null
        }
    }

    /**
     * 各模式把「詞對加分（nat）」併入自己分數的強度倍率。預設值由 BigramBenchmarkTest 在 600 句驗證集上掃描決定
     * （2026-10-24）：用詞庫詞頻當基準的模型下，這幾個值在合理範圍內結果幾乎不變（九鍵權重 3~10、全鍵盤 2~8
     * 都在同一個高原），所以不是精細調出來的臨界值。internal 只為了讓測試能掃參數。
     */
    internal var bigramScaleT9Cost = 3.0
    internal var bigramScaleT9Weight = 6.0
    internal var bigramScaleFull = 4.0
    internal var bigramScaleSentence = 1.0

    /**
     * 全鍵盤單音節輸入「沒有打聲調符號」時，給「沒有聲調符號的字（一聲）」的固定加分。
     * 一聲字在注音裡本來就不寫符號，所以舊版把沒打聲調視為「想打一聲」；但習慣省略聲調的人，
     * 這個加分（舊值 500 萬，遠大於任何詞頻）會讓所有一聲同音字（窩、渦、萵…）蓋過常用字。
     * 驗證集實測（出貨模型，逐詞第 1 名；不打聲調／打聲調）：500 萬（舊值）66.3%／89.8%、3 萬 81.5%／89.5%、
     * 0 82.8%／88.6%。會打聲調的人一聲字不寫符號，所以歸零對他們有小幅退步；取 3 萬當折衷：
     * 習慣省略聲調的主要用法大幅進步，會打聲調的人只少 0.4 個百分點。
     */
    internal var fullNoToneFirstBonus = 30_000

    @Volatile
    var isDictionaryLoaded = false
        private set

    var onDictionaryLoadedListener: (() -> Unit)? = null
    private val mainHandler: Handler? = try { Looper.getMainLooper()?.let { Handler(it) } } catch (_: Exception) { null }

    companion object {
        val TONE_SYMBOLS = listOf(' ', 'ˇ', 'ˋ', 'ˊ', '˙')

        /** APK 內建的詞對核心包；使用者下載的完整包放在 filesDir（備份規則已排除）。 */
        const val BIGRAM_CORE_ASSET = "bigram_core.bin"
        const val BIGRAM_FULL_FILE = "bigram_full.bin"

        // ── 九鍵專用斷詞參數（成本 = -ln(詞頻/總詞頻) + 每詞固定成本，越低越好） ──
        /** 每多一個詞的固定成本：偏好用較少、較長的詞組成整句 */
        private const val T9_WORD_PENALTY = 3.5
        /** 容錯（跨鍵位模糊音）詞條的額外成本 */
        private const val T9_TOLERANT_PENALTY = 8.0
        /** 尾端未打完的詞，每缺一個按鍵的成本 */
        private const val T9_TAIL_MISS = 2.5
        /** 尾端未完成詞向下蒐集的深度（缺 1~2 鍵） */
        private const val T9_TAIL_DEPTH = 1
        /** 尾端未完成詞每個節點只看權重最高的前幾名 */
        private const val T9_TAIL_PER_NODE = 3
        /** 整句 n-best 每個位置保留的路徑數 */
        private const val T9_BEAM = 4
        /** 斷詞時單一詞條權重上限，避免個人學習詞（5000 萬級權重）讓成本趨近 0 而綁架斷詞 */
        private const val T9_DP_WEIGHT_CAP = 600_000
        /** 超過此鍵數不做整句斷詞（效能保護：上限 16 鍵涵蓋 6~8 字常用短句，杜絕 DP 運算超時） */
        private const val T9_MAX_DP_KEYS = 16
        /** 左側組合：先放幾個詞庫衍生的組合，再放音節切分組合 */
        private const val T9_WORD_COMBOS_FIRST = 5
        /** 左側組合：音節切分組合的數量 */
        private const val T9_SPLIT_COMBO_LIMIT = 6
        /** 提供給使用者的「部分上屏」候選數量 */
        private const val T9_PARTIAL_LIMIT = 8
        /** 部分上屏候選插在整詞候選之後的位置（保證第一順位永遠是涵蓋全部按鍵的候選） */
        private const val T9_PARTIAL_INSERT_AT = 4
    }

    init {
        initDefaultBigrams()
        loadUserDictionaryEntries(trie, charZhuyinMap, fullZhuyinWordMap)
        userDict.onDictionaryChangedListener = {
            loadUserDictionaryEntries(trie, charZhuyinMap, fullZhuyinWordMap)
            if (currentKeys.isNotEmpty()) {
                recalculate()
            }
        }
        if (context != null) {
            startAsyncDictionaryLoading()
        }
    }

    private fun initDefaultBigrams() {
        addBigramPair("去看", "醫生", 800)
        addBigramPair("看", "醫生", 600)
        addBigramPair("去", "醫院", 700)
        addBigramPair("醫院", "看病", 800)
        addBigramPair("醫院", "吃藥", 600)
        addBigramPair("吃", "飯", 800)
        addBigramPair("吃", "藥", 600)
        addBigramPair("洗", "澡", 800)
        addBigramPair("洗", "手", 700)
        addBigramPair("睡", "覺", 800)
        addBigramPair("喝", "水", 800)
        addBigramPair("喝", "茶", 600)
        addBigramPair("喝", "咖啡", 700)
        addBigramPair("今天", "天氣", 700)
        addBigramPair("大家", "好", 800)
        addBigramPair("非常", "感謝", 800)
        addBigramPair("祝你", "順心", 800)
        addBigramPair("台灣", "大學", 600)
        addBigramPair("台灣", "高鐵", 700)
        addBigramPair("捷運", "站", 700)
        val commonPairs = listOf(
            "我們" to "可以", "我們" to "要", "你們" to "要", "他們" to "在",
            "現在" to "在", "現在" to "要", "已經" to "在", "已經" to "有",
            "可以" to "幫我", "可以" to "嗎", "幫我" to "看", "幫我" to "買",
            "覺得" to "很", "覺得" to "可以", "知道" to "嗎", "知道" to "了",
            "怎麼" to "辦", "怎麼" to "樣", "什麼" to "時候", "什麼" to "意思",
            "因為" to "我", "所以" to "我", "如果" to "你", "還是" to "要",
            "應該" to "是", "應該" to "可以", "真的" to "很", "真的" to "嗎",
            "沒問題" to "了", "不好意思" to "我", "謝謝" to "你", "辛苦" to "了"
        )
        for ((p, n) in commonPairs) {
            addBigramPair(p, n, 600)
        }
        loadBigramFromFile()
    }

    private fun getBigramFile(): java.io.File? {
        return try {
            val ctx = context ?: return null
            java.io.File(ctx.filesDir, "user_bigram.json")
        } catch (_: Exception) {
            null
        }
    }
    private val bigramExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val bigramSaveRunnable = Runnable {
        bigramExecutor.execute {
            saveBigramToFile()
        }
    }

    private fun loadBigramFromFile() {
        try {
            val file = getBigramFile() ?: return
            if (!file.exists()) return
            val content = file.readText(Charsets.UTF_8)
            val json = org.json.JSONObject(content)
            val keys = json.keys()
            synchronized(bigramMap) {
                while (keys.hasNext()) {
                    val prev = keys.next()
                    val nextObj = json.getJSONObject(prev)
                    val nextKeys = nextObj.keys()
                    val map = bigramMap.getOrPut(prev) { HashMap() }
                    while (nextKeys.hasNext()) {
                        val next = nextKeys.next()
                        map[next] = nextObj.getInt(next)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "載入 Bigram 詞頻關聯失敗", e)
        }
    }

    private fun saveBigramToFile() {
        try {
            val file = getBigramFile() ?: return
            val json = org.json.JSONObject()
            synchronized(bigramMap) {
                for ((prev, nextMap) in bigramMap) {
                    val nextObj = org.json.JSONObject()
                    for ((next, weight) in nextMap) {
                        nextObj.put(next, weight)
                    }
                    json.put(prev, nextObj)
                }
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "儲存 Bigram 詞頻關聯失敗", e)
        }
    }

    private fun addBigramPair(prev: String, next: String, weight: Int) {
        val nextMap = bigramMap.getOrPut(prev) { HashMap() }
        nextMap[next] = (nextMap[next] ?: 0) + weight
    }

    fun learnBigram(prevWord: String, nextWord: String) {
        if (prevWord.isBlank() || nextWord.isBlank()) return
        synchronized(bigramMap) {
            addBigramPair(prevWord, nextWord, 500)
        }
        mainHandler?.removeCallbacks(bigramSaveRunnable)
        mainHandler?.postDelayed(bigramSaveRunnable, 2500L)
    }

    fun getBigramBoost(prevWord: String?, candidateWord: String): Int {
        if (prevWord == null) return 0
        return synchronized(bigramMap) {
            bigramMap[prevWord]?.get(candidateWord) ?: 0
        }
    }

    /** 線性詞頻依語境放大：weight × e^(加分)，上限 e^4 並夾在 Int 範圍內（DictEntry.weight 是 Int）。 */
    private fun bigramScaled(weight: Int, prevWord: String?, candidateWord: String): Int {
        val nat = getBigramBonus(prevWord, candidateWord) * bigramScaleFull
        if (nat <= 0.0) return weight
        val scaled = weight.toDouble() * Math.exp(minOf(nat, 4.0))
        return if (scaled > 1_900_000_000.0) 1_900_000_000 else scaled.toInt()
    }

    /**
     * 「前一個詞 → 候選詞」的對數加分（nat，≥ 0）。
     * 取預先訓練模型與（手寫＋使用者學習）整數權重換算值中較大的那個，不重複計算：
     * 整數權重 b 以 ln(1 + b/80) 換算，與舊版九鍵成本公式一致（b=800 ≈ 2.4 nat）。
     */
    fun getBigramBonus(prevWord: String?, candidateWord: String): Double {
        if (prevWord == null) return 0.0
        val trained = bigramModel?.bonus(prevWord, candidateWord) ?: 0.0
        val learned = getBigramBoost(prevWord, candidateWord)
        val learnedNat = if (learned > 0) Math.log1p(learned / 80.0) else 0.0
        return if (trained > learnedNat) trained else learnedNat
    }

    private fun startAsyncDictionaryLoading() {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val startTime = android.os.SystemClock.elapsedRealtime()
            android.util.Log.d("BopomofoIME_Perf", "[Perf] 字典背景載入開始 (Thread priority set to BACKGROUND)")

            val newTrie = TrieDictionary()
            val newNextWordMap = mutableMapOf<String, MutableList<DictEntry>>()
            val newCharZhuyinMap = mutableMapOf<Char, MutableList<String>>()
            val newInitialMap = mutableMapOf<String, MutableList<DictEntry>>()
            val newSoundToCharMap = mutableMapOf<String, MutableList<DictEntry>>()
            val newFullZhuyinWordMap = mutableMapOf<String, MutableList<DictEntry>>()
            val newInitialToCharMap = mutableMapOf<Char, MutableList<DictEntry>>()
            loadDictionaryInternal(newTrie, newNextWordMap, newCharZhuyinMap, newInitialMap, newSoundToCharMap, newFullZhuyinWordMap, newInitialToCharMap)
            loadUserDictionaryEntries(newTrie, newCharZhuyinMap, newFullZhuyinWordMap)
            val newBigramModel = loadBigramModelFromStorage()

            val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
            android.util.Log.d("BopomofoIME_Perf", "[Perf] 字典全量解析完成，總耗時: ${elapsed}ms")

            val updateAction = {
                trie = newTrie
                bigramModel = newBigramModel
                nextWordMap = newNextWordMap
                charZhuyinMap = newCharZhuyinMap
                initialMap = newInitialMap
                soundToCharMap = newSoundToCharMap
                fullZhuyinWordMap = newFullZhuyinWordMap
                initialToCharMap = newInitialToCharMap
                isDictionaryLoaded = true
                if (currentKeys.isNotEmpty()) {
                    recalculate()
                }
                onDictionaryLoadedListener?.invoke()
            }
            if (mainHandler != null) {
                mainHandler.post { updateAction() }
            } else {
                updateAction()
            }
        }, "ZhuyinT9DictLoader").start()
    }

    fun loadDictionaryForTest(file: java.io.File = java.io.File("src/main/assets/dict_tw.txt")) {
        val newTrie = TrieDictionary()
        val newNextWordMap = mutableMapOf<String, MutableList<DictEntry>>()
        val newCharZhuyinMap = mutableMapOf<Char, MutableList<String>>()
        val newInitialMap = mutableMapOf<String, MutableList<DictEntry>>()
        val newSoundToCharMap = mutableMapOf<String, MutableList<DictEntry>>()
        val newFullZhuyinWordMap = mutableMapOf<String, MutableList<DictEntry>>()
        val newInitialToCharMap = mutableMapOf<Char, MutableList<DictEntry>>()
        file.inputStream().use { stream ->
            loadDictionaryInternal(newTrie, newNextWordMap, newCharZhuyinMap, newInitialMap, newSoundToCharMap, newFullZhuyinWordMap, newInitialToCharMap, stream)
        }
        loadUserDictionaryEntries(newTrie, newCharZhuyinMap, newFullZhuyinWordMap)
        trie = newTrie
        nextWordMap = newNextWordMap
        charZhuyinMap = newCharZhuyinMap
        initialMap = newInitialMap
        soundToCharMap = newSoundToCharMap
        fullZhuyinWordMap = newFullZhuyinWordMap
        initialToCharMap = newInitialToCharMap
        isDictionaryLoaded = true
    }

    private fun loadUserDictionaryEntries(
        targetTrie: TrieDictionary,
        targetCharZhuyinMap: MutableMap<Char, MutableList<String>>,
        targetFullZhuyinWordMap: MutableMap<String, MutableList<DictEntry>>? = null
    ) {
        val userEntries = userDict.getAllEntries()
        for (u in userEntries) {
            if (u.zhuyin.isNotEmpty()) {
                // 使用者選過的詞給予加權，但多字詞給予高優先權，單字則適度加權 (避免單字在 Trie 中壓死多字詞)
                val weight = if (u.word.length == 1) {
                    minOf(120_000 + u.count * 10_000, 200_000)
                } else {
                    5_000_000 + minOf(u.count * 6_000_000, 100_000_000)
                }
                val entry = DictEntry(u.word, u.zhuyin, weight)
                targetTrie.insert(entry)
                if (targetFullZhuyinWordMap != null) {
                    val clean = u.zhuyin.filter { it !in "ˇˋˊ˙ " }
                    val list = targetFullZhuyinWordMap.getOrPut(clean) { ArrayList(2) }
                    list.removeAll { it.word == u.word }
                    list.add(0, entry)
                }
                if (u.word.length == 1) {
                    val list = targetCharZhuyinMap.getOrPut(u.word[0]) { ArrayList(2) }
                    if (!list.contains(u.zhuyin)) {
                        list.add(0, u.zhuyin)
                    }
                }
            }
        }
    }

    @Volatile
    private var totalDictWeight: Long = 60_000_000L
    @Volatile
    private var logTotalWeight: Double = 17.91

    fun loadDictionaryForTest(stream: java.io.InputStream) {
        val newTrie = TrieDictionary()
        val newNextWordMap = HashMap<String, MutableList<DictEntry>>()
        val newCharZhuyinMap = HashMap<Char, MutableList<String>>()
        val newInitialMap = HashMap<String, MutableList<DictEntry>>()
        val newSoundToCharMap = HashMap<String, MutableList<DictEntry>>()
        val newFullZhuyinWordMap = HashMap<String, MutableList<DictEntry>>()
        val newInitialToCharMap = HashMap<Char, MutableList<DictEntry>>()

        loadDictionaryInternal(
            newTrie,
            newNextWordMap,
            newCharZhuyinMap,
            newInitialMap,
            newSoundToCharMap,
            newFullZhuyinWordMap,
            newInitialToCharMap,
            stream
        )

        trie = newTrie
        nextWordMap = newNextWordMap
        charZhuyinMap = newCharZhuyinMap
        initialMap = newInitialMap
        soundToCharMap = newSoundToCharMap
        fullZhuyinWordMap = newFullZhuyinWordMap
        initialToCharMap = newInitialToCharMap
        isDictionaryLoaded = true
    }

    private fun loadDictionaryInternal(
        targetTrie: TrieDictionary,
        targetNextWordMap: MutableMap<String, MutableList<DictEntry>>,
        targetCharZhuyinMap: MutableMap<Char, MutableList<String>>,
        targetInitialMap: MutableMap<String, MutableList<DictEntry>>,
        targetSoundToCharMap: MutableMap<String, MutableList<DictEntry>>,
        targetFullZhuyinWordMap: MutableMap<String, MutableList<DictEntry>>,
        targetInitialToCharMap: MutableMap<Char, MutableList<DictEntry>>,
        customStream: java.io.InputStream? = null
    ) {
        val nextWordTrack = mutableMapOf<String, HashSet<String>>()
        val initialToCharTrack = mutableMapOf<Char, HashSet<String>>()
        val localSyllableWeights = HashMap<String, Long>()
        var localTotalSyllableWeight = 0L
        var accumulatedWeight = 0L
        var lineCount = 0
        var splitCallCount = 0
        var splitTimeNs = 0L

        val parseStartTime = android.os.SystemClock.elapsedRealtime()
        try {
            val stream = customStream ?: context?.assets?.open("dict_tw.txt") ?: return
            stream.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream), 65536).useLines { lines ->
                    for (line in lines) {
                        lineCount++
                        val tab1 = line.indexOf('\t')
                        if (tab1 == -1) continue
                        val tab2 = line.indexOf('\t', tab1 + 1)
                        if (tab2 == -1) continue

                        val word = line.substring(0, tab1)
                        val zhuyin = line.substring(tab1 + 1, tab2)
                        val tab3 = line.indexOf('\t', tab2 + 1)
                        val weight = (if (tab3 != -1) line.substring(tab2 + 1, tab3) else line.substring(tab2 + 1)).toIntOrNull() ?: 100
                        val precomputedInitials = if (tab3 != -1) line.substring(tab3 + 1) else null

                        val entry = DictEntry(word, zhuyin, weight)
                        targetTrie.insert(entry)
                        accumulatedWeight += weight

                        // 構建精準全注音/去聲調注音詞條索引 (支援單字與多字詞)
                        val cleanZhuyinNoSpace = zhuyin.filter { it !in "ˇˋˊ˙ " }
                        val fullList = targetFullZhuyinWordMap.getOrPut(cleanZhuyinNoSpace) { ArrayList(2) }
                        if (fullList.none { it.word == word }) {
                            fullList.add(entry)
                        }

                        // 構建簡拼（聲母偷懶輸入）索引表 (2~6字詞，優先使用離線預處理欄位，0 回溯開銷)
                        val cleanZhuyin = zhuyin.filter { it !in "ˇˋˊ˙" }
                        if (!precomputedInitials.isNullOrEmpty()) {
                            val list = targetInitialMap.getOrPut(precomputedInitials) { ArrayList(4) }
                            list.add(entry)
                        } else if (word.length in 2..6) {
                            splitCallCount++
                            val t0 = System.nanoTime()
                            val syllables = SyllableManager.splitIntoSyllables(cleanZhuyin, word.length)
                            splitTimeNs += (System.nanoTime() - t0)

                            if (syllables != null && syllables.size == word.length) {
                                val initials = syllables.map { it[0] }.joinToString("")
                                if (initials.isNotEmpty()) {
                                    val list = targetInitialMap.getOrPut(initials) { ArrayList(4) }
                                    list.add(entry)
                                }
                            }
                        }

                        // 統計單字音節歷史頻率（教育部 429 個合法音節）與記錄單字注音查表 + 反向音節索引
                        if (word.length == 1) {
                            if (cleanZhuyin.isNotEmpty() && weight > 0) {
                                localSyllableWeights[cleanZhuyin] = (localSyllableWeights[cleanZhuyin] ?: 0L) + weight
                                localTotalSyllableWeight += weight
                            }
                            val list = targetCharZhuyinMap.getOrPut(word[0]) { ArrayList(2) }
                            if (!list.contains(zhuyin)) {
                                list.add(zhuyin)
                            }
                            val soundList = targetSoundToCharMap.getOrPut(cleanZhuyin) { ArrayList(4) }
                            if (soundList.none { it.word == word }) {
                                soundList.add(entry)
                            }
                            if (cleanZhuyin.isNotEmpty()) {
                                val firstInitial = cleanZhuyin[0]
                                val initSet = initialToCharTrack.getOrPut(firstInitial) { HashSet(64) }
                                if (initSet.add(word)) {
                                    val initList = targetInitialToCharMap.getOrPut(firstInitial) { ArrayList(64) }
                                    initList.add(entry)
                                }
                            }
                        }

                        // 單次讀取時構建高頻接續聯想詞庫（weight >= 25 且使用 HashSet 快速去重）
                        if (word.length in 2..4 && weight >= 25) {
                            val addNext = { p: String, n: String ->
                                val set = nextWordTrack.getOrPut(p) { HashSet(8) }
                                if (set.size < 10 && set.add(n)) {
                                    val list = targetNextWordMap.getOrPut(p) { ArrayList(8) }
                                    list.add(DictEntry(n, "", weight))
                                }
                            }
                            when (word.length) {
                                2 -> addNext(word.substring(0, 1), word.substring(1))
                                3 -> {
                                    addNext(word.substring(0, 1), word.substring(1))
                                    addNext(word.substring(0, 2), word.substring(2))
                                }
                                4 -> {
                                    addNext(word.substring(0, 2), word.substring(2))
                                    addNext(word.substring(0, 1), word.substring(1))
                                }
                            }
                        }
                    }
                }
            }

            // 原子性替換音節權重，避免中途重置
            SyllableManager.applyNewWeights(localSyllableWeights, localTotalSyllableWeight)

            totalDictWeight = accumulatedWeight
            logTotalWeight = Math.log(maxOf(accumulatedWeight.toDouble(), 1.0))

            for ((_, list) in targetNextWordMap) {
                list.sortByDescending { it.weight }
            }
            for ((_, list) in targetInitialMap) {
                list.sortByDescending { it.weight }
            }
            for ((_, list) in targetFullZhuyinWordMap) {
                list.sortByDescending { it.weight }
            }
            for ((_, list) in targetInitialToCharMap) {
                list.sortByDescending { it.weight }
            }

            // 顯式清空載入過程中的大型暫存去重集合，避免與新舊字典疊加形成記憶體峰值
            nextWordTrack.clear()
            initialToCharTrack.clear()
            localSyllableWeights.clear()

            val parseElapsed = android.os.SystemClock.elapsedRealtime() - parseStartTime
            val splitMs = splitTimeNs / 1_000_000
            android.util.Log.d(
                "BopomofoIME_Perf",
                "[Perf] 字典解析完成: 總行數=$lineCount 筆, 解析總耗時=${parseElapsed}ms (其中 splitIntoSyllables 呼叫 $splitCallCount 次，耗時 ${splitMs}ms)"
            )
        } catch (e: Exception) {
            android.util.Log.e("BopomofoIME", "字典載入失敗", e)
        }
    }

    // ───────── 按鍵操作 ─────────

    fun pressKey(keyId: Int): List<DictEntry> {
        currentKeys.add(keyId)
        currentToneIndex = 0
        lockedZhuyinCombo = null
        recalculate()
        return cachedCandidates
    }

    fun cycleTone(): Pair<Char, List<DictEntry>> {
        if (currentKeys.isEmpty()) return Pair(' ', emptyList())

        currentToneIndex = (currentToneIndex + 1) % TONE_SYMBOLS.size

        if (currentKeys.isNotEmpty() && currentKeys.last() == 11) {
            currentKeys.removeAt(currentKeys.size - 1)
        }
        if (currentToneIndex > 0) {
            currentKeys.add(11)
        }

        lockedZhuyinCombo = null
        recalculate()
        return Pair(TONE_SYMBOLS[currentToneIndex], cachedCandidates)
    }

    /**
     * 直接設置指定聲調（供測試與實體鍵盤聲調鍵直接指定）
     */
    fun setTone(toneChar: Char): Pair<Char, List<DictEntry>> {
        if (currentKeys.isEmpty()) return Pair(' ', emptyList())

        val targetIndex = TONE_SYMBOLS.indexOf(toneChar)
        if (targetIndex <= 0) return Pair(' ', cachedCandidates)

        currentToneIndex = if (currentToneIndex == targetIndex) 0 else targetIndex

        if (currentKeys.isNotEmpty() && currentKeys.last() == 11) {
            currentKeys.removeAt(currentKeys.size - 1)
        }
        if (currentToneIndex > 0) {
            currentKeys.add(11)
        }

        lockedZhuyinCombo = null
        recalculate()
        return Pair(TONE_SYMBOLS[currentToneIndex], cachedCandidates)
    }

    // SwiftKey 階梯式逐字音節確認狀態 (已確認音節序列與各音節消耗之按鍵數)
    private val confirmedSyllables = mutableListOf<String>()
    private val confirmedKeyCounts = mutableListOf<Int>()

    fun getConfirmedSyllables(): List<String> = confirmedSyllables.toList()
    fun hasConfirmedSyllables(): Boolean = confirmedSyllables.isNotEmpty()

    /** 取得尚未被階梯音節鎖定消耗的剩餘按鍵序列 */
    fun getRemainingKeys(): List<Int> {
        val consumed = confirmedKeyCounts.sum()
        return if (consumed < currentKeys.size) {
            currentKeys.subList(consumed, currentKeys.size).toList()
        } else {
            emptyList()
        }
    }

    /**
     * SwiftKey 階梯式確認當前音節：
     * 1. 計算該音節消耗按鍵數 (含貪婪吸收緊隨其後的聲調鍵 11)
     * 2. 記錄進 confirmedSyllables，自動推進至下一字
     */
    fun confirmStepSyllable(syllable: String): List<DictEntry> {
        val cleanSyl = syllable.filter { it !in "ˇˋˊ˙" }
        val baseKeyLen = KeyMapping.getSequence(cleanSyl, ignoreTones = true).size
        val remaining = getRemainingKeys()
        var consumedKeys = baseKeyLen

        // 貪婪吸收聲調鍵：若 remainingKeys 在音節按鍵之後緊跟著聲調鍵 11，一併被此音節吸收
        if (remaining.size > baseKeyLen && remaining[baseKeyLen] == 11) {
            consumedKeys += 1
        } else if (syllable.any { it in "ˇˋˊ˙" } && remaining.contains(11)) {
            // 若選擇的音節帶有聲調標記，且剩餘鍵中有 11，也一併吸收
            val toneIdx = remaining.indexOf(11)
            if (toneIdx in 0..baseKeyLen) {
                consumedKeys = maxOf(consumedKeys, toneIdx + 1)
            }
        }

        confirmedSyllables.add(syllable)
        confirmedKeyCounts.add(minOf(consumedKeys, remaining.size))
        lockedZhuyinCombo = null
        recalculate()
        return cachedCandidates
    }

    /**
     * 平滑階梯式退格回滾 (Step Back)：
     * 當沒有未確認按鍵時，按退格退回最後一個已確認的音節，讓該音節重新恢復為待決策狀態
     */
    fun rollbackLastConfirmedSyllable(): Boolean {
        if (confirmedSyllables.isNotEmpty()) {
            confirmedSyllables.removeAt(confirmedSyllables.size - 1)
            if (confirmedKeyCounts.isNotEmpty()) {
                confirmedKeyCounts.removeAt(confirmedKeyCounts.size - 1)
            }
            lockedZhuyinCombo = null
            recalculate()
            return true
        }
        return false
    }

    fun backspace(): List<DictEntry> {
        if (currentKeys.isEmpty()) {
            clear()
            return emptyList()
        }

        // 1. 忠實刪除使用者實際按下的最後一顆實體按鍵
        val removedKey = currentKeys.removeAt(currentKeys.size - 1)
        if (removedKey == 11) {
            currentToneIndex = 0
        }
        lockedZhuyinCombo = null

        // 2. 若累計已確認音節所消耗的鍵數大於剩餘總鍵數，表示該音節的按鍵已被削減，回滾解除該音節
        while (confirmedKeyCounts.isNotEmpty() && confirmedKeyCounts.sum() > currentKeys.size) {
            confirmedKeyCounts.removeAt(confirmedKeyCounts.size - 1)
            if (confirmedSyllables.isNotEmpty()) {
                confirmedSyllables.removeAt(confirmedSyllables.size - 1)
            }
        }

        // 3. 若所有按鍵皆已退格清空
        if (currentKeys.isEmpty()) {
            clear()
            return emptyList()
        }

        previousTopWord = null
        recalculate()
        return cachedCandidates
    }

    fun clear() {
        currentKeys.clear()
        currentToneIndex = 0
        lockedZhuyinCombo = null
        confirmedSyllables.clear()
        confirmedKeyCounts.clear()
        cachedCandidates = emptyList()
        cachedZhuyinCombos = emptyList()
        previousTopWord = null
        lastRecalcKeyCount = 0
    }

    fun hasComposing(): Boolean = currentKeys.isNotEmpty()

    fun getCurrentKeys(): List<Int> = currentKeys.toList()

    /** 目前輸入中的注音鍵數（不含聲調鍵） */
    fun cleanKeyCount(): Int = currentKeys.count { it != 11 }

    /**
     * 部分上屏：使用者確認了前 [count] 個注音鍵所對應的詞之後，
     * 從輸入序列移除這些按鍵（連同緊接其後、屬於該詞的聲調鍵），其餘按鍵保留繼續組字。
     */
    fun consumePrefixKeys(count: Int): List<DictEntry> {
        var consumed = 0
        var idx = 0
        while (idx < currentKeys.size && consumed < count) {
            if (currentKeys[idx] != 11) consumed++
            idx++
        }
        if (idx < currentKeys.size && currentKeys[idx] == 11) idx++
        val rest = currentKeys.subList(idx, currentKeys.size).toList()
        currentKeys.clear()
        currentKeys.addAll(rest)
        currentToneIndex = 0
        lockedZhuyinCombo = null
        confirmedSyllables.clear()
        confirmedKeyCounts.clear()
        previousTopWord = null
        lastRecalcKeyCount = 0
        recalculate()
        return cachedCandidates
    }

    private var previousTopWord: String? = null
    private var lastRecalcKeyCount: Int = 0

    /**
     * 現代音節與詞頻評分模型：
     * 1. 嚴格區分「完全匹配 (isExact)」與「未完成前綴預測 (!isExact)」：
     *    - 完全匹配（如單字「說」ㄕㄨㄛ 已完整打完 3 鍵）：絕不施加長度懲罰，保證精確單字與完整詞優先出字！
     *    - 前綴預測（如「所以」ㄙㄨㄛˇㄧˇ 尚缺第 4 鍵）：採每缺一鍵 (0.65^distance) 指數折扣，嚴禁半成品詞搶佔第一順位！
     * 2. 詞長權重：
     *    - 完全匹配：雙字詞 2.5x、三字詞 2.2x、單字 1.5x
     *    - 前綴預測：雙字詞 1.2x、三字詞 1.0x、單字 0.8x
     * 3. 音節延續保護 (Syllable Continuity)：若候選詞繼承了前一擊的首字音節，給予 1.3x 延續加成。
     */
    private fun getEffectiveWeight(entry: DictEntry, inputKeyCount: Int, isExact: Boolean, prevTopWord: String? = null): Double {
        val userBoost = userDict.getBoost(entry.word)
        val bigramNat = getBigramBonus(currentContextWord, entry.word) * bigramScaleT9Weight
        // 舊公式 1 + min(b, 2500)/800，b = 80(e^n - 1)；上限 n = 3.4 對應舊的 b ≈ 2400
        val bigramBonus = if (bigramNat > 0) 1.0 + (Math.exp(minOf(bigramNat, 3.4)) - 1.0) / 10.0 else 1.0

        // 完全匹配且使用者曾選過：給予高優先級，但乘上 Bigram 語境加成讓強上下文可競爭
        if (isExact && userBoost > 0) {
            return (1_000_000_000.0 + userBoost) * bigramBonus
        }

        var rawWeight = minOf(entry.weight, T9_DP_WEIGHT_CAP).toDouble()  // 照抄 t9SegmentCost:892
        if (userBoost > 0) {
            val boostCap = if (entry.word.length == 1) 50_000 else 200_000  // 照抄 t9SegmentCost:893，加法
            rawWeight += minOf(userBoost, boostCap)
        }
        if (!isExact && inputKeyCount <= 2 && entry.word.length >= 2) {
            rawWeight *= if (entry.word.length == 2) 0.5 else 0.3  // 少按鍵時多字詞懲罰，單字不受影響
        }

        val lengthMultiplier = when {
            isExact -> {
                when (entry.word.length) {
                    2 -> 2.5
                    3 -> 2.2
                    4 -> 2.0
                    1 -> 1.5
                    else -> 1.8
                }
            }
            else -> {
                when (entry.word.length) {
                    2 -> 1.2
                    3 -> 1.0
                    4 -> 0.9
                    1 -> 0.8
                    else -> 0.8
                }
            }
        }

        val targetKeyLen = KeyMapping.getSequence(entry.zhuyin, ignoreTones = true).size
        val distance = maxOf(0, targetKeyLen - inputKeyCount)
        val distanceMultiplier = if (isExact) 2.5 else Math.pow(0.65, distance.toDouble())
        val continuityMultiplier = if (prevTopWord != null && prevTopWord.isNotEmpty() && entry.word.startsWith(prevTopWord)) 1.3 else 1.0
        val tolerantMultiplier = if (entry.isTolerant) 0.35 else 1.0

        return rawWeight * lengthMultiplier * distanceMultiplier * continuityMultiplier * tolerantMultiplier * bigramBonus
    }

    // ───────── 核心查詢邏輯 ─────────

    private fun recalculate() {
        val keyCountGrew = currentKeys.size > lastRecalcKeyCount
        if (currentKeys.isEmpty()) {
            cachedCandidates = emptyList()
            cachedZhuyinCombos = emptyList()
            previousTopWord = null
            lastRecalcKeyCount = 0
            return
        }

        val isTolerantEnabled = context?.let { com.bopomofo.t9ime.data.PreferencesRepository.isTolerantInputEnabled(it) } ?: false
        val exactResults = trie.searchExact(currentKeys, includeTolerant = isTolerantEnabled)
        // 前綴預測：鍵數少時子樹很大（1 鍵約 7 千個節點），深度隨鍵數遞增；鍵數足夠後子樹小，可看更深
        val prefixDepth = when (currentKeys.count { it != 11 }) {
            1 -> 1
            2 -> 2
            3 -> 3
            else -> 4
        }
        val prefixResults = trie.searchPrefixBest(
            currentKeys,
            maxDepth = prefixDepth,
            includeTolerant = isTolerantEnabled,
            limit = 60
        )

        // 聲調過濾
        val toneChar = if (currentToneIndex > 0) TONE_SYMBOLS[currentToneIndex] else null
        val filterTone = { list: List<DictEntry> ->
            if (toneChar != null) {
                val filtered = list.filter { it.zhuyin.contains(toneChar) }
                if (filtered.isNotEmpty()) filtered else list
            } else list
        }

        val cleanKeys = currentKeys.filter { it != 11 }
        val phonemeKeyLen = cleanKeys.size

        val filteredExact = filterTone(exactResults)
        val filteredPrefix = filterTone(prefixResults)

        // 統一度量衡評分池：將完全匹配與前綴預測統一納入評分，徹底杜絕冷門生僻怪詞霸佔首選！
        val pool = mutableMapOf<String, Pair<DictEntry, Double>>()

        for (e in filteredExact) {
            val score = getEffectiveWeight(e, phonemeKeyLen, isExact = true, prevTopWord = previousTopWord)
            pool[e.word] = Pair(e, score)
        }

        for (p in filteredPrefix) {
            val score = getEffectiveWeight(p, phonemeKeyLen, isExact = false, prevTopWord = previousTopWord)
            val existing = pool[p.word]
            if (existing == null || score > existing.second) {
                pool[p.word] = Pair(p, score)
            }
        }

        val rankedList = pool.values
            .sortedWith(
                compareByDescending<Pair<DictEntry, Double>> { it.second >= 1_000_000_000.0 }
                    .thenByDescending { !it.first.isTolerant }
                    .thenByDescending { it.second }
            )
            .map { it.first }

        var candidateList = ArrayList(rankedList)

        // 長句斷詞（4 鍵以上）：整句 n-best 依成本與一般候選合併；另提供「部分上屏」候選（先選第一個詞，其餘按鍵繼續組字）
        if (phonemeKeyLen in 4..T9_MAX_DP_KEYS) {
            val exactWords = HashSet<String>(filteredExact.size)
            for (e in filteredExact) exactWords.add(e.word)
            candidateList = t9MergeSentenceCandidates(
                candidateList, exactWords, cleanKeys, isTolerantEnabled
            )
        }

        previousTopWord = if (keyCountGrew) candidateList.firstOrNull()?.word else null
        lastRecalcKeyCount = currentKeys.size

        // 產生左側注音音節列表 (SwiftKey 階梯式推進模型：永遠只針對當前第一個待決策字顯示大字合法單音節)
        val comboSet = LinkedHashSet<String>()
        val remaining = getRemainingKeys()
        val cleanRemaining = remaining.filter { it != 11 }

        if (cleanRemaining.isNotEmpty()) {
            val remLen = cleanRemaining.size
            // 優先列出長度為 3、2、1 鍵的所有合法單音節（按音節頻率排序）
            for (takeLen in minOf(remLen, 3) downTo 1) {
                val subKeys = cleanRemaining.subList(0, takeLen)
                val exactSyllables = SyllableManager.getExactSyllables(subKeys)
                for (syl in exactSyllables) {
                    val label = if (toneChar != null && takeLen == remLen) "$syl$toneChar" else syl
                    comboSet.add(label)
                    if (comboSet.size >= 14) break
                }
                if (comboSet.size >= 14) break
            }

            // 保底 1：從當前候選詞列表中提取當前字的首字單音節
            if (comboSet.size < 8) {
                for (entry in candidateList) {
                    val firstSyl = entry.cleanZhuyin.split(" ").firstOrNull() ?: entry.cleanZhuyin
                    if (firstSyl.isNotEmpty() && firstSyl.length <= 4) {
                        val seq = KeyMapping.getSequence(firstSyl, ignoreTones = true)
                        if (seq.size <= remLen && cleanRemaining.subList(0, seq.size) == seq) {
                            comboSet.add(firstSyl)
                            if (comboSet.size >= 14) break
                        }
                    }
                }
            }

            // 保底 2：若依然為空，以第一鍵的個別注音字符置入
            if (comboSet.isEmpty()) {
                val firstKey = cleanRemaining.first()
                for (ch in KeyMapping.getChars(firstKey)) {
                    comboSet.add(ch.toString())
                }
            }
        } else if (confirmedSyllables.isNotEmpty()) {
            // 所有輸入按鍵均已被鎖定確認，左側顯示已確認的音節序列
            comboSet.add(confirmedSyllables.joinToString(" "))
        }

        cachedZhuyinCombos = comboSet.toList()
        applyLockFilter(candidateList)
    }

    // ───────── 九鍵專用：整句斷詞 n-best 與「部分上屏」候選 ─────────
    //
    // 九鍵一個鍵對應多個注音，連打時又沒有音節邊界，同一串按鍵有很多種合理切法，
    // 純詞頻模型無法保證第一名一定正確。所以除了整句最佳解，還提供：
    //   - 整句 n-best（第 2 名等替代切法）
    //   - 部分上屏候選：先確認第一個詞，其餘按鍵繼續組字，由使用者逐詞修正
    // 此區塊只供九鍵 (recalculate) 使用；全鍵盤 / 實體鍵盤的 searchFullZhuyin 仍使用下方原本的 findBestSentence。

    private class T9Path(val cost: Double, val prev: T9Path?, val entry: DictEntry?, val keyLen: Int)

    /** 單一詞條的成本：-ln(詞頻 / 總詞頻) + 每詞固定成本 - Bigram 語境轉移折扣。越低越好。 */
    private fun t9SegmentCost(entry: DictEntry, prevWord: String? = null): Double {
        val boost = userDict.getBoost(entry.word)
        val base = minOf(entry.weight, T9_DP_WEIGHT_CAP)
        val weight = if (entry.word.length == 1) base + minOf(boost, 50_000) else base + minOf(boost, 200_000)
        val logTotal = if (logTotalWeight > 0) logTotalWeight else 17.91
        val tolerantPenalty = if (entry.isTolerant) T9_TOLERANT_PENALTY else 0.0

        val bigramDiscount = minOf(2.8, getBigramBonus(prevWord, entry.word) * bigramScaleT9Cost)

        return (logTotal - Math.log(maxOf(weight, 1).toDouble()) + T9_WORD_PENALTY + tolerantPenalty - bigramDiscount).coerceAtLeast(0.5)
    }

    private fun t9PathSegments(path: T9Path): List<Pair<DictEntry, Int>> {
        val segments = ArrayList<Pair<DictEntry, Int>>()
        var p: T9Path? = path
        while (p != null) {
            val e = p.entry ?: break
            segments.add(Pair(e, p.keyLen))
            p = p.prev
        }
        segments.reverse()
        return segments
    }

    private fun t9PathText(path: T9Path): String {
        val words = ArrayList<String>()
        var p: T9Path? = path
        while (p != null) {
            val e = p.entry ?: break
            words.add(e.word)
            p = p.prev
        }
        val sb = StringBuilder()
        for (i in words.indices.reversed()) sb.append(words[i])
        return sb.toString()
    }

    /**
     * 前向 DP (Bigram Viterbi)：hyp[i] = 涵蓋 keys[0, i) 的前 T9_BEAM 條最低成本路徑
     * 當 i == n 時，容許最後一個詞為尚未打完的前綴延伸詞（避免打字中途句尾跳出怪異單鍵韻母字）
     */
    private fun t9ForwardPaths(keys: List<Int>, includeTolerant: Boolean): Array<List<T9Path>> {
        val n = keys.size
        val hyp = Array<List<T9Path>>(n + 1) { emptyList() }
        hyp[0] = listOf(T9Path(0.0, null, null, 0))
        for (i in 1..n) {
            val cands = ArrayList<T9Path>()
            for (len in 1..minOf(12, i)) {
                val prevPaths = hyp[i - len]
                if (prevPaths.isEmpty()) continue
                val subKeys = keys.subList(i - len, i)
                val node = trie.searchNode(subKeys) ?: continue

                for (entry in node.topEntries()) {
                    if (entry.isTolerant && !includeTolerant) continue
                    for (p in prevPaths) {
                        val prevWord = if (i - len == 0) currentContextWord else p.entry?.word
                        val segCost = t9SegmentCost(entry, prevWord)
                        cands.add(T9Path(p.cost + segCost, p, entry, len))
                    }
                }

                // 若為輸入序列最尾端 (i == n) 且為多詞組合 (i - len > 0)，容許尾端尚未打完的詞參與組句
                if (i == n && i - len > 0 && len in 1..3) {
                    val extensions = trie.collectExtensions(node, T9_TAIL_DEPTH, false, null, T9_TAIL_PER_NODE)
                    for (ext in extensions.values) {
                        if (ext.entry.isTolerant && !includeTolerant) continue
                        for (p in prevPaths) {
                            val prevWord = p.entry?.word
                            val segCost = t9SegmentCost(ext.entry, prevWord) + T9_TAIL_MISS * ext.extraKeys
                            cands.add(T9Path(p.cost + segCost, p, ext.entry, len))
                        }
                    }
                }
            }
            if (cands.isEmpty()) continue
            cands.sortBy { it.cost }
            val kept = ArrayList<T9Path>(T9_BEAM)
            val seenText = HashSet<String>()
            for (p in cands) {
                if (seenText.add(t9PathText(p))) {
                    kept.add(p)
                    if (kept.size >= T9_BEAM) break
                }
            }
            hyp[i] = kept
        }
        return hyp
    }

    /**
     * 後向 DP：suffix[k] = 涵蓋 keys[k, n) 的最低成本（最後一個詞允許尚未打完）。
     * 無法涵蓋時為 +∞。
     */
    private fun t9SuffixCosts(keys: List<Int>, includeTolerant: Boolean): DoubleArray {
        val n = keys.size
        val suffix = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        suffix[n] = 0.0
        for (k in n - 1 downTo 0) {
            var best = Double.POSITIVE_INFINITY
            for (len in 1..minOf(12, n - k)) {
                val rest = suffix[k + len]
                if (rest == Double.POSITIVE_INFINITY) continue
                val node = trie.searchNode(keys.subList(k, k + len)) ?: continue
                for (entry in node.topEntries()) {
                    if (entry.isTolerant && !includeTolerant) continue
                    val c = t9SegmentCost(entry) + rest
                    if (c < best) best = c
                }
            }
            // 尾端未打完的詞：避免被迫把尚未打完的按鍵硬湊成單獨的韻母字（如 喔、額、愛）
            val tailNode = trie.searchNode(keys.subList(k, n))
            if (tailNode != null) {
                for (ext in trie.collectExtensions(tailNode, T9_TAIL_DEPTH, false, null, T9_TAIL_PER_NODE).values) {
                    val c = t9SegmentCost(ext.entry) + T9_TAIL_MISS * ext.extraKeys
                    if (c < best) best = c
                }
            }
            suffix[k] = best
        }
        return suffix
    }

    /**
     * 「部分上屏」候選：枚舉每個切點 k 之前可以成詞的詞，以「詞本身成本 + 剩餘按鍵的最佳成本」排序。
     * 選取後只上屏該詞，其餘按鍵繼續組字（consumedKeys = k）。
     */
    private fun t9PartialCandidates(keys: List<Int>, includeTolerant: Boolean): List<DictEntry> {
        val n = keys.size
        val suffix = t9SuffixCosts(keys, includeTolerant)
        val best = HashMap<String, Triple<Double, DictEntry, Int>>()
        for (k in 1 until n) {
            val rest = suffix[k]
            if (rest == Double.POSITIVE_INFINITY) continue
            val node = trie.searchNode(keys.subList(0, k)) ?: continue
            for (entry in node.topEntries()) {
                if (entry.isTolerant && !includeTolerant) continue
                val cost = t9SegmentCost(entry, currentContextWord) + rest
                val old = best[entry.word]
                if (old == null || cost < old.first) {
                    best[entry.word] = Triple(cost, entry, k)
                }
            }
        }
        return best.values
            .sortedBy { it.first }
            .take(T9_PARTIAL_LIMIT)
            .map { (_, entry, k) -> entry.copy(consumedKeys = k) }
    }

    /**
     * 把整句 n-best 與部分上屏候選併入一般候選列：
     * - 整句依成本插入（成本比一般候選低才會排前面，不再無條件灌分搶第一）
     * - 部分上屏候選插在整詞候選之後，第一順位永遠是涵蓋全部按鍵的候選
     */
    private fun t9MergeSentenceCandidates(
        base: ArrayList<DictEntry>,
        exactWords: Set<String>,
        keys: List<Int>,
        includeTolerant: Boolean
    ): ArrayList<DictEntry> {
        val keyLen = keys.size
        val out = ArrayList<DictEntry>(base)
        val costs = ArrayList<Double>(base.size)
        for (e in base) {
            val miss = if (e.word in exactWords) 0
            else maxOf(0, KeyMapping.getSequence(e.zhuyin, ignoreTones = true).size - keyLen)
            costs.add(t9SegmentCost(e, currentContextWord) + T9_TAIL_MISS * miss)
        }
        val present = HashSet<String>()
        for (e in out) present.add(e.word)

        // 1. 涵蓋全部按鍵的 n-best 路徑，取前 2 名。
        //    單一詞的路徑只會在「精確比對沒找到、但忽略聲調鍵後找得到」時才會出現（例如只在最後一個音節打了聲調），
        //    聲調本來就是軟性偏好（找不到符合聲調的詞時退回全部），這種情況也應該顯示。
        val sentences = ArrayList<Pair<DictEntry, Double>>()
        for (path in t9ForwardPaths(keys, includeTolerant)[keyLen]) {
            val segments = t9PathSegments(path)
            if (segments.isEmpty()) continue
            val word = segments.joinToString("") { it.first.word }
            if (word in present) continue
            val zhuyin = segments.joinToString(" ") { it.first.zhuyin }
            val avgLog = segments.map { Math.log(maxOf(it.first.weight, 1).toDouble()) }.average()
            val weight = minOf(Math.exp(avgLog).toInt(), 100_000_000)
            sentences.add(Pair(DictEntry(word, zhuyin, weight), path.cost))
            if (sentences.size >= 2) break
        }
        // 由後往前插入，迴圈結束時 bestSentencePos 即為最佳整句所在位置
        var bestSentencePos = -1
        for ((entry, cost) in sentences.asReversed()) {
            var pos = 0
            while (pos < costs.size && costs[pos] <= cost) pos++
            out.add(pos, entry)
            costs.add(pos, cost)
            present.add(entry.word)
            bestSentencePos = pos
        }

        // 2. 部分上屏候選：整句候選通常很長、會占滿候選列寬度，部分上屏要緊接在最佳整句之後才看得到
        //    （第 2 名整句多半只是同音字變體，如 再/在、那/哪，價值較低，排在部分上屏之後）。
        //    沒有整句時（輸入的是單一詞）維持插在第 T9_PARTIAL_INSERT_AT 位之後。
        var insertAt = if (bestSentencePos >= 0) minOf(bestSentencePos + 1, out.size)
        else minOf(T9_PARTIAL_INSERT_AT, out.size)
        for (entry in t9PartialCandidates(keys, includeTolerant)) {
            if (entry.word in present) continue
            out.add(insertAt, entry)
            present.add(entry.word)
            insertAt++
        }
        return out
    }


    var currentContextWord: String? = null

    private fun applyLockFilter(baseList: List<DictEntry>) {
        val activeSyllables = if (confirmedSyllables.isNotEmpty()) {
            confirmedSyllables
        } else if (lockedZhuyinCombo != null) {
            listOf(lockedZhuyinCombo!!)
        } else {
            emptyList()
        }

        if (activeSyllables.isNotEmpty()) {
            val cleanPrefix = activeSyllables.joinToString("") { it.filter { ch -> ch !in "ˇˋˊ˙" } }
            val firstSylClean = activeSyllables[0].filter { it !in "ˇˋˊ˙" }

            val filtered = baseList.filter { entry ->
                entry.cleanZhuyin.startsWith(cleanPrefix)
            }

            val resultList = mutableListOf<DictEntry>()
            resultList.addAll(filtered)

            // 雙軌合成保底：從第一個已確認音節提取單字，並與後續音節/按鍵進行笛卡爾積合成
            val firstSylWords = fullZhuyinWordMap[firstSylClean]
            val firstSylKeyCount = confirmedKeyCounts.getOrNull(0) ?: KeyMapping.getSequence(firstSylClean, ignoreTones = true).size

            // 1. 若鎖定了兩個音節 (如 ㄅㄤ + ㄨㄛ)，合成雙字詞 (如 幫我)
            if (activeSyllables.size >= 2) {
                val secondSylClean = activeSyllables[1].filter { it !in "ˇˋˊ˙" }
                val secondSylWords = fullZhuyinWordMap[secondSylClean]
                if (!firstSylWords.isNullOrEmpty() && !secondSylWords.isNullOrEmpty()) {
                    val top1 = firstSylWords.take(6)
                    val top2 = secondSylWords.take(6)
                    for (w1 in top1) {
                        for (w2 in top2) {
                            val combined = w1.word + w2.word
                            if (resultList.none { it.word == combined }) {
                                val avgWt = (w1.weight + w2.weight) / 2
                                resultList.add(DictEntry(combined, "${w1.zhuyin} ${w2.zhuyin}", avgWt))
                            }
                        }
                    }
                }
            } else if (resultList.size < 4 && !firstSylWords.isNullOrEmpty()) {
                // 若只有一個鎖定音節且整詞候選少於 4 個，嘗試與剩餘按鍵的首字單音節合成
                val remaining = getRemainingKeys().filter { it != 11 }
                if (remaining.isNotEmpty()) {
                    for (kLen in minOf(remaining.size, 3) downTo 1) {
                        val subKeys = remaining.subList(0, kLen)
                        val nextSyls = SyllableManager.getExactSyllables(subKeys)
                        for (nSyl in nextSyls.take(3)) {
                            val nextWords = fullZhuyinWordMap[nSyl] ?: continue
                            for (w1 in firstSylWords.take(4)) {
                                for (w2 in nextWords.take(4)) {
                                    val combined = w1.word + w2.word
                                    if (resultList.none { it.word == combined }) {
                                        val avgWt = (w1.weight + w2.weight) / 2
                                        resultList.add(DictEntry(combined, "${w1.zhuyin} ${w2.zhuyin}", avgWt))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 將第一字的單字候選 (如 幫、榜、綁、胖) 納入候選列，標註其 consumedKeys，讓使用者可單字上屏
            if (!firstSylWords.isNullOrEmpty()) {
                for (singleEntry in firstSylWords.sortedByDescending { it.weight + userDict.getBoost(it.word, it.zhuyin) }) {
                    if (resultList.none { it.word == singleEntry.word }) {
                        resultList.add(singleEntry.copy(consumedKeys = firstSylKeyCount))
                    }
                }
            }

            if (resultList.isNotEmpty()) {
                cachedCandidates = resultList.sortedByDescending { it.weight + userDict.getBoost(it.word, it.zhuyin) }
                return
            }

            // 3. 終極保底：直接提取第一音節的所有單字，絕不允許空白
            if (!firstSylWords.isNullOrEmpty()) {
                cachedCandidates = firstSylWords.sortedByDescending { it.weight + userDict.getBoost(it.word, it.zhuyin) }
                return
            }
        }
        cachedCandidates = baseList
    }

    // ───────── 對外查詢 API ─────────

    fun getPossibleZhuyinCombinations(): List<String> = cachedZhuyinCombos

    /**
     * 取得當前組字序列對應之完整多字注音字串：
     * 嚴格遵守「使用者未輸入聲調絕不擅自填上聲調」原則：
     * 1. 已確認音節 (confirmedSyllables)：忠實呈現使用者點選時的音節形態（有聲調即有，無聲調即無）。
     * 2. 剩餘未確認音節：若使用者主動按了聲調鍵 (currentToneIndex > 0)，僅最後一個音節套用該聲調；
     *    若使用者完全未按聲調鍵，所有音節一律去除聲調符號 (ˇˋˊ˙)，絕不隨便腦補字典預設聲調！
     */
    fun getFullComposingZhuyin(): String {
        if (currentKeys.isEmpty()) return ""

        val top = cachedCandidates.firstOrNull()
        val rawZhuyin = top?.zhuyin?.trim() ?: ""

        val toneChar = if (currentToneIndex > 0 && currentToneIndex < TONE_SYMBOLS.size) {
            TONE_SYMBOLS[currentToneIndex].takeIf { it != ' ' }
        } else null

        // 輔助函式：去除音節中的所有聲調符號
        fun cleanTone(syl: String): String = syl.filter { it !in "ˇˋˊ˙" }

        if (confirmedSyllables.isEmpty()) {
            if (rawZhuyin.isNotEmpty()) {
                val syllables = rawZhuyin.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                if (syllables.isNotEmpty()) {
                    val result = syllables.mapIndexed { index, syl ->
                        val base = cleanTone(syl)
                        if (index == syllables.size - 1 && toneChar != null) {
                            "$base$toneChar"
                        } else {
                            base
                        }
                    }
                    return result.joinToString(" ")
                }
            }
            val combo = cachedZhuyinCombos.firstOrNull() ?: ""
            return if (toneChar != null && !combo.any { it in "ˇˋˊ˙" }) "$combo$toneChar" else combo
        }

        val confirmed = confirmedSyllables.joinToString(" ")
        val confirmedClean = confirmedSyllables.map { cleanTone(it) }.joinToString(" ")

        if (rawZhuyin.isNotEmpty()) {
            val syllables = rawZhuyin.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            val confirmedCount = confirmedSyllables.size
            if (syllables.size > confirmedCount) {
                // 拼接：前段保持 confirmedSyllables 原樣，後段為剩餘音節
                val remainingSyllables = syllables.subList(confirmedCount, syllables.size)
                val formattedRem = remainingSyllables.mapIndexed { index, syl ->
                    val base = cleanTone(syl)
                    if (index == remainingSyllables.size - 1 && toneChar != null) {
                        "$base$toneChar"
                    } else {
                        base
                    }
                }
                return "$confirmed ${formattedRem.joinToString(" ")}"
            } else if (rawZhuyin.startsWith(confirmedClean) || rawZhuyin.startsWith(confirmed)) {
                // 候選詞長度剛好等於已確認音節數
                return confirmed
            }
        }

        val remCombo = cachedZhuyinCombos.firstOrNull() ?: ""
        val formattedRem = if (remCombo.isNotEmpty()) {
            val base = cleanTone(remCombo)
            if (toneChar != null) "$base$toneChar" else base
        } else ""

        return if (formattedRem.isNotEmpty()) "$confirmed $formattedRem" else confirmed
    }

    /**
     * 九鍵專用：只根據「使用者真實按下的鍵」產生輸入狀態預覽（上方 composing 文字）。
     *
     * 與 [getTopComposingWord]／[getFullComposingZhuyin] 的差異：後兩者取候選詞第一名，
     * 其中包含「前綴預測」（只按 1 鍵卻回傳 3、4 音節的詞），會讓預測污染輸入狀態。
     * 本函式完全不讀 cachedCandidates，輸出的注音符號數量永遠等於實際按鍵數（聲調鍵除外）：
     * - 已由使用者確認的音節：原樣呈現
     * - 其餘按鍵：由左而右貪婪取「剛好吃掉 N 鍵」的合法音節（N>=2）；單鍵或無合法音節時顯示該鍵的首個注音符號
     * - 尾端聲調鍵：顯示使用者目前選的聲調；中間夾雜的聲調鍵略過
     */
    fun getKeyOnlyPreview(): String {
        if (currentKeys.isEmpty()) return ""
        val sb = StringBuilder()
        for (syl in confirmedSyllables) sb.append(syl)

        val remaining = getRemainingKeys()
        val toneChar = if (currentToneIndex in 1 until TONE_SYMBOLS.size) TONE_SYMBOLS[currentToneIndex] else null
        var i = 0
        while (i < remaining.size) {
            val key = remaining[i]
            if (key == 11) {
                if (i == remaining.size - 1 && toneChar != null) sb.append(toneChar)
                i++
                continue
            }
            var runLen = 0
            while (i + runLen < remaining.size && remaining[i + runLen] != 11 && runLen < 4) runLen++

            var consumed = 1
            var piece: String? = null
            for (take in runLen downTo 2) {
                val syl = SyllableManager.getExactSyllables(remaining.subList(i, i + take)).firstOrNull()
                if (syl != null && syl.length == take) {
                    piece = syl
                    consumed = take
                    break
                }
            }
            sb.append(piece ?: (KeyMapping.getChars(key).firstOrNull()?.toString() ?: ""))
            i += consumed
        }
        return sb.toString()
    }

    /**
     * 九鍵專用：輸入狀態快照，供「按下即輸入、滑動時精準還原」使用。
     * 只保存參照與小型複本；還原時不重新計算候選，因此是 O(1) 且不會殘留任何預測狀態。
     */
    class InputSnapshot internal constructor(
        internal val keys: List<Int>,
        internal val toneIndex: Int,
        internal val locked: String?,
        internal val confirmed: List<String>,
        internal val confirmedCounts: List<Int>,
        internal val prevTop: String?,
        internal val lastRecalcKeyCount: Int,
        internal val candidates: List<DictEntry>,
        internal val combos: List<String>
    )

    fun captureInputState(): InputSnapshot = InputSnapshot(
        keys = currentKeys.toList(),
        toneIndex = currentToneIndex,
        locked = lockedZhuyinCombo,
        confirmed = confirmedSyllables.toList(),
        confirmedCounts = confirmedKeyCounts.toList(),
        prevTop = previousTopWord,
        lastRecalcKeyCount = lastRecalcKeyCount,
        candidates = cachedCandidates,
        combos = cachedZhuyinCombos
    )

    /** 目前輸入狀態是否與快照完全一致（用來確認在快照之後沒有其他手指又輸入了別的鍵） */
    fun isSameInputState(s: InputSnapshot): Boolean =
        currentKeys == s.keys && currentToneIndex == s.toneIndex && confirmedKeyCounts == s.confirmedCounts

    fun restoreInputState(s: InputSnapshot): List<DictEntry> {
        currentKeys.clear()
        currentKeys.addAll(s.keys)
        currentToneIndex = s.toneIndex
        lockedZhuyinCombo = s.locked
        confirmedSyllables.clear()
        confirmedSyllables.addAll(s.confirmed)
        confirmedKeyCounts.clear()
        confirmedKeyCounts.addAll(s.confirmedCounts)
        previousTopWord = s.prevTop
        lastRecalcKeyCount = s.lastRecalcKeyCount
        cachedCandidates = s.candidates
        cachedZhuyinCombos = s.combos
        return cachedCandidates
    }

    fun getCandidates(): List<DictEntry> = cachedCandidates

    val currentCandidates: List<DictEntry>
        get() = cachedCandidates

    fun getZhuyinForChar(ch: Char): String {
        return charZhuyinMap[ch]?.firstOrNull() ?: ""
    }

    /**
     * 動態學習新詞彙或自訂同音字組合：
     * 1. 寫入使用者記憶體字典與磁碟檔案
     * 2. 即時以最高權重 (50,000,000) 注入 Trie 字典，確保該按鍵序列下一次輸入時 100% 穩居首位
     */
    fun learnWord(word: String, zhuyin: String = "") {
        if (word.isBlank() || word.startsWith("【")) return
        val zy = if (zhuyin.isNotEmpty()) zhuyin else {
            word.map { getZhuyinForChar(it) }.filter { it.isNotEmpty() }.joinToString(" ")
        }
        userDict.recordUsage(word, zy)
        if (zy.isNotEmpty()) {
            val usageCount = userDict.getUsageCount(word)
            val weight = 50_000_000 + minOf(usageCount * 1_000_000, 50_000_000)
            trie.insert(DictEntry(word, zy, weight))
            if (word.length == 1) {
                val list = charZhuyinMap.getOrPut(word[0]) { ArrayList(2) }
                if (!list.contains(zy)) {
                    list.add(0, zy)
                }
            }
            val cleanZy = zy.filter { it !in "ˇˋˊ˙" }
            val syllables = if (zy.contains(" ")) zy.split(" ") else SyllableManager.splitIntoSyllables(cleanZy, word.length)
            if (syllables != null && syllables.size == word.length) {
                val initials = syllables.mapNotNull { it.firstOrNull() }.joinToString("")
                if (initials.isNotEmpty()) {
                    val list = initialMap.getOrPut(initials) { ArrayList(4) }
                    list.removeAll { it.word == word }
                    list.add(0, DictEntry(word, zy, weight))
                }
            }
        }
    }

    private fun toneOf(s: String): Char? = s.find { it in "ˇˋˊ˙" }

    /**
     * 依使用者實際輸入的音節，找出該詞在詞庫中的「真實讀音」（多音字不再取第一讀音）。
     * 找不到（例如音節只是聲母簡拼、詞庫沒有此詞）回傳 null，呼叫端應略過學習，避免污染個人詞庫。
     */
    fun resolveReading(word: String, syllables: List<String>): String? {
        if (word.isEmpty() || syllables.size != word.length) return null
        val clean = syllables.joinToString("") { it.filter { c -> c !in "ˇˋˊ˙ " } }
        val userTones = syllables.map { toneOf(it) }
        fun toneMisses(zhuyin: String): Int {
            val parts = if (zhuyin.contains(' ')) zhuyin.split(' ').filter { it.isNotEmpty() }
                        else if (word.length == 1) listOf(zhuyin)
                        else SyllableManager.splitFullZhuyin(zhuyin, word.length) ?: return 0
            if (parts.size != word.length) return 0
            return parts.indices.count { userTones[it] != null && userTones[it] != toneOf(parts[it]) }
        }
        val exact = fullZhuyinWordMap[clean]?.filter { it.word == word }
        if (!exact.isNullOrEmpty()) return exact.minByOrNull { toneMisses(it.zhuyin) }!!.zhuyin
        if (word.length == 1) {
            val readings = charZhuyinMap[word[0]] ?: return null
            val matched = readings.filter { it.filter { c -> c !in "ˇˋˊ˙ " } == clean }
            if (matched.isNotEmpty()) return matched.minByOrNull { toneMisses(it) }
            return readings.firstOrNull { it.filter { c -> c !in "ˇˋˊ˙ " }.startsWith(clean) }
        }
        return null
    }

    /**
     * 實體鍵盤整句上屏時的學習（取代「整句當一個詞學」）：
     * 依音節與最終分詞，把 2~4 字片語以實際讀音各自學習；單字只在使用者手動改字（pinned）時學習；
     * 並學習相鄰片語的 bigram。回傳最終分詞，供呼叫端更新上下文。
     */
    fun learnCommittedSentence(
        sentence: String,
        syllables: List<String>,
        pinned: Map<Int, String>,
        previousWord: String?
    ): List<String> {
        var words = if (syllables.size == sentence.length) findBestSentenceFromSyllables(syllables, pinned) else emptyList()
        if (words.joinToString("") != sentence) words = sentence.map { it.toString() }
        var prev = previousWord
        var pos = 0
        for (w in words) {
            val span = pos until pos + w.length
            val learnable = if (w.length == 1) pinned.containsKey(pos) else w.length in 2..4
            if (learnable && syllables.size == sentence.length) {
                val reading = resolveReading(w, syllables.subList(span.first, span.last + 1))
                if (reading != null) learnWord(w, reading)
            }
            if (prev != null && prev != w && prev.length in 1..8 && w.length in 1..8) learnBigram(prev, w)
            prev = w
            pos += w.length
        }
        return words
    }

    /**
     * 41 鍵大千注音全鍵盤專屬預測與候選檢索引擎（支援聲母簡拼、混合簡打與全拼聯想）
     */
    fun searchFullZhuyin(inputZhuyin: String, contextWord: String? = null): List<DictEntry> {
        if (inputZhuyin.isEmpty()) return emptyList()
        val effectiveContext = contextWord ?: currentContextWord

        val results = mutableListOf<DictEntry>()
        val seenWords = hashSetOf<String>()

        val cleanInput = inputZhuyin.filter { it !in "ˇˋˊ˙" }
        val toneChar = inputZhuyin.find { it in "ˇˋˊ˙" }
        val isSingleSyllable = SyllableManager.isValidSyllable(cleanInput)
        val soundMatches = soundToCharMap[cleanInput]

        // 策略分流：
        // A. 若輸入構成合法單音節且字典中有單字對應（例如 ㄒㄧㄢ、ㄓㄨ、ㄇㄚ、ㄕ、ㄍㄨㄛ 等）：
        //    使用者在拼寫單一字音，候選字必須以該音節之「單字同音字」為最優先呈現！
        if (isSingleSyllable && !soundMatches.isNullOrEmpty()) {
            val singleEntries = mutableListOf<DictEntry>()

            // 1. 個人詞庫中的該音節單字（最高優先）
            val userEntries = userDict.getAllEntries()
            val userCharSet = hashSetOf<String>()
            for (u in userEntries) {
                if (u.word.length == 1) {
                    val uClean = u.zhuyin.filter { it !in "ˇˋˊ˙" }
                    if (uClean == cleanInput) {
                        userCharSet.add(u.word)
                        val baseWeight = 50_000_000 + u.count * 1_000_000
                        val toneBonus = if (toneChar != null) {
                            if (u.zhuyin.contains(toneChar)) 20_000_000 else 0
                        } else {
                            if (!u.zhuyin.any { it in "ˇˋˊ˙" }) fullNoToneFirstBonus else 0
                        }
                        singleEntries.add(DictEntry(u.word, u.zhuyin, baseWeight + toneBonus))
                    }
                }
            }

            // 2. 字典反向索引中的所有單字同音字（依前一個詞的語境放大詞頻，讓「很」勝過同音的冷僻字）
            for (e in soundMatches) {
                if (userCharSet.contains(e.word)) continue
                val boost = userDict.getBoost(e.word, e.zhuyin)
                val baseWeight = bigramScaled(e.weight + boost, effectiveContext, e.word)
                val toneBonus = if (toneChar != null) {
                    if (e.zhuyin.contains(toneChar)) 20_000_000 else 0
                } else {
                    if (!e.zhuyin.any { it in "ˇˋˊ˙" }) fullNoToneFirstBonus else 0
                }
                singleEntries.add(DictEntry(e.word, e.zhuyin, baseWeight + toneBonus))
            }

            // 排序單字：依據綜合加權降序
            singleEntries.sortByDescending { it.weight }
            for (e in singleEntries) {
                if (seenWords.add(e.word)) {
                    results.add(e)
                }
            }

            // 3. 單字之後，再補入該音節開頭的常用詞彙（個人詞庫多字詞與 Trie 詞庫）
            val userMultiEntries = userEntries.filter {
                it.word.length > 1 && it.cleanZhuyin.startsWith(cleanInput)
            }.sortedByDescending { it.count }
            for (u in userMultiEntries) {
                if (seenWords.add(u.word)) {
                    results.add(DictEntry(u.word, u.zhuyin, 30_000_000 + u.count * 1_000_000))
                    if (results.size >= 200) break
                }
            }

            val isTolerantEnabled = context?.let { com.bopomofo.t9ime.data.PreferencesRepository.isTolerantInputEnabled(it) } ?: false
            val keys = cleanInput.mapNotNull { KeyMapping.getKeyId(it) }
            if (keys.isNotEmpty()) {
                val trieResults = trie.searchPrefix(keys, includeTolerant = isTolerantEnabled)
                val sortedTrieResults = if (toneChar != null) {
                    trieResults.sortedByDescending {
                        if (it.zhuyin.startsWith(inputZhuyin) || it.zhuyin.takeWhile { c -> c != ' ' }.contains(toneChar)) {
                            it.weight + 10_000_000
                        } else {
                            it.weight
                        }
                    }
                } else {
                    trieResults
                }
                for (e in sortedTrieResults) {
                    val eClean = e.cleanZhuyin
                    if (eClean.startsWith(cleanInput) && seenWords.add(e.word)) {
                        results.add(e)
                        if (results.size >= 200) break
                    }
                }
            }

            return results
        }

        // B. 非合法單音節或字典中無獨立單字音節（如簡拼 ㄐㄊ、混合簡打 ㄐㄧㄣㄊ、長詞全拼等）：
        // 1. 個人詞庫 (UserDict) 最高優先權
        val userEntries = userDict.getAllEntries()
        for (u in userEntries) {
            val uClean = u.zhuyin.filter { it !in "ˇˋˊ˙ " }
            if (uClean.startsWith(cleanInput) || u.word.startsWith(cleanInput)) {
                if (seenWords.add(u.word)) {
                    results.add(DictEntry(u.word, u.zhuyin, 50_000_000 + u.count * 1_000_000))
                }
            }
        }

        // 1.2 全拼精確詞庫匹配 (Exact Full Zhuyin Match, 如 ㄓㄉㄠ -> 知道, ㄊㄧㄢㄑㄧ -> 天氣, ㄐㄧㄣㄊㄧㄢ -> 今天)
        val exactZhuyinWords = fullZhuyinWordMap[cleanInput]
        if (!exactZhuyinWords.isNullOrEmpty()) {
            val sortedExact = exactZhuyinWords.sortedByDescending {
                val toneScore = if (toneChar != null && it.zhuyin.contains(toneChar)) 20_000_000L else 0L
                toneScore + bigramScaled(it.weight + userDict.getBoost(it.word, it.zhuyin), effectiveContext, it.word).toLong()
            }
            for (e in sortedExact) {
                if (seenWords.add(e.word)) {
                    results.add(e)
                    if (results.size >= 200) break
                }
            }
        }

        // 1.5 長句智慧分詞預測 (基於 DP / Viterbi 最優路徑，支援連打中文長句一口氣出字)
        val autoSyllables = SyllableManager.splitFullZhuyinAuto(inputZhuyin)
        if (autoSyllables != null && autoSyllables.size >= 2) {
            val sentenceWords = findBestSentenceFromSyllables(autoSyllables)
            val combinedWord = sentenceWords.joinToString("")
            if (combinedWord.isNotEmpty() && seenWords.add(combinedWord)) {
                results.add(DictEntry(combinedWord, autoSyllables.joinToString(" "), 80_000_000))
            }
        }

        // 2. 首碼簡拼精確匹配 (Pure Initials Match, 如 ㄐㄊ -> 今天, 家庭)
        if (cleanInput.length >= 2) {
            val matched = initialMap[cleanInput]
            if (matched != null) {
                for (e in matched) {
                    if (seenWords.add(e.word)) {
                        results.add(e)
                        if (results.size >= 100) break
                    }
                }
            }
        }

        // 3. 混合簡打匹配 (Hybrid Match, 如 ㄐㄧㄣㄊ -> 今天: 前綴音節 ㄐㄧㄣ + 後續首碼 ㄊ)
        if (cleanInput.length >= 3) {
            for (splitIdx in minOf(cleanInput.length - 1, 3) downTo 1) {
                val firstSyl = cleanInput.substring(0, splitIdx)
                val restInitials = cleanInput.substring(splitIdx)
                if (SyllableManager.isValidSyllable(firstSyl)) {
                    val targetInitials = firstSyl[0] + restInitials
                    val matched = initialMap[targetInitials]
                    if (matched != null) {
                        for (e in matched) {
                            val eClean = e.cleanZhuyin
                            if (eClean.startsWith(firstSyl) && seenWords.add(e.word)) {
                                results.add(e)
                                if (results.size >= 200) break
                            }
                        }
                    }
                }
            }
        }

        // 4. 全拼前綴檢索 (Trie Prefix Match, 如 ㄐㄧㄣ -> 今, 金, 今天, 金融)
        val isTolerantEnabled = context?.let { com.bopomofo.t9ime.data.PreferencesRepository.isTolerantInputEnabled(it) } ?: false
        val keys = cleanInput.mapNotNull { KeyMapping.getKeyId(it) }
        if (keys.isNotEmpty()) {
            val trieResults = trie.searchPrefix(keys, includeTolerant = isTolerantEnabled)
            for (e in trieResults) {
                val eClean = e.cleanZhuyin
                if (eClean.startsWith(cleanInput) && seenWords.add(e.word)) {
                    results.add(e)
                    if (results.size >= 200) break
                }
            }
        }

        // 5. 單字精確與聲母檢索 (若輸入為單字音節或聲母，使用反向音節與首碼索引 O(1) 替代全量線性掃描)
        val singleMatches = mutableListOf<DictEntry>()
        soundToCharMap[cleanInput]?.let { entries ->
            for (e in entries) {
                if (!seenWords.contains(e.word)) {
                    singleMatches.add(e)
                }
            }
        }
        if (cleanInput.length == 1) {
            initialToCharMap[cleanInput[0]]?.let { entries ->
                for (e in entries) {
                    if (!seenWords.contains(e.word)) {
                        singleMatches.add(e)
                        if (singleMatches.size >= 200) break
                    }
                }
            }
        }
        for (e in singleMatches) {
            if (seenWords.add(e.word)) {
                results.add(e)
                if (results.size >= 200) break
            }
        }

        return results
    }

    /**
     * 單字同音/同按鍵組合候選字查詢（供底線組字長按逐字替換使用）：
     * 1. 查詢完全相同鍵序的所有單字（涵蓋該鍵位上所有可能的拼音字，如 Key 6 包含 此、次、意、吃、一、衣、依、醫等）
     * 2. 依該字之注音查詢同音字（不同聲調）
     * 3. 納入前綴延伸單字
     * 4. 排序：首位原字、使用者曾選字最優先 (getBoost > 0)、字典權重降序
     */
    /**
     * 取得當前組字文字指定字元索引對應的輸入音節 (帶聲調)
     * 1. 優先從當前頂部候選詞（或首選詞）的注音切分出第 charIndex 個音節
     * 2. 若為單字，且有鎖定注音組合或頂部注音組合，返回該組合
     */
    fun getComposingSyllableAt(charIndex: Int): String? {
        val topEntry = cachedCandidates.firstOrNull()
        if (topEntry != null && topEntry.zhuyin.isNotEmpty()) {
            val syllables = SyllableManager.splitFullZhuyin(topEntry.zhuyin, topEntry.word.length)
            if (syllables != null && charIndex in syllables.indices) {
                return syllables[charIndex]
            }
        }
        if (charIndex == 0) {
            val combo = lockedZhuyinCombo ?: cachedZhuyinCombos.firstOrNull()
            if (!combo.isNullOrEmpty()) {
                return combo
            }
        }
        return null
    }

    /**
     * 取得指定音節的所有聲調單字列表（供自建自有詞面板使用）
     * @param cleanSyllable 去聲調注音音節（例如 "ㄊㄧㄢ"）
     * @param toneFilter 指定聲調 (' ' 或 '1' 一聲, 'ˊ' 二聲, 'ˇ' 三聲, 'ˋ' 四聲, '˙' 輕聲, null 全部聲調)
     */
    fun getCharsForSyllable(cleanSyllable: String, toneFilter: Char? = null): List<DictEntry> {
        val clean = cleanSyllable.filter { it !in "ˇˋˊ˙ " }
        val entries = soundToCharMap[clean]
        val results = if (!entries.isNullOrEmpty()) {
            when (toneFilter) {
                null -> entries
                ' ', '1' -> entries.filter { !it.zhuyin.any { c -> c in "ˇˋˊ˙" } }
                'ˊ', '2' -> entries.filter { it.zhuyin.contains('ˊ') }
                'ˇ', '3' -> entries.filter { it.zhuyin.contains('ˇ') }
                'ˋ', '4' -> entries.filter { it.zhuyin.contains('ˋ') }
                '˙', '5' -> entries.filter { it.zhuyin.contains('˙') }
                else -> entries
            }
        } else {
            val seq = KeyMapping.getSequence(clean, ignoreTones = true)
            val node = trie.searchNode(seq)
            val list = node?.exactEntries?.values?.filter { it.word.length == 1 } ?: emptyList()
            when (toneFilter) {
                null -> list
                ' ', '1' -> list.filter { !it.zhuyin.any { c -> c in "ˇˋˊ˙" } }
                'ˊ', '2' -> list.filter { it.zhuyin.contains('ˊ') }
                'ˇ', '3' -> list.filter { it.zhuyin.contains('ˇ') }
                'ˋ', '4' -> list.filter { it.zhuyin.contains('ˋ') }
                '˙', '5' -> list.filter { it.zhuyin.contains('˙') }
                else -> list
            }
        }

        return results.sortedByDescending { it.weight + userDict.getBoost(it.word) }
    }

    /**
     * 同音/同拼法候選字查詢：
     * 1. 若傳入 preferredZhuyin（使用者實際輸入的音節），優先以該音節排序，避免多音字卡在字典第一讀音
     * 2. 優先由 soundToCharMap 檢索標準同音字，以同聲調 > 字典權重 > 使用者個人化權重排序
     * 3. 納入前綴延伸單字
     * 4. 排序：首位原字、使用者曾選字最優先 (getBoost > 0)、字典權重降序
     */
    fun getHomophonesForChar(ch: Char, preferredZhuyin: String? = null): List<DictEntry> {
        var zhuyins = charZhuyinMap[ch] ?: emptyList()
        if (!preferredZhuyin.isNullOrEmpty()) {
            val cleanPref = preferredZhuyin.filter { it !in "ˇˋˊ˙" }
            val matched = zhuyins.find { it.filter { c -> c !in "ˇˋˊ˙" } == cleanPref }
            if (matched != null) {
                zhuyins = listOf(matched) + zhuyins.filter { it != matched }
            } else {
                zhuyins = listOf(preferredZhuyin) + zhuyins
            }
        }
        val seen = LinkedHashSet<String>()

        // 0. 優先使用正統同音字庫 (soundToCharMap)：注音完全相符或同韻同音節單字（依同聲調與權重優先排序）
        val pureSoundEntries = mutableListOf<DictEntry>()
        val prefTone = preferredZhuyin?.find { it in "ˇˋˊ˙" }
        for (zy in zhuyins) {
            val cleanZy = zy.filter { it !in "ˇˋˊ˙" }
            val toneChar = prefTone ?: zy.find { it in "ˇˋˊ˙" }
            val entries = soundToCharMap[cleanZy]
            if (entries != null) {
                val sorted = entries.sortedByDescending {
                    val isSameTone = if (toneChar != null) it.zhuyin.contains(toneChar) else !it.zhuyin.any { c -> c in "ˇˋˊ˙" }
                    val toneScore = if (isSameTone) 100_000_000L else 0L
                    toneScore + it.weight + userDict.getBoost(it.word)
                }
                for (e in sorted) {
                    if (seen.add(e.word)) {
                        pureSoundEntries.add(e)
                    }
                }
            }
        }
        if (pureSoundEntries.isNotEmpty()) {
            val originalEntry = pureSoundEntries.find { it.word == ch.toString() }
                ?: DictEntry(ch.toString(), zhuyins.firstOrNull() ?: "", 1000)
            val others = pureSoundEntries.filter { it.word != ch.toString() }
            return (listOf(originalEntry) + others).take(80)
        }

        val exactKeyEntries = mutableListOf<DictEntry>()
        val sameZhuyinEntries = mutableListOf<DictEntry>()
        val prefixKeyEntries = mutableListOf<DictEntry>()

        for (zy in zhuyins) {
            val cleanZy = zy.filter { it !in "ˇˋˊ˙" }
            val seqWithTone = KeyMapping.getSequence(zy, ignoreTones = false)
            val seqNoTone = KeyMapping.getSequence(zy, ignoreTones = true)

            // 1. 同按鍵構成之精確組合（完全相同鍵序的所有單字）
            for (seq in listOf(seqWithTone, seqNoTone).distinct()) {
                if (seq.isEmpty()) continue
                val node = trie.searchNode(seq) ?: continue
                for (e in node.exactEntries.values) {
                    if (e.word.length == 1 && seen.add(e.word)) {
                        val eCleanZy = e.zhuyin.filter { it !in "ˇˋˊ˙" }
                        if (eCleanZy == cleanZy) {
                            sameZhuyinEntries.add(e)
                        } else {
                            exactKeyEntries.add(e)
                        }
                    }
                }
            }

            // 2. 同按鍵構成之前綴延伸單字（例如單鍵長按時可展開多鍵字）
            if (seqNoTone.size <= 2) {
                val prefixNodes = trie.searchPrefix(seqNoTone, maxDepth = 2)
                for (e in prefixNodes) {
                    if (e.word.length == 1 && seen.add(e.word)) {
                        prefixKeyEntries.add(e)
                    }
                }
            }
        }

        // 保底：若查無注音，從當前候選字表中提取所有單字
        if (sameZhuyinEntries.isEmpty() && exactKeyEntries.isEmpty()) {
            for (c in cachedCandidates) {
                if (c.word.length == 1 && seen.add(c.word)) {
                    sameZhuyinEntries.add(c)
                }
            }
        }

        val originalEntry = sameZhuyinEntries.find { it.word == ch.toString() }
            ?: exactKeyEntries.find { it.word == ch.toString() }
            ?: DictEntry(ch.toString(), zhuyins.firstOrNull() ?: "", 1000)

        // 排序規則：使用者選過字最優先 (getBoost > 0)，其次同音字，再者同鍵其他字
        val sortComparator = compareByDescending<DictEntry> { userDict.getBoost(it.word) > 0 }
            .thenByDescending { it.weight + userDict.getBoost(it.word) }

        val sortedSameZy = sameZhuyinEntries.filter { it.word != ch.toString() }.sortedWith(sortComparator)
        val sortedExactKey = exactKeyEntries.filter { it.word != ch.toString() }.sortedWith(sortComparator)
        val sortedPrefix = prefixKeyEntries.filter { it.word != ch.toString() }.sortedWith(sortComparator)

        val merged = mutableListOf<DictEntry>()
        merged.add(originalEntry)
        merged.addAll(sortedSameZy)
        merged.addAll(sortedExactKey)
        merged.addAll(sortedPrefix)

        return merged.take(80)
    }

    /**
     * 同音字查詢（長按候選詞用）：根據詞條的注音，從詞典 Trie 中取出所有相同按鍵序列的同音字/詞，
     * 排除詞本身，依個人化使用權重排序，供使用者替換選字。
     */
    fun getHomophonesFor(entry: DictEntry): List<DictEntry> {
        if (entry.word.length == 1) {
            val pref = entry.zhuyin.ifEmpty { null }
            return getHomophonesForChar(entry.word[0], pref)
        }
        val zhuyin = if (entry.zhuyin.isNotEmpty()) entry.zhuyin else entry.word.map { getZhuyinForChar(it) }.joinToString(" ")
        if (zhuyin.isEmpty()) return emptyList()

        val keys = KeyMapping.getSequence(zhuyin, ignoreTones = false)
        val keysNoTone = KeyMapping.getSequence(zhuyin, ignoreTones = true)

        val seen = LinkedHashSet<String>()
        val results = mutableListOf<DictEntry>()

        // 查詢帶聲調序列與無聲調序列的候選詞，合併去重
        for (seq in listOf(keys, keysNoTone).distinct()) {
            if (seq.isEmpty()) continue
            val node = trie.searchNode(seq) ?: continue
            for (e in node.exactEntries.values) {
                if (e.word != entry.word && e.zhuyin.isNotEmpty() && seen.add(e.word)) {
                    results.add(e)
                }
            }
        }

        return results
            .sortedByDescending { it.weight + userDict.getBoost(it.word) }
            .take(20)
    }

    fun getTopComposingWord(): String {
        val candidates = getCandidates()
        if (candidates.isNotEmpty()) return candidates.first().word
        return getPossibleZhuyinCombinations().firstOrNull() ?: ""
    }

    /**
     * 常用對話接續聯想詞保底（台灣生活語境高頻詞）
     */
    private val defaultAssociations = mapOf(
        "你" to listOf("好", "在幹嘛", "在哪裡", "覺得呢", "知道嗎", "可以嗎", "有空嗎", "要不要", "們"),
        "你好" to listOf("！", "，", "請問", "我是", "早安", "晚安", "歡迎", "大家", "嗎"),
        "我" to listOf("是", "在", "想", "要", "知道", "覺得", "喜歡", "今天", "現在", "可以", "們"),
        "他" to listOf("是", "說", "在", "想", "要", "知道", "今天", "去哪", "們"),
        "她" to listOf("是", "說", "在", "想", "要", "知道", "今天", "們"),
        "謝謝" to listOf("你", "您", "大家", "配合", "分享", "幫忙", "！"),
        "貢丸" to listOf("湯", "好吃", "新竹", "麵", "米粉"),
        "台灣" to listOf("高鐵", "大學", "美食", "天氣", "啤酒", "銀行"),
        "今天" to listOf("天氣", "晚上", "中午", "早上", "星期幾", "要吃什麼", "好累"),
        "明天" to listOf("見", "早上", "晚上", "下午", "放假", "要開會"),
        "什麼" to listOf("時候", "意思", "名字", "東西", "事情", "原因"),
        "怎麼" to listOf("了", "樣", "辦", "去", "做", "會這樣", "聯絡"),
        "可以" to listOf("嗎", "幫我", "直接", "一起", "考慮", "使用"),
        "沒問題" to listOf("！", "，我來處理", "，交給我", "馬上辦"),
        "大家" to listOf("好", "早安", "晚安", "辛苦了", "注意安全"),
        "早安" to listOf("！", "，祝你有美好的一天", "，今天好冷"),
        "晚安" to listOf("！", "，祝好夢", "，明天見"),
        "工作" to listOf("順利", "認真", "人員", "愉快", "機會")
    )

    /**
     * 接續詞預測（聯想詞）
     * 結合 libchewing 官方 16 萬詞庫語料索引與台灣地道高頻接續詞
     */
    fun getNextWordPredictions(word: String): List<DictEntry> {
        if (word.isEmpty()) return emptyList()

        val rankList = { list: List<DictEntry> ->
            list.sortedByDescending { it.weight + userDict.getBoost(it.word) }
        }

        val resultList = mutableListOf<DictEntry>()

        // 0. 最高優先：從使用者與系統 Bigram 轉移表查詢真實上下文接續詞
        synchronized(bigramMap) {
            val bg = bigramMap[word]
            if (!bg.isNullOrEmpty()) {
                val sortedBg = bg.entries.sortedByDescending { it.value }
                for ((nextW, wt) in sortedBg) {
                    if (resultList.none { it.word == nextW }) {
                        resultList.add(DictEntry(nextW, "", 50_000 + wt * 100))
                    }
                }
            }
        }

        // 1. 完全匹配剛上屏詞彙
        val exactList = nextWordMap[word]
        if (!exactList.isNullOrEmpty()) {
            resultList.addAll(rankList(exactList))
        }

        // 2. 常用對話聯想規則匹配
        val defaultMatches = defaultAssociations[word]
        if (!defaultMatches.isNullOrEmpty()) {
            for (w in defaultMatches) {
                if (resultList.none { it.word == w }) {
                    resultList.add(DictEntry(w, "", 10_000))
                }
            }
        }

        // 3. 結尾 2 字匹配（如選了長詞，取末尾詞接續）
        if (word.length >= 2) {
            val suffix2 = word.takeLast(2)
            val list2 = nextWordMap[suffix2]
            if (!list2.isNullOrEmpty()) {
                for (e in rankList(list2)) {
                    if (resultList.none { it.word == e.word }) resultList.add(e)
                }
            }
            val def2 = defaultAssociations[suffix2]
            if (!def2.isNullOrEmpty()) {
                for (w in def2) {
                    if (resultList.none { it.word == w }) resultList.add(DictEntry(w, "", 8_000))
                }
            }
        }

        // 4. 結尾單字匹配
        val suffix1 = word.takeLast(1)
        val list1 = nextWordMap[suffix1]
        if (!list1.isNullOrEmpty()) {
            for (e in rankList(list1)) {
                if (resultList.none { it.word == e.word }) resultList.add(e)
            }
        }
        val def1 = defaultAssociations[suffix1]
        if (!def1.isNullOrEmpty()) {
            for (w in def1) {
                if (resultList.none { it.word == w }) resultList.add(DictEntry(w, "", 5_000))
            }
        }

        return resultList.take(15)
    }

    /**
     * 41 鍵全鍵盤「聲母簡拼 / 偷懶輸入」查詢：
     * 依各字的聲母（首字符號，如 "ㄐㄊ"）快速篩選詞庫，並以個人詞庫使用歷史優先排序
     */
    fun searchInitials(initials: String): List<DictEntry> {
        if (initials.isEmpty()) return emptyList()
        val list = initialMap[initials] ?: return emptyList()
        return list.sortedWith(
            compareByDescending<DictEntry> { userDict.getBoost(it.word) > 0 }
                .thenByDescending { it.weight + userDict.getBoost(it.word) }
        ).take(80)
    }

    /**
     * 精確注音詞條查詢（去聲調）
     */
    fun getExactZhuyinWords(cleanZhuyin: String): List<DictEntry> {
        val list = fullZhuyinWordMap[cleanZhuyin] ?: return emptyList()
        return list.sortedByDescending { it.weight + userDict.getBoost(it.word) }
    }

    /**
     * 新酷音 / PIME 標準音節序列動態規劃組句 (Bigram Viterbi 最優路徑)
     * 由 41 鍵螢幕全鍵盤與外接實體鍵盤共用：
     * - 支援跨音節詞庫匹配 (例如 ㄓ + ㄉㄠˋ -> 知道, ㄐㄧㄣ + ㄊㄧㄢ -> 今天)
     * - 支援聲母簡拼匹配 (例如 ㄐ + ㄊ -> 今天)
     * - 結合 currentContextWord 與前後詞 Bigram 轉移機率，解決同音異字詞組句歧義
     */
    fun findBestSentenceFromSyllables(
        syllables: List<String>,
        pinnedWords: Map<Int, String> = emptyMap()
    ): List<String> {
        val n = syllables.size
        if (n == 0) return emptyList()

        val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        dp[0] = 0.0
        val bestPrev = arrayOfNulls<Pair<Int, String>>(n + 1)

        val wordBonus = 2.0
        val logTotal = if (logTotalWeight > 0) logTotalWeight else 17.91

        for (i in 1..n) {
            val maxLen = minOf(6, i)
            for (len in 1..maxLen) {
                val j = i - len
                if (dp[j] == Double.NEGATIVE_INFINITY) continue

                var pinnedConflict = false
                var singlePinnedChar: String? = null
                if (len == 1 && pinnedWords.containsKey(j)) {
                    singlePinnedChar = pinnedWords[j]
                } else {
                    for (k in j until i) {
                        if (pinnedWords.containsKey(k)) {
                            pinnedConflict = true
                            break
                        }
                    }
                }
                if (pinnedConflict) continue

                if (singlePinnedChar != null) {
                    val score = dp[j] + 1000.0
                    if (score > dp[i]) {
                        dp[i] = score
                        bestPrev[i] = Pair(j, singlePinnedChar)
                    }
                    continue
                }

                val subSyllables = syllables.subList(j, i)
                val subClean = subSyllables.joinToString("") { it.filter { c -> c !in "ˇˋˊ˙ " } }
                val prevWord = if (j == 0) currentContextWord else bestPrev[j]?.second

                // 優先查詢完整注音詞庫；若該區間全部皆為單一聲母（如 ["ㄐ", "ㄊ"]），允許退回查詢簡拼詞庫 initialMap
                var words = fullZhuyinWordMap[subClean]
                var isInitialAbbrev = false
                if (words.isNullOrEmpty() && len >= 2 && subSyllables.all { it.length == 1 && it[0] in "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ" }) {
                    words = initialMap[subClean]
                    isInitialAbbrev = true
                }

                if (!words.isNullOrEmpty()) {
                    for (entry in words) {
                        if (entry.word.length != len) continue
                        val boost = userDict.getBoost(entry.word, entry.zhuyin)
                        val effectiveWeight = entry.weight + boost
                        val logProb = Math.log(maxOf(effectiveWeight.toDouble(), 1.0)) - logTotal
                        val bonus = (entry.word.length - 1) * wordBonus - (if (isInitialAbbrev) 1.5 else 0.0)

                        // Bigram 上下文語境加分
                        val bigramBonus = minOf(6.0, getBigramBonus(prevWord, entry.word) * 1.8 * bigramScaleSentence)

                        var hasToneMismatch = false
                        var exactToneMatchCount = 0
                        val entryZhuyinList = if (entry.zhuyin.contains(' ')) {
                            entry.zhuyin.split(' ').filter { it.isNotEmpty() }
                        } else if (len == 1) {
                            listOf(entry.zhuyin)
                        } else {
                            SyllableManager.splitFullZhuyin(entry.zhuyin, len) ?: emptyList()
                        }

                        if (!isInitialAbbrev && entryZhuyinList.size == len) {
                            for (idx in 0 until len) {
                                val userSyll = subSyllables[idx]
                                val userTone = userSyll.find { it in "ˇˋˊ˙" }
                                val entryTone = entryZhuyinList[idx].find { it in "ˇˋˊ˙" }
                                if (userTone != null) {
                                    if (userTone == entryTone) {
                                        exactToneMatchCount++
                                    } else {
                                        hasToneMismatch = true
                                    }
                                }
                            }
                        } else if (!isInitialAbbrev && len == 1) {
                            val userTone = subSyllables[0].find { it in "ˇˋˊ˙" }
                            val entryTone = entry.zhuyin.find { it in "ˇˋˊ˙" }
                            if (userTone != null) {
                                if (userTone == entryTone) {
                                    exactToneMatchCount++
                                } else {
                                    hasToneMismatch = true
                                }
                            }
                        }

                        val tonePenalty = if (hasToneMismatch) -25.0 else 0.0
                        val toneBonus = exactToneMatchCount * 8.0
                        val score = dp[j] + logProb + bonus + bigramBonus + toneBonus + tonePenalty
                        if (score > dp[i]) {
                            dp[i] = score
                            bestPrev[i] = Pair(j, entry.word)
                        }
                    }
                } else if (len == 1) {
                    val userSyll = subSyllables[0]
                    val userTone = userSyll.find { it in "ˇˋˊ˙" }
                    val soundMatches = soundToCharMap[subClean]
                    val fallbackChar = if (userTone != null && !soundMatches.isNullOrEmpty()) {
                        soundMatches.find { it.zhuyin.contains(userTone) }?.word ?: soundMatches.firstOrNull()?.word ?: subClean
                    } else {
                        soundMatches?.firstOrNull()?.word ?: subClean
                    }
                    val score = dp[j] - 10.0
                    if (score > dp[i]) {
                        dp[i] = score
                        bestPrev[i] = Pair(j, fallbackChar)
                    }
                }
            }
        }

        if (dp[n] == Double.NEGATIVE_INFINITY) {
            return syllables.map { s ->
                val clean = s.filter { it !in "ˇˋˊ˙ " }
                val tone = s.find { it in "ˇˋˊ˙" }
                val list = soundToCharMap[clean]
                if (tone != null && !list.isNullOrEmpty()) {
                    list.find { it.zhuyin.contains(tone) }?.word ?: list.firstOrNull()?.word ?: clean
                } else {
                    list?.firstOrNull()?.word ?: clean
                }
            }
        }

        val result = mutableListOf<String>()
        var curr = n
        var safetyCounter = 0
        while (curr > 0 && safetyCounter <= n) {
            safetyCounter++
            val prev = bestPrev[curr] ?: break
            if (prev.first >= curr) break // 嚴格防禦：指針必須向前推進，防止任何死迴圈
            result.add(prev.second)
            curr = prev.first
        }
        result.reverse()
        return result
    }

    fun close() {
        try {
            userDict.onDictionaryChangedListener = null
        } catch (_: Exception) {}
        mainHandler?.removeCallbacks(bigramSaveRunnable)
        try {
            bigramExecutor.execute { saveBigramToFile() }
            bigramExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
