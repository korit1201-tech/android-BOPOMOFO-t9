package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 個人詞庫只該記「使用者真的選用」的詞：引擎自己的斷詞不算，上屏後立刻退格要撤銷。 */
class LearningMechanismTest {
    private val um get() = UserDictionaryManager.getInstance(null)
    private lateinit var e: ZhuyinT9Engine

    @Before
    fun setUp() {
        um.clearDictionary()
        e = ZhuyinT9Engine(null)
        e.loadDictionaryForTest()
    }

    @Test
    fun sentenceSegmentationIsNotLearned() {
        val syl = "ㄨㄛˇ ㄐㄧㄝ ㄅㄨˋ ㄉㄠˋ ㄉㄧㄢˋ ㄏㄨㄚˋ".split(" ")
        e.learnCommittedSentence("我接不到電話", syl, emptyMap(), "幫")
        assertTrue("引擎斷詞不應寫入個人詞庫", um.getAllEntries().isEmpty())
        assertEquals(0, e.getBigramBoost("幫", "我"))
    }

    @Test
    fun pinnedReplacementIsStillLearned() {
        val syl = "ㄨㄛˇ ㄐㄧㄝ ㄅㄨˋ".split(" ")
        e.learnCommittedSentence("我接不", syl, mapOf(1 to "接"), null)
        assertEquals(1, um.getUsageCount("接"))
    }

    @Test
    fun backspaceRightAfterPickRevokesLearning() {
        e.learnWord("姊", "ㄐㄧㄝˇ")
        e.learnBigram("我", "姊")
        assertEquals(1, um.getUsageCount("姊"))
        assertTrue(e.revokeRecentLearning())
        assertEquals(0, um.getUsageCount("姊"))
        assertEquals(0, e.getBigramBoost("我", "姊"))
    }
}
