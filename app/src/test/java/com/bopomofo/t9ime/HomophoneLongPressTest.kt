package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
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
}
