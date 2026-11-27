package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.KeyNeighbors
import com.bopomofo.t9ime.engine.TrieDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyNeighborsTest {
    @Test
    fun gridNeighbors() {
        assertEquals(setOf(8, 4), KeyNeighbors.neighbors(7).toSet().intersect(setOf(8, 4)))
        // ㄇ(7) 與 ㄏ(8) 左右相鄰
        assertTrue(8 in KeyNeighbors.neighbors(7))
        assertTrue(7 in KeyNeighbors.neighbors(8))
        // 角落鍵只有兩個鄰鍵
        assertEquals(setOf(2, 4), KeyNeighbors.neighbors(1).toSet())
        // 中間鍵四個鄰鍵
        assertEquals(setOf(2, 4, 6, 8), KeyNeighbors.neighbors(5).toSet())
    }

    @Test
    fun toneKeyNeverParticipates() {
        assertTrue(KeyNeighbors.neighbors(11).isEmpty())
        for (k in 1..12) assertTrue("鍵 $k 的鄰鍵不應含聲調鍵", 11 !in KeyNeighbors.neighbors(k))
        assertTrue(KeyNeighbors.neighbors(0).isEmpty())
        assertTrue(KeyNeighbors.neighbors(13).isEmpty())
    }

    @Test
    fun neighborRelationIsSymmetric() {
        for (a in 1..12) for (b in KeyNeighbors.neighbors(a)) {
            assertTrue("$a→$b 應該對稱", a in KeyNeighbors.neighbors(b))
        }
    }

    @Test
    fun touchHintTowardNearestEdge() {
        // 鍵 7（ㄇ）寬 100 高 100：靠右緣落指 → 偏向鍵 8
        val right = KeyNeighbors.hintFromTouch(7, 95f, 50f, 100f, 100f)
        assertNotNull(right)
        assertEquals(8, right!!.toward)
        assertTrue(right.confidence > 0.6f)
        // 靠上緣 → 鍵 4
        assertEquals(4, KeyNeighbors.hintFromTouch(7, 50f, 3f, 100f, 100f)!!.toward)
        // 鍵中央不靠邊 → 沒有提示
        assertNull(KeyNeighbors.hintFromTouch(7, 50f, 50f, 100f, 100f))
        // 剛好在邊界比例上 → 沒有提示
        assertNull(KeyNeighbors.hintFromTouch(7, 80f, 50f, 100f, 100f))
    }

    @Test
    fun touchHintIgnoresMissingSideAndToneKey() {
        // 最左欄鍵 1 靠左緣：左邊沒有鍵
        assertNull(KeyNeighbors.hintFromTouch(1, 2f, 50f, 100f, 100f))
        // 鍵 8 靠下緣會偏向聲調鍵 11：不給提示
        assertNull(KeyNeighbors.hintFromTouch(8, 50f, 97f, 100f, 100f))
        // 聲調鍵本身
        assertNull(KeyNeighbors.hintFromTouch(11, 2f, 50f, 100f, 100f))
        // 尺寸無效
        assertNull(KeyNeighbors.hintFromTouch(5, 2f, 2f, 0f, 100f))
    }

    @Test
    fun trieSlipSearchFindsOnlySlippedNodes() {
        val trie = TrieDictionary()
        // ㄇㄚ 之外的詞：ㄏㄠˇ（好）鍵序 8,8（ㄏ=8, ㄠ=8）
        val hao = DictEntry("好", "ㄏㄠˇ", 1000)
        trie.insert(hao)
        val typed = listOf(7, 8) // 第一鍵按成 ㄇ 所在的鍵 7（實際想按 8）
        val intended = KeyMapping.getSequence(hao.zhuyin, ignoreTones = true)
        assertEquals(listOf(8, 8), intended)
        val m = trie.searchNodeWithSlips(typed, 1)
        assertEquals(1, m.size)
        assertEquals(0, m[0].slips[0].pos)
        assertEquals(8, m[0].slips[0].intended)
        assertTrue(m[0].node.exactEntries.containsKey("好"))
        // 沒糾錯的原讀法不在糾錯結果裡
        assertTrue(trie.searchNodeWithSlips(listOf(8, 8), 1).isEmpty())
    }

    @Test
    fun trieSlipSearchHonoursProtectedTailAndBudget() {
        val trie = TrieDictionary()
        trie.insert(DictEntry("好", "ㄏㄠˇ", 1000)) // 8,8
        // 最後一鍵（索引 1）受保護：8,7 不能糾回 8,8
        assertTrue(trie.searchNodeWithSlips(listOf(8, 7), 1, protectedFrom = 1).isEmpty())
        assertEquals(1, trie.searchNodeWithSlips(listOf(8, 7), 1).size)
        // 兩處錯、預算只有 1 → 找不到；預算 2 → 找得到
        assertTrue(trie.searchNodeWithSlips(listOf(7, 7), 1).isEmpty())
        assertEquals(1, trie.searchNodeWithSlips(listOf(7, 7), 2).size)
        // 預算 0 一律空
        assertTrue(trie.searchNodeWithSlips(listOf(7, 8), 0).isEmpty())
    }

    @Test
    fun trieSlipSearchNeverSlipsToneKey() {
        val trie = TrieDictionary()
        trie.insert(DictEntry("好", "ㄏㄠˇ", 1000)) // 帶聲調鍵序 8,8,11
        // 11 在序列中永遠不糾：把 11 當成別的鍵的嘗試必須失敗
        assertTrue(trie.searchNodeWithSlips(listOf(8, 8, 10), 1).isEmpty())
    }
}
