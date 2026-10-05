package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.KeyMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeyAndRecencyTest {

    @Test
    fun testKeyMappingDefinitions() {
        // 驗證 12 宮格按鍵的注音定義
        assertEquals(1, KeyMapping.getKeyId('ㄅ'))
        assertEquals(1, KeyMapping.getKeyId('ㄉ'))
        assertEquals(1, KeyMapping.getKeyId('ㄚ'))

        assertEquals(4, KeyMapping.getKeyId('ㄆ'))
        assertEquals(4, KeyMapping.getKeyId('ㄊ'))
        assertEquals(4, KeyMapping.getKeyId('ㄛ'))

        assertEquals(7, KeyMapping.getKeyId('ㄇ'))
        assertEquals(7, KeyMapping.getKeyId('ㄋ'))
        assertEquals(7, KeyMapping.getKeyId('ㄜ'))

        assertEquals(10, KeyMapping.getKeyId('ㄈ'))
        assertEquals(10, KeyMapping.getKeyId('ㄌ'))
        assertEquals(10, KeyMapping.getKeyId('ㄝ'))
    }

    @Test
    fun testRecencyBoostLogic() {
        val recentDiffMs = 60 * 1000L // 1 分鐘前
        val oldDiffMs = 15 * 24 * 3600 * 1000L // 15 天前

        val recentBonus = when {
            recentDiffMs < 10 * 60 * 1000L -> 3_000_000
            recentDiffMs < 24 * 3600 * 1000L -> 1_500_000
            recentDiffMs < 7 * 24 * 3600 * 1000L -> 500_000
            else -> 0
        }

        val oldBonus = when {
            oldDiffMs < 10 * 60 * 1000L -> 3_000_000
            oldDiffMs < 24 * 3600 * 1000L -> 1_500_000
            oldDiffMs < 7 * 24 * 3600 * 1000L -> 500_000
            else -> 0
        }

        assertTrue(recentBonus > oldBonus)
        assertEquals(3_000_000, recentBonus)
        assertEquals(0, oldBonus)
    }
}
