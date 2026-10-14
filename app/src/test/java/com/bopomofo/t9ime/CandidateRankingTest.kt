package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.TrieDictionary
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import com.bopomofo.t9ime.engine.SyllableManager
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

    @Test
    fun testBigramContextBoostInT9() {
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest()

        val prevWord = "高畫質"
        val targetWord = "影像"
        val competingWord = "影響"

        // 1. 驗證自學習與預設 Bigram 在 getNextWordPredictions 中能優先排序
        repeat(3) {
            engine.learnBigram(prevWord, targetWord)
        }
        val nextPredictions = engine.getNextWordPredictions(prevWord).map { it.word }
        assertTrue("$targetWord 應在 getNextWordPredictions(\"$prevWord\") 首選清單中", nextPredictions.contains(targetWord))
        assertEquals(targetWord, nextPredictions.first())

        // 2. 驗證設定 currentContextWord 後，接續詞在 T9 候選中能透過 Bigram 加成超越原本高頻的同音字詞
        engine.currentContextWord = prevWord
        // 「影像」與「影響」注音均為 ㄧ ㄥ ㄒ ㄧ ㄤ -> 鍵位 6, 12, 8, 6, 9
        val seqKeys = KeyMapping.getSequence("ㄧㄥㄒㄧㄤ", ignoreTones = true)
        for (k in seqKeys) {
            engine.pressKey(k)
        }
        val candidates = engine.getCandidates()
        assertTrue("T9 候選清單不得為空", candidates.isNotEmpty())
        assertEquals("設定語境「$prevWord」後，同按鍵序列首選應由「$competingWord」逆轉為「$targetWord」", targetWord, candidates.first().word)
    }

    @Test
    fun testSplitFullZhuyinAutoAndLongSentence() {
        // 1. 驗證 SyllableManager.splitFullZhuyinAuto 自動切分音節
        val rawZhuyin = "ㄉㄚˋㄐㄧㄚㄏㄠˇ"
        val syllables = SyllableManager.splitFullZhuyinAuto(rawZhuyin)
        assertEquals(listOf("ㄉㄚˋ", "ㄐㄧㄚ", "ㄏㄠˇ"), syllables)

        // 2. 驗證 engine.searchFullZhuyin 能正確透過 Viterbi 組出「大家好」
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest()
        val results = engine.searchFullZhuyin(rawZhuyin)
        assertTrue("searchFullZhuyin 搜尋結果不得為空", results.isNotEmpty())
        val topSentence = results.first().word
        assertEquals("全注音連打「ㄉㄚˋㄐㄧㄚㄏㄠˇ」應正確組出「大家好」", "大家好", topSentence)
    }

    @Test
    fun testFullKeyboardAndPhysicalSharedViterbiWithBigram() {
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest()

        // 1. 驗證實體鍵盤/全鍵盤共用的 findBestSentenceFromSyllables 支援聲母簡拼 (如 ["ㄐ", "ㄊ"] -> 今天)
        val abbrevSentence = engine.findBestSentenceFromSyllables(listOf("ㄐ", "ㄊ"))
        assertEquals("今天", abbrevSentence.joinToString(""))

        // 2. 驗證 findBestSentenceFromSyllables 支援跨詞 Bigram 語境逆轉同音詞
        repeat(4) {
            engine.learnBigram("高畫質", "影像")
        }
        val syllables = listOf("ㄍㄠ", "ㄏㄨㄚˋ", "ㄓˊ", "ㄧㄥˇ", "ㄒㄧㄤˋ")
        val sentenceWords = engine.findBestSentenceFromSyllables(syllables)
        assertEquals("高畫質影像", sentenceWords.joinToString(""))
    }
}
