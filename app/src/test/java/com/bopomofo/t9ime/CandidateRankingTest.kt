package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.TrieDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRankingTest {

    private fun getEffectiveWeight(
        entry: DictEntry,
        inputKeyCount: Int,
        isExact: Boolean,
        prevTopWord: String? = null
    ): Double {
        val rawWeight = entry.weight.toDouble()
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

    @Test
    fun testShuoRanksAboveSuoYi() {
        val entryShuo = DictEntry("說", "ㄕㄨㄛ", 43458)
        val entrySuoYi = DictEntry("所以", "ㄙㄨㄛˇ ㄧˇ", 27522)

        // 使用者輸入 [9, 9, 4] (3 個按鍵)
        val inputKeys = listOf(9, 9, 4)
        val phonemeKeyLen = inputKeys.size

        // 說 鍵長為 3，此時為完全匹配 (isExact = true)
        val scoreShuo = getEffectiveWeight(entryShuo, phonemeKeyLen, isExact = true)
        // 所以 鍵長為 4，此時為前綴預測 (isExact = false, distance = 1)
        val scoreSuoYi = getEffectiveWeight(entrySuoYi, phonemeKeyLen, isExact = false)

        assertTrue("說 ($scoreShuo) 必須高於 所以 ($scoreSuoYi)", scoreShuo > scoreSuoYi)
    }

    @Test
    fun testT9KeySequences() {
        // 驗證 ㄕㄨㄛ (說) 的按鍵序列
        val shuoSeq = KeyMapping.getSequence("ㄕㄨㄛ", ignoreTones = true)
        assertEquals(listOf(9, 9, 4), shuoSeq)

        // 驗證 ㄙㄨㄛˇㄧˇ (所以) 的按鍵序列
        val suoYiSeq = KeyMapping.getSequence("ㄙㄨㄛˇ ㄧˇ", ignoreTones = true)
        assertEquals(listOf(9, 9, 4, 6), suoYiSeq)

        // 驗證 你到底 的按鍵序列
        val niSeq = KeyMapping.getSequence("ㄋㄧˇ", ignoreTones = true)
        val daoSeq = KeyMapping.getSequence("ㄉㄠˋ", ignoreTones = true)
        val diSeq = KeyMapping.getSequence("ㄉㄧˇ", ignoreTones = true)
        assertEquals(listOf(7, 6), niSeq)
        assertEquals(listOf(1, 8), daoSeq)
        assertEquals(listOf(1, 6), diSeq)
    }
}
