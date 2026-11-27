package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 詞庫權重被嚴重低估的現代常用詞（「手機」只有 10，同鍵的「手記」4200、「收集」3272），
 * 使用者連續明確選它時，學習要能補上差距，幾次內升到第一名。
 */
class HandsetLearningTest {
    private val um get() = UserDictionaryManager.getInstance(null)
    private lateinit var e: ZhuyinT9Engine

    /** 固定的小詞庫：重現舊詞庫的失真（手機 10 對 手記 4200），不受真實詞庫日後補強影響。 */
    private val miniDict = """
        手	ㄕㄡˇ	13457
        收	ㄕㄡ	17139
        受	ㄕㄡˋ	9000
        機	ㄐㄧ	8000
        記	ㄐㄧˋ	9000
        集	ㄐㄧˊ	7000
        手機	ㄕㄡˇㄐㄧ	10	ㄕㄐ
        手記	ㄕㄡˇㄐㄧˋ	4200	ㄕㄐ
        收集	ㄕㄡㄐㄧˊ	3272	ㄕㄐ
        搜集	ㄙㄡㄐㄧˊ	576	ㄙㄐ
        蒐集	ㄙㄡㄐㄧˊ	2309	ㄙㄐ
    """.trimIndent()

    @Before
    fun setUp() {
        um.clearDictionary(); um.clock = null
        e = ZhuyinT9Engine(null)
        e.loadDictionaryForTest(java.io.ByteArrayInputStream(miniDict.toByteArray(Charsets.UTF_8)))
    }

    private fun key(c: Char) = KeyMapping.getKeyId(c)!!

    /** 模擬使用者輸入一次：回傳目標詞的排名（1 起算，找不到為 -1），並照服務層的規則學習。 */
    private fun typeOnce(withToneKey: Boolean): Int {
        e.clear()
        e.pressKey(key('ㄕ')); e.pressKey(key('ㄡ'))
        if (withToneKey) e.cycleTone()               // 在注音中間按聲調鍵（ˇ）
        e.pressKey(key('ㄐ')); e.pressKey(key('ㄧ'))
        val c = e.getCandidates()
        val rank = c.indexOfFirst { it.word == "手機" }
        if (rank < 0) return -1
        val entry = c[rank]
        e.markCommit(entry.word.length)
        if (rank == 0) e.acceptWord(entry.word, entry.zhuyin, c.drop(1).filter { it.word.length == 2 }.take(4))
        else e.learnPick(entry.word, entry.zhuyin, c.subList(0, rank), entry.weight)
        return rank + 1
    }

    @Test
    fun handsetReachesFirstWithinSixPicksWithoutTone() {
        val ranks = (1..8).map { typeOnce(false) }
        println("HANDSET 不打聲調 排名變化: $ranks")
        assertTrue("應在 6 次內升到第一名：$ranks", ranks.take(7).any { it == 1 })
        val first = ranks.indexOfFirst { it == 1 }
        assertTrue("升到第一名後要維持（不能再掉回去）：$ranks", ranks.drop(first).all { it == 1 })
    }

    @Test
    fun handsetReachesFirstWithinSixPicksWithToneKey() {
        val ranks = (1..8).map { typeOnce(true) }
        println("HANDSET 打聲調 排名變化: $ranks")
        assertTrue("應在 6 次內升到第一名：$ranks", ranks.take(7).any { it == 1 })
        val first = ranks.indexOfFirst { it == 1 }
        assertTrue("升到第一名後要維持（不能再掉回去）：$ranks", ranks.drop(first).all { it == 1 })
    }

    /** 追上差距不能反過來讓原本的常用詞被永久壓死：改回選「手記」之後，它要能回到前面。 */
    @Test
    fun switchingBackToTheOtherWordRecoversTheOldOrder() {
        repeat(6) { typeOnce(false) }
        val c0 = { e.clear(); e.pressKey(key('ㄕ')); e.pressKey(key('ㄡ')); e.pressKey(key('ㄐ')); e.pressKey(key('ㄧ')); e.getCandidates() }
        repeat(12) {
            val c = c0()
            val rank = c.indexOfFirst { it.word == "手記" }
            e.markCommit(2)
            if (rank == 0) e.acceptWord("手記", c[0].zhuyin, c.drop(1).filter { it.word.length == 2 }.take(4))
            else if (rank > 0) e.learnPick("手記", c[rank].zhuyin, c.subList(0, rank), c[rank].weight)
        }
        assertEquals("一直改選手記之後，手記要回到第一名", "手記", c0().first().word)
    }
}
