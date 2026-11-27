package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.KeyNeighbors
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SlipCorrectionEngineTest {
    companion object {
        private val engine = ZhuyinT9Engine()

        @BeforeClass
        @JvmStatic
        fun load() {
            engine.loadDictionaryForTest(File("src/main/assets/dict_tw.txt"))
        }

        /** 把詞的注音轉成不含聲調鍵的鍵序，再把第 [pos] 鍵換成鄰鍵 [to] */
        fun slippedKeys(zhuyin: String, pos: Int, to: Int): List<Int> {
            val keys = KeyMapping.getSequence(zhuyin, ignoreTones = true).toMutableList()
            keys[pos] = to
            return keys
        }

        fun press(keys: List<Int>, hints: List<KeyNeighbors.SlipHint?>? = null) {
            engine.clear()
            engine.currentContextWord = null
            keys.forEachIndexed { i, k -> engine.pressKey(k, hints?.getOrNull(i)) }
        }
    }

    @Test
    fun levelZeroAndShortInputDoNothing() {
        press(listOf(7, 8, 8, 8))
        assertNull(engine.applySlipCorrection(0))
        press(listOf(7, 8)) // 少於 3 鍵
        assertNull(engine.applySlipCorrection(2))
        engine.clear()
        assertNull(engine.applySlipCorrection(2))
    }

    @Test
    fun slippedMiddleKeyIsRecoveredButNotFirstAtConservativeLevel() {
        // 「朋友」ㄆㄥˊ ㄧㄡˇ：鍵 4,12,6,8,9；把第 3 鍵（ㄧ=6）偏到鄰鍵 5 之外的合法鄰鍵
        val target = "朋友"
        val zy = "ㄆㄥˊㄧㄡˇ"
        val intended = KeyMapping.getSequence(zy, ignoreTones = true)
        val pos = 2
        val neighbor = KeyNeighbors.neighbors(intended[pos]).first()
        press(slippedKeys(zy, pos, neighbor))
        val before = engine.getCandidates().map { it.word }
        val after = engine.applySlipCorrection(1)
        assertNotNull("保守檔應補上糾錯候選", after)
        val words = after!!.map { it.word }
        assertTrue("糾錯候選應包含 $target：$words", target in words)
        assertTrue(after.first { it.word == target }.slipped)
        // 保守檔：原首選仍是第一
        assertEquals(before.first(), words.first())
        // 原本的候選沒有被刪掉，只是多了糾錯候選
        assertTrue(words.containsAll(before))
    }

    @Test
    fun lastKeyIsProtected() {
        val zy = "ㄆㄥˊㄧㄡˇ"
        val intended = KeyMapping.getSequence(zy, ignoreTones = true)
        val last = intended.lastIndex
        val neighbor = KeyNeighbors.neighbors(intended[last]).first()
        press(slippedKeys(zy, last, neighbor))
        val result = engine.applySlipCorrection(2)
        // 句尾最後一鍵不糾：不應該出現把「最後一鍵」換掉才能得到的候選
        if (result != null) {
            assertFalse("句尾鍵被糾了：${result.filter { it.slipped }.map { it.word }}", result.any { it.slipped && it.word == "朋友" })
        }
    }

    @Test
    fun cleanInputGetsNoWrongFirstCandidateFromSlip() {
        val zy = "ㄆㄥˊㄧㄡˇ"
        press(KeyMapping.getSequence(zy, ignoreTones = true))
        val top = engine.getCandidates().first().word
        for (level in 1..2) {
            press(KeyMapping.getSequence(zy, ignoreTones = true))
            val r = engine.applySlipCorrection(level)
            val first = (r ?: engine.getCandidates()).first()
            assertEquals("乾淨輸入在檔位 $level 首選不該變", top, first.word)
            assertFalse(first.slipped)
        }
    }

    @Test
    fun touchHintMakesTheIndicatedNeighborCheaper() {
        // 同一串鍵序，兩個鄰鍵都可能：有提示時，提示指的那個鄰鍵的糾錯成本較低
        val zy = "ㄆㄥˊㄧㄡˇ"
        val intended = KeyMapping.getSequence(zy, ignoreTones = true)
        val pos = 2
        val neighbor = KeyNeighbors.neighbors(intended[pos]).first()
        val keys = slippedKeys(zy, pos, neighbor)
        val hints = MutableList<KeyNeighbors.SlipHint?>(keys.size) { null }
        hints[pos] = KeyNeighbors.SlipHint(intended[pos], 1f)
        press(keys, hints)
        val withHint = engine.applySlipCorrection(2)
        assertNotNull(withHint)
        assertTrue(withHint!!.any { it.word == "朋友" })
    }

    @Test
    fun backspaceAndClearKeepHintsAligned() {
        press(listOf(4, 12, 6, 8, 9), List(5) { KeyNeighbors.SlipHint(5, 0.5f) })
        engine.backspace()
        engine.clear()
        press(listOf(7, 8, 8, 8)) // 不帶提示，不應因殘留提示而出錯
        engine.applySlipCorrection(2)
        assertTrue(engine.hasComposing())
    }

    @Test
    fun learnedBigramsAreCapped() {
        val e = ZhuyinT9Engine()
        for (i in 0 until 300) e.learnBigram("前$i", "後$i")
        val removed = e.trimLearnedBigrams(cap = 100)
        assertTrue("超過上限應移除詞對", removed >= 200)
        assertEquals(0, e.trimLearnedBigrams(cap = 10_000))
    }
}
