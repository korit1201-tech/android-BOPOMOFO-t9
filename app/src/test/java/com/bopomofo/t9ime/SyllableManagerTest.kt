package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.SyllableManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllableManagerTest {
    @Test
    fun testValidSyllable() {
        assertTrue(SyllableManager.isValidSyllable("ㄒㄧㄢ"))
        assertTrue(SyllableManager.isValidSyllable("ㄓㄨ"))
        assertTrue(SyllableManager.isValidSyllable("ㄇㄚ"))
        assertTrue(SyllableManager.isValidSyllable("ㄕ"))
        assertTrue(SyllableManager.isValidSyllable("ㄍㄨㄛ"))

        assertFalse(SyllableManager.isValidSyllable("ㄐㄊ"))
        assertFalse(SyllableManager.isValidSyllable("ㄅㄓㄉ"))
        assertFalse(SyllableManager.isValidSyllable("ㄐㄧㄣㄊ"))
    }

    @Test
    fun testSplitFullZhuyin() {
        val res1 = SyllableManager.splitFullZhuyin("ㄉㄨˊ", 1)
        org.junit.Assert.assertEquals(listOf("ㄉㄨˊ"), res1)

        val res2 = SyllableManager.splitFullZhuyin("ㄉㄡ", 1)
        org.junit.Assert.assertEquals(listOf("ㄉㄡ"), res2)

        val res3 = SyllableManager.splitFullZhuyin("ㄉㄚˋㄐㄧㄚ", 2)
        org.junit.Assert.assertEquals(listOf("ㄉㄚˋ", "ㄐㄧㄚ"), res3)

        val res4 = SyllableManager.splitFullZhuyin("ㄐㄧㄣㄊㄧㄢ", 2)
        org.junit.Assert.assertEquals(listOf("ㄐㄧㄣ", "ㄊㄧㄢ"), res4)

        val res5 = SyllableManager.splitFullZhuyin("ㄐㄧㄣ ㄊㄧㄢ", 2)
        org.junit.Assert.assertEquals(listOf("ㄐㄧㄣ", "ㄊㄧㄢ"), res5)
    }
}
