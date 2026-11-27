package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 十二鍵聲調鍵要能明確選「一聲」（沒有符號的聲調），不能只有「全部」和四個有符號的聲調。 */
class FirstToneTest {
    private lateinit var e: ZhuyinT9Engine

    @Before
    fun setUp() {
        UserDictionaryManager.getInstance(null).clearDictionary()
        e = ZhuyinT9Engine(null)
        e.loadDictionaryForTest()
    }

    private fun lastSyllableTone(zhuyin: String): Char? =
        zhuyin.trimEnd().lastOrNull()?.takeIf { it in "ˇˋˊ˙" }

    @Test
    fun toneKeyCyclesThroughFirstToneThenWrapsToNone() {
        e.pressKey(7); e.pressKey(1)            // ㄇㄚ
        val seen = ArrayList<Char>()
        repeat(6) { seen.add(e.cycleTone().first) }
        assertEquals(listOf('ˇ', 'ˋ', 'ˊ', '˙', ZhuyinT9Engine.FIRST_TONE, ' '), seen)
    }

    @Test
    fun firstToneOnlyKeepsCandidatesWithoutToneMark() {
        e.pressKey(7); e.pressKey(1)            // ㄇㄚ：媽(一聲) 麻(二) 馬(三) 罵(四) 嗎(輕)
        repeat(5) { e.cycleTone() }             // 停在 ˉ
        val words = e.getCandidates().filter { it.word.length == 1 }
        assertTrue("應該有候選", words.isNotEmpty())
        for (c in words) assertEquals("一聲不應含聲調符號: ${c.word} ${c.zhuyin}", null, lastSyllableTone(c.zhuyin))
        assertTrue("媽應在候選中", words.any { it.word == "媽" })
        assertTrue("馬(三聲)不該出現", words.none { it.word == "馬" })
    }

    @Test
    fun firstToneSendsNoToneKeyAndShowsMarkInPreview() {
        e.pressKey(7); e.pressKey(1)
        repeat(5) { e.cycleTone() }
        assertEquals(listOf(7, 1), e.getCurrentKeys())
        assertEquals("ㄇㄚˉ", e.getKeyOnlyPreview())
    }
}
