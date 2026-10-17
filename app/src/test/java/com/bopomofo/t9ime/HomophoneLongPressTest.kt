package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class HomophoneLongPressTest {

    private lateinit var engine: ZhuyinT9Engine

    @Before
    fun setUp() {
        engine = ZhuyinT9Engine()
        val dictFile = File("src/main/assets/dict_tw.txt")
        if (dictFile.exists()) {
            engine.loadDictionaryForTest(FileInputStream(dictFile))
        }
    }

    @Test
    fun testSingleCharHomophones() {
        // 測試單字同音字查詢
        val entryTa = DictEntry("他", "ㄊㄚ", 1000)
        val homophones = engine.getHomophonesFor(entryTa)
        assertNotNull(homophones)
        assertTrue("長按「他」應能查出同音字", homophones.isNotEmpty())
        val words = homophones.map { it.word }
        assertTrue("「他」的同音字應包含「她」或「它」", words.contains("她") || words.contains("它"))
    }

    @Test
    fun testPolyphoneWithPreferredZhuyin() {
        // 測試多音字依 preferredZhuyin 優先查詢
        val entryDe = DictEntry("得", "ㄉㄟˇ", 1000)
        val homophones = engine.getHomophonesFor(entryDe)
        assertNotNull(homophones)
        assertTrue(homophones.isNotEmpty())
    }

    @Test
    fun testFallbackZhuyinForMultiCharWords() {
        // 測試多字詞同音詞或逐字注音解析
        val entryToday = DictEntry("今天", "", 5000)
        val homophones = engine.getHomophonesFor(entryToday)
        assertNotNull(homophones)
    }

    @Test
    fun testCreateCustomWordFromCandidate() {
        // 模擬修改候選詞「今天」各字產生新自有詞「金添」
        val originalWord = "今天"
        val originalZhuyin = "ㄐㄧㄣ ㄊㄧㄢ"

        // 1. 查詢第 1 字【今】同音字
        val homos1 = engine.getHomophonesForChar('今', "ㄐㄧㄣ")
        assertTrue("【今】同音字應包含【金】", homos1.any { it.word == "金" })

        // 2. 查詢第 2 字【天】同音字
        val homos2 = engine.getHomophonesForChar('天', "ㄊㄧㄢ")
        assertTrue("【天】同音字應包含【添】", homos2.any { it.word == "添" })

        // 3. 學習新自有詞「金添」
        val customWord = "金添"
        engine.learnWord(customWord, originalZhuyin)

        // 4. 驗證：原詞「今天」依然完好存在於詞庫中（不被破壞）
        val candidatesForToday = engine.searchFullZhuyin("ㄐㄧㄣㄊㄧㄢ")
        assertTrue("原詞「今天」應依然存在於詞庫", candidatesForToday.any { it.word == "今天" })

        // 5. 驗證：新自有詞「金添」成功註冊且獲得最高置頂優先級
        assertTrue("新自有詞「金添」應成功註冊至候選庫", candidatesForToday.any { it.word == "金添" })
        assertEquals("新自有詞「金添」應享有最高權重排在首位", "金添", candidatesForToday.first().word)
    }
}
