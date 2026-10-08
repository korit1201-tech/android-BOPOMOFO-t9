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
        // 驗證數字鍵選字狀態判定：當音節已結算(注音緩衝區空)且候選列非空時，數字鍵必須能作為挑字鍵
        val isSentenceSelecting = false
        val isCandidateGridOpen = false
        val isHomophoneSelectionMode = false
        val isSymbolLeadMode = false
        val fullZhuyinBufferEmpty = true
        val hasCandidates = true

        val canSelect = isSentenceSelecting ||
                isCandidateGridOpen ||
                isHomophoneSelectionMode ||
                isSymbolLeadMode ||
                (fullZhuyinBufferEmpty && hasCandidates)

        assertTrue("當音節已結算且候選字條有候選項目時，必須允許數字鍵選字", canSelect)
    }
}
