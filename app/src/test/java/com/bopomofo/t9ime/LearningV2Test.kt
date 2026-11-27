package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.ln

/** 學習 v2：負向訊號、事件撤銷、刪除重打、自建詞、遷移、匯出入、重設。 */
class LearningV2Test {
    private val um get() = UserDictionaryManager.getInstance(null)
    private lateinit var e: ZhuyinT9Engine
    private val hen3 = DictEntry("很", "ㄏㄣˇ", 27890)

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
    fun skippedTopCandidateGetsNegativeAndPickedGetsPositive() {
        e.markCommit(1)
        e.learnPick("狠", "ㄏㄣˇ", listOf(hen3))
        assertTrue(um.boostNat("狠", "ㄏㄣˇ") > 0.6)
        assertTrue("被跳過的第一名要降權", um.boostNat("很", "ㄏㄣˇ") < 0.0)
        assertTrue(um.applyBoost(27890, "很", "ㄏㄣˇ") < 27890)
    }

    @Test
    fun negativeIsBoundedSoFunctionWordsAreNeverBuried() {
        repeat(300) { e.learnPick("狠", "ㄏㄣˇ", listOf(hen3)) }
        assertTrue(um.boostNat("很", "ㄏㄣˇ") >= UserDictionaryManager.MIN_BOOST_NAT - 1e-9)
        // 最糟也只剩 e^-1 ≈ 0.37 倍，仍遠高於冷僻同音字
        assertTrue(um.applyBoost(27890, "很", "ㄏㄣˇ") > 10_000)
    }

    @Test
    fun predictionsOfDifferentLengthAreNotCountedAsSkipped() {
        e.learnPick("狠", "ㄏㄣˇ", listOf(DictEntry("很好", "ㄏㄣˇㄏㄠˇ", 9000)))
        assertEquals(0.0, um.boostNat("很好", "ㄏㄣˇㄏㄠˇ"), 0.0)
    }

    @Test
    fun acceptingHalvesNegativeAndKeepsLearnedWordFresh() {
        e.learnPick("狠", "ㄏㄣˇ", listOf(hen3))
        val before = um.boostNat("很", "ㄏㄣˇ")
        e.learnWord("很", "ㄏㄣˇ")
        e.acceptWord("很", "ㄏㄣˇ")
        assertTrue(um.boostNat("很", "ㄏㄣˇ") > before)
    }

    @Test
    fun revokeRollsBackWholeCommitGroupIncludingNegatives() {
        e.markCommit(1)
        e.learnPick("狠", "ㄏㄣˇ", listOf(hen3))
        e.learnBigram("他", "狠")
        assertTrue(e.revokeRecentLearning())
        assertEquals(0, um.getUsageCount("狠"))
        assertEquals(0.0, um.boostNat("很", "ㄏㄣˇ"), 1e-9)
        assertEquals(0, e.getBigramBoost("他", "狠"))
        assertEquals(1, e.lastRevokedTextLength)
    }

    @Test
    fun barrierPreventsRevokingAfterPunctuationOrNewline() {
        e.markCommit(1)
        e.learnWord("狠", "ㄏㄣˇ")
        e.learnBarrier()
        assertFalse(e.revokeRecentLearning())
        assertEquals(1, um.getUsageCount("狠"))
    }

    @Test
    fun deletingThenRetypingSameReadingMarksTheDeletedWordWrong() {
        e.markCommit(1)
        e.learnWord("狠", "ㄏㄣˇ")
        e.revokeRecentLearning()
        e.markCommit(1)
        e.learnWord("很", "ㄏㄣˇ")
        assertTrue("刪掉又改選別的：被刪的詞要有負向", um.boostNat("狠", "ㄏㄣˇ") < 0.0)
        assertTrue(um.boostNat("很", "ㄏㄣˇ") > 0.6)
    }

    @Test
    fun retypeWithDifferentReadingDoesNotPenalize() {
        e.markCommit(1)
        e.learnWord("狠", "ㄏㄣˇ")
        e.revokeRecentLearning()
        e.markCommit(1)
        e.learnWord("恨", "ㄏㄣˋ")
        assertEquals(0.0, um.boostNat("狠", "ㄏㄣˇ"), 1e-9)
    }

    @Test
    fun customWordIsStrongAndNeverDecaysBelowFloorNorGetsReset() {
        var now = 1_000_000_000_000L
        um.clock = { now }
        e.learnCustomWord("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ")
        assertTrue(um.boostNat("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ") >= ln(5.0) - 1e-6)
        now += 365L * 86_400_000L
        assertTrue(um.boostNat("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ") >= ln(3.0) - 1e-6)
        e.learnWord("狠", "ㄏㄣˇ")
        assertEquals(1, um.resetLearningKeepCustom())
        assertEquals(0.0, um.boostNat("狠", "ㄏㄣˇ"), 0.0)
        assertTrue(um.boostNat("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ") > 0.0)
    }

    @Test
    fun legacyMigrationCompressesInflatedCountsKeepsCustomDropsGarbage() {
        um.seedLegacyForTest("很", "ㄏㄣˇ", 200)               // 詞庫內，被灌高
        um.seedLegacyForTest("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ", 5)    // 詞庫外多字詞 → 自建
        um.seedLegacyForTest("ㄏㄣˇㄏㄠˇ", "ㄏㄣˇㄏㄠˇ", 3)    // 舊 bug 學進去的注音原字串
        um.migrateLegacy { w, _ -> w == "很" }
        assertTrue(um.boostNat("很", "ㄏㄣˇ") <= ln(1.0 + 1.0) + 1e-6)
        assertTrue(um.boostNat("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ") > 1.0)
        assertEquals(0, um.getUsageCount("ㄏㄣˇㄏㄠˇ"))
        assertFalse(um.needsLegacyMigration)
    }

    @Test
    fun exportImportRoundTripAndLegacyTsvIsCapped() {
        e.learnWord("狠", "ㄏㄣˇ")
        e.learnCustomWord("柯文哲", "ㄎㄜ ㄨㄣˊ ㄓㄜˊ")
        val out = ByteArrayOutputStream()
        um.exportToStream(out)
        val text = out.toString("UTF-8")
        assertTrue(text.lines().any { it.startsWith("柯文哲\t") && it.split("\t").size >= 5 })
        um.clearDictionary()
        assertEquals(2, um.importFromStream(ByteArrayInputStream(text.toByteArray())))
        assertTrue(um.boostNat("狠", "ㄏㄣˇ") > 0)

        um.clearDictionary()
        um.importFromStream(ByteArrayInputStream("很\tㄏㄣˇ\t999\n".toByteArray()))   // 舊格式、被灌高的次數
        assertTrue(um.boostNat("很", "ㄏㄣˇ") <= ln(5.0) + 1e-6)
    }

    @Test
    fun polyphoneReadingsAreIndependent() {
        e.learnWord("行", "ㄒㄧㄥˊ")
        e.learnWord("行", "ㄏㄤˊ")
        e.learnWord("行", "ㄏㄤˊ")
        assertTrue(um.boostNat("行", "ㄏㄤˊ") > um.boostNat("行", "ㄒㄧㄥˊ"))
        assertEquals(3, um.getUsageCount("行"))
    }
}
