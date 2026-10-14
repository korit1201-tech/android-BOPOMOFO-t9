package com.bopomofo.t9ime.engine

/**
 * 詞庫條目
 */
data class DictEntry(
    val word: String,
    val zhuyin: String,
    val weight: Int,
    val isTolerant: Boolean = false,
    val consumedKeys: Int = 0
) {
    /** 預先計算去除聲調與空白的純注音字串，避免在查詢與 DP 迴圈中重複產生垃圾物件 */
    val cleanZhuyin: String = if (zhuyin.isEmpty()) "" else buildString(zhuyin.length) {
        for (c in zhuyin) {
            if (c != 'ˇ' && c != 'ˋ' && c != 'ˊ' && c != '˙' && c != ' ') {
                append(c)
            }
        }
    }
}

val UserWordEntry.cleanZhuyin: String
    get() = if (zhuyin.isEmpty()) "" else buildString(zhuyin.length) {
        for (c in zhuyin) {
            if (c != 'ˇ' && c != 'ˋ' && c != 'ˊ' && c != '˙' && c != ' ') {
                append(c)
            }
        }
    }

/**
 * 前綴樹節點
 */
class TrieNode {
    val children = mutableMapOf<Int, TrieNode>()
    // LinkedHashMap<word, DictEntry>：插入時去重，相同詞取 weight 最大者，
    // 避免 searchExact 每次執行昂貴的 distinctBy 操作
    val exactEntries = LinkedHashMap<String, DictEntry>()

    /** 依權重排序的前 [TOP_ENTRIES_SIZE] 名詞條快取（九鍵斷詞使用），插入新詞時失效 */
    @Volatile
    var topEntriesCache: List<DictEntry>? = null

    /**
     * 此節點權重最高的幾個詞條。一個節點常有上百個詞條（九鍵一鍵對應多個注音），
     * 斷詞只需要其中最常用的幾個，避免每次按鍵都對所有詞條計算成本。
     */
    fun topEntries(): List<DictEntry> {
        val cached = topEntriesCache
        if (cached != null) return cached
        val computed = if (exactEntries.size <= TOP_ENTRIES_SIZE) {
            exactEntries.values.toList()
        } else {
            exactEntries.values.sortedByDescending { it.weight }.take(TOP_ENTRIES_SIZE)
        }
        topEntriesCache = computed
        return computed
    }

    companion object {
        const val TOP_ENTRIES_SIZE = 16
    }
}

class TrieDictionary {
    val root = TrieNode()

    private companion object {
        /** searchPrefixBest 每個子節點只取權重最高的前幾名 */
        const val PREFIX_PER_NODE_LIMIT = 6
    }

    fun insert(entry: DictEntry) {
        val seqNoTone = KeyMapping.getSequence(entry.zhuyin, ignoreTones = true)
        if (seqNoTone.isNotEmpty()) {
            insertSequence(seqNoTone, entry)
        }

        val seqWithTone = KeyMapping.getSequence(entry.zhuyin, ignoreTones = false)
        if (seqWithTone.isNotEmpty() && seqWithTone != seqNoTone) {
            insertSequence(seqWithTone, entry)
        }

        // 僅對常用詞彙（詞長 <= 3 且 weight >= 60）進行跨鍵位容錯索引（避免 18 萬詞全面展開造成記憶體爆炸與 OOM）
        if (entry.word.length <= 3 && entry.weight >= 60 && !entry.isTolerant) {
            val altSeqs = KeyMapping.getCrossKeyTolerantSequences(entry.zhuyin)
            for (altSeq in altSeqs) {
                if (altSeq != seqNoTone && altSeq != seqWithTone) {
                    val adjustedEntry = DictEntry(
                        entry.word,
                        entry.zhuyin,
                        (entry.weight * 0.35).toInt(),
                        isTolerant = true
                    )
                    insertSequence(altSeq, adjustedEntry)
                }
            }
        }
    }

    private fun insertSequence(seq: List<Int>, entry: DictEntry) {
        var curr = root
        for (k in seq) {
            curr = curr.children.getOrPut(k) { TrieNode() }
        }
        // 相同詞取 weight 最大者，避免 searchExact 執行 distinctBy
        val existing = curr.exactEntries[entry.word]
        if (existing == null || entry.weight > existing.weight) {
            curr.exactEntries[entry.word] = entry
            curr.topEntriesCache = null
        }
    }

    /**
     * 高效檢索：直接獲取當前按鍵節點，並搜集候選詞（毫秒級完成）
     */
    fun searchNode(sequence: List<Int>): TrieNode? {
        var curr = root
        for (k in sequence) {
            val next = curr.children[k] ?: return null
            curr = next
        }
        return curr
    }

    fun search(sequence: List<Int>, includeTolerant: Boolean = true): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()

        val exactList = searchExact(sequence, includeTolerant)
        val prefixList = searchPrefix(sequence, maxDepth = 3, includeTolerant = includeTolerant)

        val combined = mutableListOf<DictEntry>()
        combined.addAll(exactList)
        combined.addAll(prefixList)
        return combined
    }

    fun searchExact(sequence: List<Int>, includeTolerant: Boolean = true): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()
        val targetNode = searchNode(sequence) ?: return emptyList()
        val entries = if (includeTolerant) {
            targetNode.exactEntries.values
        } else {
            targetNode.exactEntries.values.filter { !it.isTolerant }
        }
        return entries
            .sortedWith(
                compareByDescending<DictEntry> { !it.isTolerant }
                    .thenByDescending { it.weight }
            )
    }

    fun searchPrefix(sequence: List<Int>, maxDepth: Int = 3, includeTolerant: Boolean = true): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()
        val targetNode = searchNode(sequence) ?: return emptyList()
        val exactWords = targetNode.exactEntries.keys
        val prefixList = mutableListOf<DictEntry>()
        collectPrefix(targetNode, prefixList, 0, maxDepth, includeTolerant)
        return prefixList
            .distinctBy { it.word }
            .filter { it.word !in exactWords && (includeTolerant || !it.isTolerant) }
            .sortedWith(
                compareByDescending<DictEntry> { !it.isTolerant }
                    .thenByDescending { it.weight }
            )
    }

    /**
     * 延伸詞：比目前輸入更長的詞。extraKeys 為尚未輸入的按鍵數（含聲調鍵）。
     */
    class Extension(val entry: DictEntry, val extraKeys: Int)

    /**
     * 九鍵專用前綴查詢：走訪整個子樹（深度 <= maxDepth），依權重取最佳 limit 個延伸詞。
     * 與 searchPrefix 不同：不以走訪順序截斷，先插入的分支不會佔滿名額、擠掉更常用的詞。
     */
    fun searchPrefixBest(
        sequence: List<Int>,
        maxDepth: Int,
        includeTolerant: Boolean,
        limit: Int
    ): List<DictEntry> {
        if (sequence.isEmpty()) return emptyList()
        val targetNode = searchNode(sequence) ?: return emptyList()
        return collectExtensions(
            targetNode, maxDepth, includeTolerant, targetNode.exactEntries.keys, PREFIX_PER_NODE_LIMIT
        )
            .values
            .map { it.entry }
            .sortedWith(
                compareByDescending<DictEntry> { !it.isTolerant }
                    .thenByDescending { it.weight }
            )
            .take(limit)
    }

    /**
     * 蒐集 node 之下（相對層級 1..maxDepth+1）的延伸詞；同一個詞保留權重最高的詞條與最少缺鍵數。
     * perNodeLimit：每個子節點只取權重最高的前幾名（子樹很大時避免掃描所有詞條）。
     */
    fun collectExtensions(
        node: TrieNode,
        maxDepth: Int,
        includeTolerant: Boolean,
        excludeWords: Set<String>? = null,
        perNodeLimit: Int = Int.MAX_VALUE
    ): Map<String, Extension> {
        val result = HashMap<String, Extension>()
        collectExtensionsRec(node, 1, maxDepth + 1, includeTolerant, excludeWords, perNodeLimit, result)
        return result
    }

    private fun collectExtensionsRec(
        node: TrieNode,
        level: Int,
        maxLevel: Int,
        includeTolerant: Boolean,
        excludeWords: Set<String>?,
        perNodeLimit: Int,
        result: MutableMap<String, Extension>
    ) {
        if (level > maxLevel) return
        for (child in node.children.values) {
            val entries: Collection<DictEntry> =
                if (perNodeLimit == Int.MAX_VALUE) child.exactEntries.values
                else child.topEntries().let { if (it.size > perNodeLimit) it.subList(0, perNodeLimit) else it }
            for (e in entries) {
                if (!includeTolerant && e.isTolerant) continue
                if (excludeWords != null && e.word in excludeWords) continue
                val old = result[e.word]
                if (old == null) {
                    result[e.word] = Extension(e, level)
                } else {
                    val bestEntry = if (e.weight > old.entry.weight) e else old.entry
                    val bestExtra = minOf(level, old.extraKeys)
                    if (bestEntry !== old.entry || bestExtra != old.extraKeys) {
                        result[e.word] = Extension(bestEntry, bestExtra)
                    }
                }
            }
            collectExtensionsRec(child, level + 1, maxLevel, includeTolerant, excludeWords, perNodeLimit, result)
        }
    }

    private fun collectPrefix(
        node: TrieNode,
        results: MutableList<DictEntry>,
        depth: Int,
        maxDepth: Int,
        includeTolerant: Boolean
    ) {
        if (depth > maxDepth || results.size >= 50) return
        for ((_, child) in node.children) {
            val remaining = 50 - results.size
            if (remaining <= 0) return
            val validEntries = if (includeTolerant) {
                child.exactEntries.values
            } else {
                child.exactEntries.values.filter { !it.isTolerant }
            }
            results.addAll(validEntries.take(remaining))
            collectPrefix(child, results, depth + 1, maxDepth, includeTolerant)
        }
    }
}
