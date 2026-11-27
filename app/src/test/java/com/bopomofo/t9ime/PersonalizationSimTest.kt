package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * 個人化學習不能「越用越差」：
 * 接受預設不算學習；明確選擇的加分有上限、會衰退，蓋不過詞庫與上下文給的大差距。
 */
class PersonalizationSimTest {
    private val um get() = UserDictionaryManager.getInstance(null)
    private lateinit var e: ZhuyinT9Engine

    @Before
    fun setUp() {
        um.clearDictionary()
        um.clock = null
        e = ZhuyinT9Engine(null)
        e.loadDictionaryForTest()
    }

    @After
    fun tearDown() { um.clock = null }

    @Test
    fun acceptingDefaultNeverCreatesEntry() {
        repeat(50) { e.acceptWord("很") }
        assertEquals(0, um.getUsageCount("很"))
        assertTrue(um.getAllEntries().isEmpty())
    }

    @Test
    fun acceptingDefaultOnlyRefreshesAlreadyLearnedWord() {
        e.learnWord("很", "ㄏㄣˇ")
        val before = um.getUsageCount("很")
        repeat(20) { e.acceptWord("很") }
        assertEquals("接受預設不加次數", before, um.getUsageCount("很"))
    }

    @Test
    fun oneExplicitPickIsAboutDoubleAndBoostIsCapped() {
        e.learnWord("狠", "ㄏㄣˇ")
        assertEquals(2.0, um.applyBoost(1000, "狠", "ㄏㄣˇ") / 1000.0, 0.02)
        repeat(500) { e.learnWord("狠", "ㄏㄣˇ") }
        val max = 1000 * exp(UserDictionaryManager.MAX_BOOST_NAT)
        assertTrue(um.applyBoost(1000, "狠", "ㄏㄣˇ") <= max + 1)
    }

    /** 重現「越用越差」：次要同音字偶爾被改選、多數時候接受常用字，常用字（差 27 倍）仍必須在第一名。 */
    @Test
    fun manyPicksOfRareHomophoneDoNotBuryTheCommonWord() {
        val hen = com.bopomofo.t9ime.engine.DictEntry("很", "ㄏㄣˇ", 27890)
        repeat(30) {
            repeat(9) { e.markCommit(1); e.acceptWord("很", "ㄏㄣˇ", listOf(com.bopomofo.t9ime.engine.DictEntry("狠", "ㄏㄣˇ", 1005))) }
            e.markCommit(1); e.learnPick("狠", "ㄏㄣˇ", listOf(hen), 1005)
        }
        val top = e.searchFullZhuyin("ㄏㄣˇ").firstOrNull()?.word
        assertEquals("9:1 混用，多數派必須維持第一", "很", top)
    }

    @Test
    fun boostDecaysWithHalfLife() {
        var now = 1_000_000_000_000L
        um.clock = { now }
        e.learnWord("狠", "ㄏㄣˇ")
        val fresh = um.boostNat("狠", "ㄏㄣˇ")
        now += (UserDictionaryManager.HALF_LIFE_POS_DAYS * 86_400_000L).toLong()
        val aged = um.boostNat("狠", "ㄏㄣˇ")
        assertEquals(ln(2.0), fresh, 1e-6)
        assertEquals(ln(1.5), aged, 1e-6)
    }

    @Test
    fun boostIsReadingAware() {
        e.learnWord("行", "ㄒㄧㄥˊ")
        assertTrue(um.boostNat("行", "ㄒㄧㄥˊ") > 0)
        assertEquals("不同讀音不吃加分", 0.0, um.boostNat("行", "ㄏㄤˊ"), 0.0)
    }

    @Test
    fun learningWithUnknownReadingOfPolyphoneIsSkipped() {
        e.learnWord("行")     // 多音字又沒給讀音：不學
        assertEquals(0, um.getUsageCount("行"))
    }
}
