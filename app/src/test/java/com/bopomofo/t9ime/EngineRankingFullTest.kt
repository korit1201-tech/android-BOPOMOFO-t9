package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineRankingFullTest {
    @Test
    fun testFullCandidatesWithPopulatedUserDict() {
        val dictFile = File("src/main/assets/dict_tw.txt")
        val trie = TrieDictionary()
        dictFile.forEachLine { line ->
            val p = line.split("\t")
            if (p.size >= 3) {
                trie.insert(DictEntry(p[0], p[1], p[2].toIntOrNull() ?: 1))
            }
        }

        // Add vital spoken expressions:
        for ((w, zy, wt) in listOf(
            Triple("怎麼了", "ㄗㄣˇㄇㄜ˙ㄌㄜ˙", 45000),
            Triple("怎麼了", "ㄗㄜˇㄇㄜ˙ㄌㄜ˙", 45000),
            Triple("不開心", "ㄅㄨˋㄎㄞㄒㄧㄣ", 35000),
            Triple("你到底", "ㄋㄧˇㄉㄠˋㄉㄧˇ", 35000),
            Triple("說", "ㄕㄨㄛ", 120000)
        )) {
            trie.insert(DictEntry(w, zy, wt))
        }

        // Simulate userDict having common words:
        val userBoostMap = mapOf(
            "所以" to 4000000,
            "字" to 4000000,
            "一" to 4000000,
            "那" to 4000000,
            "了" to 4000000,
            "很" to 4000000,
            "不" to 4000000,
            "開" to 4000000
        )

        fun getEffectiveWeight(entry: DictEntry, inputKeyCount: Int, isExact: Boolean, prevTopWord: String? = null): Double {
            val userBoost = userBoostMap[entry.word] ?: 0
            if (isExact && userBoost > 0) {
                return 1_000_000_000.0 + userBoost
            }
            var rawWeight = entry.weight.toDouble()
            if (!isExact && userBoost > 0) {
                rawWeight *= (1.0 + minOf(userBoost, 2_000_000) / 1_000_000.0)
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
            return rawWeight * lengthMultiplier * distanceMultiplier * continuityMultiplier * tolerantMultiplier
        }

        fun findBestSentenceSegments(keys: List<Int>): List<Pair<DictEntry, Int>>? {
            val n = keys.size
            if (n < 4) return null
            val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
            dp[0] = 0.0
            val bestSplit = arrayOfNulls<Pair<Int, DictEntry>>(n + 1)
            val logTotal = 17.91
            val wordBonus = 3.5

            for (i in 1..n) {
                val maxLen = minOf(12, i)
                for (len in 1..maxLen) {
                    val j = i - len
                    if (dp[j] != Double.NEGATIVE_INFINITY) {
                        val subKeys = keys.subList(j, i)
                        val node = trie.searchNode(subKeys)
                        if (node != null && node.exactEntries.isNotEmpty()) {
                            for (entry in node.exactEntries.values) {
                                if (entry.isTolerant) continue
                                val boost = userBoostMap[entry.word] ?: 0
                                val effectiveWeight = if (entry.word.length == 1) {
                                    minOf(entry.weight + minOf(boost, 50_000), 120_000)
                                } else {
                                    entry.weight + minOf(boost, 200_000)
                                }
                                val logProb = Math.log(maxOf(effectiveWeight.toDouble(), 1.0)) - logTotal
                                val bonus = (entry.word.length - 1) * wordBonus
                                val score = dp[j] + logProb + bonus
                                if (score > dp[i]) {
                                    dp[i] = score
                                    bestSplit[i] = Pair(j, entry)
                                }
                            }
                        }
                    }
                }
            }
            if (dp[n] == Double.NEGATIVE_INFINITY) return null
            val segments = mutableListOf<Pair<DictEntry, Int>>()
            var curr = n
            while (curr > 0) {
                val split = bestSplit[curr] ?: return null
                segments.add(Pair(split.second, curr - split.first))
                curr = split.first
            }
            segments.reverse()
            return segments
        }

        fun findBestSentence(keys: List<Int>): DictEntry? {
            val segments = findBestSentenceSegments(keys) ?: return null
            if (segments.size > 1) {
                val combinedWord = segments.joinToString("") { it.first.word }
                val combinedZhuyin = segments.joinToString(" ") { it.first.zhuyin }
                val avgLog = segments.map { Math.log(maxOf(it.first.weight.toDouble(), 1.0)) }.average()
                val compositeWeight = minOf(Math.exp(avgLog).toInt(), 100_000_000)
                return DictEntry(combinedWord, combinedZhuyin, compositeWeight)
            }
            return null
        }

        fun getCandidates(keys: List<Int>): List<String> {
            val exactResults = trie.searchExact(keys)
            val prefixResults = trie.searchPrefix(keys, maxDepth = 3)
            val pool = mutableMapOf<String, Pair<DictEntry, Double>>()

            for (e in exactResults) {
                val score = getEffectiveWeight(e, keys.size, isExact = true)
                pool[e.word] = Pair(e, score)
            }

            if (keys.size >= 4) {
                val sentenceEntry = findBestSentence(keys)
                if (sentenceEntry != null) {
                    val sentenceScore = sentenceEntry.weight.toDouble() * 2.5 * 2.0
                    val existing = pool[sentenceEntry.word]
                    if (existing == null || sentenceScore > existing.second) {
                        pool[sentenceEntry.word] = Pair(sentenceEntry, sentenceScore)
                    }
                }
            }

            for (p in prefixResults) {
                val score = getEffectiveWeight(p, keys.size, isExact = false)
                val existing = pool[p.word]
                if (existing == null || score > existing.second) {
                    pool[p.word] = Pair(p, score)
                }
            }

            return pool.values.sortedWith(
                compareByDescending<Pair<DictEntry, Double>> { it.second >= 1_000_000_000.0 }
                    .thenByDescending { !it.first.isTolerant }
                    .thenByDescending { it.second }
            ).map { it.first.word }
        }

        val shuoTop = getCandidates(listOf(9, 9, 4)).first()
        println("Top candidate for [9, 9, 4]: $shuoTop")
        assertEquals("說", shuoTop)

        val buKaiXinTop = getCandidates(listOf(1, 9, 5, 2, 8, 6, 6)).first()
        println("Top candidate for [1, 9, 5, 2, 8, 6, 6]: $buKaiXinTop")
        assertEquals("不開心", buKaiXinTop)

        val zenMeLeTop = getCandidates(listOf(3, 6, 7, 7, 10, 7)).first()
        println("Top candidate for [3, 6, 7, 7, 10, 7]: $zenMeLeTop")
        assertEquals("怎麼了", zenMeLeTop)

        val niDaoDiTop = getCandidates(listOf(7, 6, 1, 8, 1, 6)).first()
        println("Top candidate for [7, 6, 1, 8, 1, 6]: $niDaoDiTop")
        assertEquals("你到底", niDaoDiTop)
    }
}
