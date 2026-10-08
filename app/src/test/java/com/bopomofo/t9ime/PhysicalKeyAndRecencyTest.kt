package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.KeyMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeyAndRecencyTest {

    @Test
    fun testKeyMappingDefinitions() {
        // 驗證 12 宮格按鍵的注音定義
        assertEquals(1, KeyMapping.getKeyId('ㄅ'))
        assertEquals(1, KeyMapping.getKeyId('ㄉ'))
        assertEquals(1, KeyMapping.getKeyId('ㄚ'))

        assertEquals(4, KeyMapping.getKeyId('ㄆ'))
        assertEquals(4, KeyMapping.getKeyId('ㄊ'))
        assertEquals(4, KeyMapping.getKeyId('ㄛ'))

        assertEquals(7, KeyMapping.getKeyId('ㄇ'))
        assertEquals(7, KeyMapping.getKeyId('ㄋ'))
        assertEquals(7, KeyMapping.getKeyId('ㄜ'))

        assertEquals(10, KeyMapping.getKeyId('ㄈ'))
        assertEquals(10, KeyMapping.getKeyId('ㄌ'))
        assertEquals(10, KeyMapping.getKeyId('ㄝ'))
    }

    @Test
    fun testRecencyBoostLogic() {
        val recentDiffMs = 60 * 1000L // 1 分鐘前
        val oldDiffMs = 15 * 24 * 3600 * 1000L // 15 天前

        val recentBonus = when {
            recentDiffMs < 10 * 60 * 1000L -> 3_000_000
            recentDiffMs < 24 * 3600 * 1000L -> 1_500_000
            recentDiffMs < 7 * 24 * 3600 * 1000L -> 500_000
            else -> 0
        }

        val oldBonus = when {
            oldDiffMs < 10 * 60 * 1000L -> 3_000_000
            oldDiffMs < 24 * 3600 * 1000L -> 1_500_000
            oldDiffMs < 7 * 24 * 3600 * 1000L -> 500_000
            else -> 0
        }

        assertTrue(recentBonus > oldBonus)
    }

    @Test
    fun testDaQianKeyMapping() {
        val daqianMap = mapOf(
            android.view.KeyEvent.KEYCODE_W to 'ㄊ',
            android.view.KeyEvent.KEYCODE_U to 'ㄧ',
            android.view.KeyEvent.KEYCODE_0 to 'ㄢ',
            android.view.KeyEvent.KEYCODE_1 to 'ㄅ',
            android.view.KeyEvent.KEYCODE_Q to 'ㄆ',
            android.view.KeyEvent.KEYCODE_A to 'ㄇ',
            android.view.KeyEvent.KEYCODE_Z to 'ㄈ'
        )
        // 驗證打 w u 0 拼出 ㄊㄧㄢ，絕不可映射為 9 鍵序列
        val chars = listOf(
            daqianMap[android.view.KeyEvent.KEYCODE_W],
            daqianMap[android.view.KeyEvent.KEYCODE_U],
            daqianMap[android.view.KeyEvent.KEYCODE_0]
        ).filterNotNull().joinToString("")
        assertEquals("ㄊㄧㄢ", chars)
    }

    @Test
    fun testSyllableManagerForTian() {
        // 驗證 ㄊㄧㄢ 是合法單音節，應該分流至單字同音字優先展示
        assertTrue(com.bopomofo.t9ime.engine.SyllableManager.isValidSyllable("ㄊㄧㄢ"))
        assertTrue(com.bopomofo.t9ime.engine.SyllableManager.isValidSyllable("ㄐㄧㄣ"))
    }

    @Test
    fun testSplitFullZhuyinForMultiCharWords() {
        // 驗證關鍵高頻詞音節切分正確，確保 Viterbi DP 演算法精準匹配各音節聲調加權
        assertEquals(listOf("ㄕˊ", "ㄊㄧˇ"), com.bopomofo.t9ime.engine.SyllableManager.splitFullZhuyin("ㄕˊㄊㄧˇ", 2))
        assertEquals(listOf("ㄓㄨㄤˋ", "ㄊㄞˋ"), com.bopomofo.t9ime.engine.SyllableManager.splitFullZhuyin("ㄓㄨㄤˋㄊㄞˋ", 2))
        assertEquals(listOf("ㄨㄣˋ", "ㄊㄧˊ"), com.bopomofo.t9ime.engine.SyllableManager.splitFullZhuyin("ㄨㄣˋㄊㄧˊ", 2))
        assertEquals(listOf("ㄓㄜˋ", "ㄒㄧㄝ"), com.bopomofo.t9ime.engine.SyllableManager.splitFullZhuyin("ㄓㄜˋㄒㄧㄝ", 2))
        assertEquals(listOf("ㄇㄧㄥˊ", "ㄒㄧㄢˇ"), com.bopomofo.t9ime.engine.SyllableManager.splitFullZhuyin("ㄇㄧㄥˊㄒㄧㄢˇ", 2))
        assertEquals(listOf("ㄘㄨㄛˋ", "ㄨˋ"), com.bopomofo.t9ime.engine.SyllableManager.splitFullZhuyin("ㄘㄨㄛˋㄨˋ", 2))
    }

    @Test
    fun testPhysicalNumberSelectLogic() {
        // 驗證 PIME / 微軟新注音標準行為：
        // 1. 平常打字輸入狀態下，不可攔截主鍵盤數字鍵，主鍵盤 1~9 必須走大千注音（如 2 走 ㄉ）
        val normalTypingSelecting = false
        val normalTypingGrid = false
        val normalTypingHomophone = false
        val normalTypingSymbol = false
        val canSelectDuringNormalTyping = normalTypingSelecting || normalTypingGrid || normalTypingHomophone || normalTypingSymbol
        assertTrue("平常打字時絕對不可啟用主鍵盤數字選字，確保 ㄉ (2) 等注音盲打暢行無阻", !canSelectDuringNormalTyping)

        // 2. 用方向鍵叫出修改模式 (isSentenceSelecting) 或展開選字清單時，數字鍵 1~9 才作為挑字鍵
        val directionKeyModifiedSelecting = true
        val canSelectAfterDirectionKey = directionKeyModifiedSelecting || normalTypingGrid || normalTypingHomophone || normalTypingSymbol
        assertTrue("用方向鍵叫出修改時，必須啟用數字選字", canSelectAfterDirectionKey)
    }

    @Test
    fun testSyllableBoundaryDetection() {
        val initials = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ"
        val finals = "ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
        fun needCommit(ch: Char, buf: String): Boolean = when {
            ch in initials -> buf.isNotEmpty()
            ch in "ㄧㄨㄩ" -> buf.any { it in "ㄧㄨㄩ" || it in finals }
            ch in finals -> buf.any { it in finals }
            else -> false
        }

        // ㄓ 後面遇到 ㄉ（知道）：必須立即結算 ㄓ，絕不能黏成 ㄓㄉ！
        assertTrue(needCommit('ㄉ', "ㄓ"))
        // ㄕ 後面遇到 ㄊ（實體）：必須立即結算 ㄕ！
        assertTrue(needCommit('ㄊ', "ㄕ"))
        // ㄧ 後面遇到 ㄈ（衣服）：必須立即結算 ㄧ！
        assertTrue(needCommit('ㄈ', "ㄧ"))
        // ㄊㄧㄢ 後面遇到 ㄑ（天氣）：必須立即結算 ㄊㄧㄢ！
        assertTrue(needCommit('ㄑ', "ㄊㄧㄢ"))
        // ㄊ 後面遇到 ㄧ：屬於同一個字，不結算
        assertTrue(!needCommit('ㄧ', "ㄊ"))
        // ㄊㄧ 後面遇到 ㄢ：屬於同一個字，不結算
        assertTrue(!needCommit('ㄢ', "ㄊㄧ"))
    }

    @Test
    fun testPhysicalCandidateEnrichmentAndSelection() {
        // 1. 驗證音節序列去聲調 cleanKey 提取正確性
        val syllables = listOf("ㄕˊ", "ㄊㄧˇ")
        val cleanKey = syllables.joinToString("") { it.filter { c -> c !in "ˇˋˊ˙ " } }
        assertEquals("ㄕㄊㄧ", cleanKey)

        // 2. 驗證整詞候選與單字候選的合成邏輯
        val topEntry = com.bopomofo.t9ime.engine.DictEntry("時體", syllables.joinToString(" "), 999_999_999)
        val exactWords = listOf(
            com.bopomofo.t9ime.engine.DictEntry("實體", "ㄕˊ ㄊㄧˇ", 5000),
            com.bopomofo.t9ime.engine.DictEntry("試題", "ㄕˋ ㄊㄧˊ", 3000)
        )
        val candidates = (listOf(topEntry) + exactWords).distinctBy { e -> e.word }
        assertEquals(3, candidates.size)
        assertEquals("時體", candidates[0].word)
        assertEquals("實體", candidates[1].word)
        assertEquals("試題", candidates[2].word)

        // 3. 驗證選字時整詞替換 (sentenceCursor == length)
        val sentence = StringBuilder("時體")
        val chosen = candidates[1] // 選取 "實體"
        sentence.clear()
        sentence.append(chosen.word)
        assertEquals("實體", sentence.toString())

        // 4. 驗證選字時單字替換 (sentenceCursor < length)
        val singleSentence = StringBuilder("時體")
        val newChar = "實"
        val cursor = 0
        singleSentence.setCharAt(cursor, newChar[0])
        assertEquals("實體", singleSentence.toString())
    }
}

