package com.bopomofo.t9ime.engine

/**
 * 台灣注音標準音節管理器 (SyllableManager)
 * 依據教育部標準與 libchewing 詞庫，管理台灣繁體中文全量 429 個合法無聲調音節。
 * 徹底杜絕長度暴力截斷與非拼音雜訊，提供精確音節檢索與頻率排序。
 */
object SyllableManager {

    val VALID_SYLLABLES: List<String> = listOf(
        "ㄅ", "ㄅㄚ", "ㄅㄛ", "ㄅㄞ", "ㄅㄟ", "ㄅㄠ", "ㄅㄢ", "ㄅㄣ", "ㄅㄤ", "ㄅㄥ",
        "ㄅㄧ", "ㄅㄧㄝ", "ㄅㄧㄠ", "ㄅㄧㄢ", "ㄅㄧㄣ", "ㄅㄧㄥ", "ㄅㄨ", "ㄆ", "ㄆㄚ", "ㄆㄛ",
        "ㄆㄞ", "ㄆㄟ", "ㄆㄠ", "ㄆㄡ", "ㄆㄢ", "ㄆㄣ", "ㄆㄤ", "ㄆㄥ", "ㄆㄧ", "ㄆㄧㄝ",
        "ㄆㄧㄠ", "ㄆㄧㄢ", "ㄆㄧㄣ", "ㄆㄧㄥ", "ㄆㄨ", "ㄇ", "ㄇㄚ", "ㄇㄛ", "ㄇㄜ", "ㄇㄞ",
        "ㄇㄟ", "ㄇㄠ", "ㄇㄡ", "ㄇㄢ", "ㄇㄣ", "ㄇㄤ", "ㄇㄥ", "ㄇㄧ", "ㄇㄧㄝ", "ㄇㄧㄠ",
        "ㄇㄧㄡ", "ㄇㄧㄢ", "ㄇㄧㄣ", "ㄇㄧㄥ", "ㄇㄨ", "ㄈ", "ㄈㄚ", "ㄈㄛ", "ㄈㄟ", "ㄈㄡ",
        "ㄈㄢ", "ㄈㄣ", "ㄈㄤ", "ㄈㄥ", "ㄈㄧㄠ", "ㄈㄨ", "ㄈㄨㄥ", "ㄉ", "ㄉㄚ", "ㄉㄜ",
        "ㄉㄞ", "ㄉㄟ", "ㄉㄠ", "ㄉㄡ", "ㄉㄢ", "ㄉㄣ", "ㄉㄤ", "ㄉㄥ", "ㄉㄧ", "ㄉㄧㄝ",
        "ㄉㄧㄠ", "ㄉㄧㄡ", "ㄉㄧㄢ", "ㄉㄧㄥ", "ㄉㄨ", "ㄉㄨㄛ", "ㄉㄨㄟ", "ㄉㄨㄢ", "ㄉㄨㄣ", "ㄉㄨㄥ",
        "ㄊ", "ㄊㄚ", "ㄊㄜ", "ㄊㄞ", "ㄊㄠ", "ㄊㄡ", "ㄊㄢ", "ㄊㄤ", "ㄊㄥ", "ㄊㄧ",
        "ㄊㄧㄝ", "ㄊㄧㄠ", "ㄊㄧㄢ", "ㄊㄧㄥ", "ㄊㄨ", "ㄊㄨㄛ", "ㄊㄨㄟ", "ㄊㄨㄢ", "ㄊㄨㄣ", "ㄊㄨㄥ",
        "ㄋ", "ㄋㄚ", "ㄋㄜ", "ㄋㄞ", "ㄋㄟ", "ㄋㄠ", "ㄋㄡ", "ㄋㄢ", "ㄋㄣ", "ㄋㄤ",
        "ㄋㄥ", "ㄋㄧ", "ㄋㄧㄚ", "ㄋㄧㄝ", "ㄋㄧㄠ", "ㄋㄧㄡ", "ㄋㄧㄢ", "ㄋㄧㄣ", "ㄋㄧㄤ", "ㄋㄧㄥ",
        "ㄋㄨ", "ㄋㄨㄛ", "ㄋㄨㄢ", "ㄋㄨㄣ", "ㄋㄨㄥ", "ㄋㄩ", "ㄋㄩㄝ", "ㄌ", "ㄌㄚ", "ㄌㄛ",
        "ㄌㄜ", "ㄌㄞ", "ㄌㄟ", "ㄌㄠ", "ㄌㄡ", "ㄌㄢ", "ㄌㄤ", "ㄌㄥ", "ㄌㄧ", "ㄌㄧㄚ",
        "ㄌㄧㄝ", "ㄌㄧㄠ", "ㄌㄧㄡ", "ㄌㄧㄢ", "ㄌㄧㄣ", "ㄌㄧㄤ", "ㄌㄧㄥ", "ㄌㄨ", "ㄌㄨㄛ", "ㄌㄨㄢ",
        "ㄌㄨㄣ", "ㄌㄨㄥ", "ㄌㄩ", "ㄌㄩㄝ", "ㄌㄩㄢ", "ㄍ", "ㄍㄚ", "ㄍㄜ", "ㄍㄞ", "ㄍㄟ",
        "ㄍㄠ", "ㄍㄡ", "ㄍㄢ", "ㄍㄣ", "ㄍㄤ", "ㄍㄥ", "ㄍㄨ", "ㄍㄨㄚ", "ㄍㄨㄛ", "ㄍㄨㄞ",
        "ㄍㄨㄟ", "ㄍㄨㄢ", "ㄍㄨㄣ", "ㄍㄨㄤ", "ㄍㄨㄥ", "ㄎ", "ㄎㄚ", "ㄎㄜ", "ㄎㄞ", "ㄎㄠ",
        "ㄎㄡ", "ㄎㄢ", "ㄎㄣ", "ㄎㄤ", "ㄎㄥ", "ㄎㄨ", "ㄎㄨㄚ", "ㄎㄨㄛ", "ㄎㄨㄞ", "ㄎㄨㄟ",
        "ㄎㄨㄢ", "ㄎㄨㄣ", "ㄎㄨㄤ", "ㄎㄨㄥ", "ㄏ", "ㄏㄚ", "ㄏㄜ", "ㄏㄞ", "ㄏㄟ", "ㄏㄠ",
        "ㄏㄡ", "ㄏㄢ", "ㄏㄣ", "ㄏㄤ", "ㄏㄥ", "ㄏㄨ", "ㄏㄨㄚ", "ㄏㄨㄛ", "ㄏㄨㄞ", "ㄏㄨㄟ",
        "ㄏㄨㄢ", "ㄏㄨㄣ", "ㄏㄨㄤ", "ㄏㄨㄥ", "ㄐ", "ㄐㄧ", "ㄐㄧㄚ", "ㄐㄧㄝ", "ㄐㄧㄠ", "ㄐㄧㄡ",
        "ㄐㄧㄢ", "ㄐㄧㄣ", "ㄐㄧㄤ", "ㄐㄧㄥ", "ㄐㄩ", "ㄐㄩㄝ", "ㄐㄩㄢ", "ㄐㄩㄣ", "ㄐㄩㄥ", "ㄑ",
        "ㄑㄧ", "ㄑㄧㄚ", "ㄑㄧㄝ", "ㄑㄧㄠ", "ㄑㄧㄡ", "ㄑㄧㄢ", "ㄑㄧㄣ", "ㄑㄧㄤ", "ㄑㄧㄥ", "ㄑㄩ",
        "ㄑㄩㄝ", "ㄑㄩㄢ", "ㄑㄩㄣ", "ㄑㄩㄥ", "ㄒ", "ㄒㄧ", "ㄒㄧㄚ", "ㄒㄧㄝ", "ㄒㄧㄠ", "ㄒㄧㄡ",
        "ㄒㄧㄢ", "ㄒㄧㄣ", "ㄒㄧㄤ", "ㄒㄧㄥ", "ㄒㄩ", "ㄒㄩㄝ", "ㄒㄩㄢ", "ㄒㄩㄣ", "ㄒㄩㄥ", "ㄓ",
        "ㄓㄚ", "ㄓㄜ", "ㄓㄞ", "ㄓㄟ", "ㄓㄠ", "ㄓㄡ", "ㄓㄢ", "ㄓㄣ", "ㄓㄤ", "ㄓㄥ",
        "ㄓㄨ", "ㄓㄨㄚ", "ㄓㄨㄛ", "ㄓㄨㄞ", "ㄓㄨㄟ", "ㄓㄨㄢ", "ㄓㄨㄣ", "ㄓㄨㄤ", "ㄓㄨㄥ", "ㄔ",
        "ㄔㄚ", "ㄔㄜ", "ㄔㄞ", "ㄔㄠ", "ㄔㄡ", "ㄔㄢ", "ㄔㄣ", "ㄔㄤ", "ㄔㄥ", "ㄔㄨ",
        "ㄔㄨㄚ", "ㄔㄨㄛ", "ㄔㄨㄞ", "ㄔㄨㄟ", "ㄔㄨㄢ", "ㄔㄨㄣ", "ㄔㄨㄤ", "ㄔㄨㄥ", "ㄕ", "ㄕㄚ",
        "ㄕㄜ", "ㄕㄞ", "ㄕㄟ", "ㄕㄠ", "ㄕㄡ", "ㄕㄢ", "ㄕㄣ", "ㄕㄤ", "ㄕㄥ", "ㄕㄨ",
        "ㄕㄨㄚ", "ㄕㄨㄛ", "ㄕㄨㄞ", "ㄕㄨㄟ", "ㄕㄨㄢ", "ㄕㄨㄣ", "ㄕㄨㄤ", "ㄖ", "ㄖㄜ", "ㄖㄠ",
        "ㄖㄡ", "ㄖㄢ", "ㄖㄣ", "ㄖㄤ", "ㄖㄥ", "ㄖㄨ", "ㄖㄨㄛ", "ㄖㄨㄟ", "ㄖㄨㄢ", "ㄖㄨㄣ",
        "ㄖㄨㄥ", "ㄗ", "ㄗㄚ", "ㄗㄜ", "ㄗㄞ", "ㄗㄟ", "ㄗㄠ", "ㄗㄡ", "ㄗㄢ", "ㄗㄣ",
        "ㄗㄤ", "ㄗㄥ", "ㄗㄨ", "ㄗㄨㄛ", "ㄗㄨㄟ", "ㄗㄨㄢ", "ㄗㄨㄣ", "ㄗㄨㄥ", "ㄘ", "ㄘㄚ",
        "ㄘㄜ", "ㄘㄞ", "ㄘㄠ", "ㄘㄡ", "ㄘㄢ", "ㄘㄣ", "ㄘㄤ", "ㄘㄥ", "ㄘㄨ", "ㄘㄨㄛ",
        "ㄘㄨㄟ", "ㄘㄨㄢ", "ㄘㄨㄣ", "ㄘㄨㄥ", "ㄙ", "ㄙㄚ", "ㄙㄜ", "ㄙㄞ", "ㄙㄟ", "ㄙㄠ",
        "ㄙㄡ", "ㄙㄢ", "ㄙㄣ", "ㄙㄤ", "ㄙㄥ", "ㄙㄨ", "ㄙㄨㄛ", "ㄙㄨㄟ", "ㄙㄨㄢ", "ㄙㄨㄣ",
        "ㄙㄨㄥ", "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ",
        "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄧㄚ", "ㄧㄛ", "ㄧㄝ", "ㄧㄞ", "ㄧㄠ",
        "ㄧㄡ", "ㄧㄢ", "ㄧㄣ", "ㄧㄤ", "ㄧㄥ", "ㄨ", "ㄨㄚ", "ㄨㄛ", "ㄨㄞ", "ㄨㄟ",
        "ㄨㄢ", "ㄨㄣ", "ㄨㄤ", "ㄨㄥ", "ㄩ", "ㄩㄝ", "ㄩㄢ", "ㄩㄣ", "ㄩㄥ"
    )

    private val EXACT_KEY_MAP = mutableMapOf<List<Int>, MutableList<String>>()
    private val SYLLABLE_WEIGHTS = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val totalSyllableWeight = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        for (syl in VALID_SYLLABLES) {
            val seq = KeyMapping.getSequence(syl, ignoreTones = true)
            if (seq.isNotEmpty()) {
                EXACT_KEY_MAP.getOrPut(seq) { mutableListOf() }.add(syl)
            }
        }
    }

    /**
     * 重新載入字典前重置音節權重與快取，避免重複累加（供單元測試使用）
     */
    fun resetWeights() {
        SYLLABLE_WEIGHTS.clear()
        totalSyllableWeight.set(0L)
        synchronized(splitComboCache) {
            splitComboCache.clear()
            splitCacheTotal = 0L
        }
    }

    /**
     * 原子替換音節權重：在背景字典完成全量載入後一次性原子替換，避免載入中途 resetWeights 導致主線程查詢時序競態
     */
    fun applyNewWeights(newWeights: Map<String, Long>, newTotal: Long) {
        SYLLABLE_WEIGHTS.clear()
        SYLLABLE_WEIGHTS.putAll(newWeights)
        totalSyllableWeight.set(newTotal)
        synchronized(splitComboCache) {
            splitComboCache.clear()
            splitCacheTotal = newTotal
        }
    }

    /**
     * 在載入字典時，累計單字的詞頻到對應音節，用於音節排序（執行緒安全）
     */
    fun addSyllableWeight(cleanZhuyin: String, weight: Int) {
        if (cleanZhuyin.isEmpty() || weight <= 0) return
        SYLLABLE_WEIGHTS.merge(cleanZhuyin, weight.toLong()) { old, new -> old + new }
        totalSyllableWeight.addAndGet(weight.toLong())
    }

    fun getSyllableWeight(syl: String): Long {
        return SYLLABLE_WEIGHTS[syl] ?: 100L
    }

    /**
     * 精確匹配：找出鍵序完全吻合的合法音節，並按音節熱度降序排列
     * 例：[1, 9] -> [ㄅㄨ, ㄉㄤ, ㄉㄨ, ㄅㄤ]
     */
    fun getExactSyllables(keys: List<Int>): List<String> {
        val matches = EXACT_KEY_MAP[keys] ?: return emptyList()
        return matches.sortedByDescending { getSyllableWeight(it) }
    }

    /**
     * 前綴匹配：找出鍵序以前綴開頭的合法音節
     * 例：[1] -> [ㄅㄚ, ㄅㄤ, ㄅㄨ, ㄉㄤ, ...]
     */
    fun getPrefixSyllables(keys: List<Int>, maxCount: Int = 12): List<String> {
        if (keys.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        for ((seq, sylList) in EXACT_KEY_MAP) {
            if (seq.size > keys.size && seq.subList(0, keys.size) == keys) {
                results.addAll(sylList)
            }
        }
        return results.distinct().sortedByDescending { getSyllableWeight(it) }.take(maxCount)
    }


    private val splitComboCache = LinkedHashMap<List<Int>, List<String>>()
    private var splitCacheTotal = 0L

    /**
     * 九鍵專用：把按鍵序列切成合法音節，依音節頻率排序（用於字典沒有收錄的詞，如「幫我」ㄅㄤㄨㄛ）。
     *
     * 與 getMultiSyllableCombinations 不同：以真實單字音節頻率計成本（-ln p + 每音節固定成本），
     * 沒有任何字使用的音節不納入，也不再以「長音節加分」硬推。實測正確注音組合出現在前 8 名的機率約 92%，
     * 舊的多音節合成約 2%。
     */
    fun getSyllableSplitCombinations(keys: List<Int>, maxCount: Int = 6): List<String> {
        val n = keys.size
        val total = totalSyllableWeight.get()
        if (n < 4 || n > 8 || total <= 0L) return emptyList()

        synchronized(splitComboCache) {
            // 字典還在載入（權重總和仍在變動）時，之前算出的結果可能不完整，直接捨棄
            if (splitCacheTotal != total) {
                splitComboCache.clear()
                splitCacheTotal = total
            }
            splitComboCache[keys]?.let { return it.take(maxCount) }
        }

        val logTotal = Math.log(total.toDouble())
        val beam = 12
        // dp[i] = 涵蓋 keys[0, i) 的最低成本切分（音節以空白分隔，最後再去掉）
        val dp = Array(n + 1) { ArrayList<Pair<String, Double>>() }
        dp[0].add(Pair("", 0.0))
        for (i in 1..n) {
            val candidates = ArrayList<Pair<String, Double>>()
            for (len in 1..minOf(3, i)) {
                val prevList = dp[i - len]
                if (prevList.isEmpty()) continue
                val sylList = EXACT_KEY_MAP[keys.subList(i - len, i)] ?: continue
                for (syl in sylList) {
                    val weight = SYLLABLE_WEIGHTS[syl] ?: continue
                    if (weight <= 0L) continue
                    val cost = logTotal - Math.log(weight.toDouble()) + SPLIT_SYLLABLE_PENALTY
                    for ((text, score) in prevList) {
                        candidates.add(Pair(if (text.isEmpty()) syl else "$text $syl", score + cost))
                    }
                }
            }
            candidates.sortBy { it.second }
            val seen = HashSet<String>()
            for (c in candidates) {
                if (seen.add(c.first)) {
                    dp[i].add(c)
                    if (dp[i].size >= beam) break
                }
            }
        }
        val results = ArrayList<String>()
        val seenJoined = HashSet<String>()
        for ((text, _) in dp[n]) {
            val joined = text.replace(" ", "")
            if (seenJoined.add(joined)) results.add(joined)
        }

        synchronized(splitComboCache) {
            if (splitComboCache.size > 128) splitComboCache.clear()
            splitComboCache[keys] = results
        }
        return results.take(maxCount)
    }

    /** 音節切分的每音節固定成本：偏好用較少、較長的音節切分 */
    private const val SPLIT_SYLLABLE_PENALTY = 4.0

    val VALID_SYLLABLES_SET: Set<String> by lazy { VALID_SYLLABLES.toHashSet() }

    /**
     * 依據教育部標準 429 個合法音節，將詞彙的無聲調注音字串切分為指定字數的合法音節
     * 例："ㄊㄞㄨㄢ", 2 -> ["ㄊㄞ", "ㄨㄢ"]
     */
    fun splitIntoSyllables(cleanZhuyin: String, wordLength: Int): List<String>? {
        val len = cleanZhuyin.length
        fun backtrack(idx: Int, count: Int): MutableList<String>? {
            if (idx == len && count == wordLength) return ArrayList(wordLength)
            if (idx >= len || count >= wordLength) return null
            for (subLen in 3 downTo 1) {
                if (idx + subLen <= len) {
                    val sub = cleanZhuyin.substring(idx, idx + subLen)
                    if (VALID_SYLLABLES_SET.contains(sub)) {
                        val rem = backtrack(idx + subLen, count + 1)
                        if (rem != null) {
                            rem.add(0, sub)
                            return rem
                        }
                    }
                }
            }
            return null
        }
        return backtrack(0, 0)
    }

    /**
     * 檢查注音字串是否為合法音節（無聲調）
     */
    fun isValidSyllable(cleanZhuyin: String): Boolean {
        return cleanZhuyin in VALID_SYLLABLES_SET
    }

    /**
     * 將包含聲調或空格的完整注音字串切分為指定字數的合法音節（保留各字聲調）
     * 例："ㄉㄚˋㄐㄧㄚ", 2 -> ["ㄉㄚˋ", "ㄐㄧㄚ"]
     * 例："ㄉㄨˊ", 1 -> ["ㄉㄨˊ"]
     * 例："ㄐㄧㄣ ㄊㄧㄢ", 2 -> ["ㄐㄧㄣ", "ㄊㄧㄢ"]
     */
    fun splitFullZhuyin(rawZhuyin: String, targetLength: Int): List<String>? {
        if (rawZhuyin.isEmpty() || targetLength <= 0) return null
        if (rawZhuyin.contains(" ")) {
            val parts = rawZhuyin.split(" ").filter { it.isNotEmpty() }
            if (parts.size == targetLength) return parts
        }
        if (targetLength == 1) {
            val clean = rawZhuyin.filter { it !in "ˇˋˊ˙" }
            if (isValidSyllable(clean)) {
                return listOf(rawZhuyin)
            }
        }
        val n = rawZhuyin.length
        val tones = setOf('ˇ', 'ˋ', 'ˊ', '˙')
        fun backtrack(idx: Int, count: Int): MutableList<String>? {
            if (idx == n && count == targetLength) return ArrayList(targetLength)
            if (idx >= n || count >= targetLength) return null
            for (subLen in 3 downTo 1) {
                if (idx + subLen <= n) {
                    val sylPart = rawZhuyin.substring(idx, idx + subLen)
                    if (sylPart.any { it in tones }) continue
                    if (VALID_SYLLABLES_SET.contains(sylPart)) {
                        val nextIdx = idx + subLen
                        val hasTone = nextIdx < n && rawZhuyin[nextIdx] in tones
                        val step = subLen + if (hasTone) 1 else 0
                        val rem = backtrack(idx + step, count + 1)
                        if (rem != null) {
                            rem.add(0, rawZhuyin.substring(idx, idx + step))
                            return rem
                        }
                    }
                }
            }
            return null
        }
        val result = backtrack(0, 0)
        if (result != null) return result

        // 保底：若合法音節集合未覆蓋特殊音（如 ㄆㄨㄥ 等），嘗試以 cleanZhuyin 切分後映射長度
        val clean = rawZhuyin.filter { it !in "ˇˋˊ˙" }
        val cleanSplit = splitIntoSyllables(clean, targetLength)
        if (cleanSplit != null && cleanSplit.size == targetLength) {
            val out = mutableListOf<String>()
            var rawIdx = 0
            for (s in cleanSplit) {
                val start = rawIdx
                rawIdx += s.length
                if (rawIdx < n && rawZhuyin[rawIdx] in tones) {
                    rawIdx++
                }
                out.add(rawZhuyin.substring(start, rawIdx))
            }
            return out
        }
        return null
    }

    /**
     * 自動將連續輸入的完整注音字串（可含聲調或無聲調）切分為合法音節列表，不需預先指定字數。
     * 採用動態規劃（偏好較少音節數，即最長匹配優先，且聲調符號必須緊接在合法音節後）。
     * 例："ㄉㄚˋㄐㄧㄚㄏㄠˇ" -> ["ㄉㄚˋ", "ㄐㄧㄚ", "ㄏㄠˇ"]
     * 例："ㄐㄧㄣㄊㄧㄢ" -> ["ㄐㄧㄣ", "ㄊㄧㄢ"]
     */
    fun splitFullZhuyinAuto(rawZhuyin: String): List<String>? {
        if (rawZhuyin.isBlank()) return null
        if (rawZhuyin.contains(" ")) {
            val parts = rawZhuyin.split(" ").filter { it.isNotEmpty() }
            if (parts.all { isValidSyllable(it.filter { c -> c !in "ˇˋˊ˙" }) }) {
                return parts
            }
        }
        val n = rawZhuyin.length
        val tones = "ˇˋˊ˙"
        // dp[i] 儲存切分到 index i 的最佳音節列表（音節數越少越優先，同音節數時權重越高越優先）
        val dp = arrayOfNulls<Pair<List<String>, Double>>(n + 1)
        dp[0] = Pair(emptyList(), 0.0)

        for (i in 0 until n) {
            val current = dp[i] ?: continue
            for (subLen in 3 downTo 1) {
                if (i + subLen > n) continue
                val sylPart = rawZhuyin.substring(i, i + subLen)
                if (sylPart.any { it in tones }) continue
                if (!VALID_SYLLABLES_SET.contains(sylPart)) continue

                val nextIdx = i + subLen
                val hasTone = nextIdx < n && rawZhuyin[nextIdx] in tones
                val endIdx = nextIdx + (if (hasTone) 1 else 0)
                val fullSyl = rawZhuyin.substring(i, endIdx)

                val sylScore = Math.log(maxOf(getSyllableWeight(sylPart).toDouble(), 10.0)) - 12.0
                val newScore = current.second + sylScore
                val existing = dp[endIdx]
                if (existing == null || newScore > existing.second) {
                    dp[endIdx] = Pair(current.first + fullSyl, newScore)
                }
            }
        }
        return dp[n]?.first
    }
}
