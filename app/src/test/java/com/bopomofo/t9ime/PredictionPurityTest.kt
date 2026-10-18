package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 預測純淨度與狀態回滾測試 (PredictionPurityTest)
 * 驗收條件：
 * 1. 按鍵→全退格→按新鍵，候選結果 100% 等同於全新引擎直接按新鍵（零殘留、零污染）。
 * 2. 單鍵輸入時，首選必為單字，前 3 名絕無 2 字以上多字詞（T9_DP_WEIGHT_CAP 與短鍵多字詞懲罰生效）。
 * 3. 退格時立即清空 previousTopWord，防止殘留延續加成。
 * 4. 驗收第 6 點：按 3 鍵 → 退格 1 鍵 → 預測結果必須等於「全新引擎直接按剩下 2 鍵」的結果（previousTopWord 無殘留）。
 */
class PredictionPurityTest {

    companion object {
        private lateinit var engine: ZhuyinT9Engine
        private lateinit var referenceEngine: ZhuyinT9Engine

        @BeforeClass
        @JvmStatic
        fun initEngines() {
            engine = ZhuyinT9Engine()
            engine.loadDictionaryForTest()

            referenceEngine = ZhuyinT9Engine()
            referenceEngine.loadDictionaryForTest()
        }
    }

    @Before
    fun reset() {
        engine.clear()
        referenceEngine.clear()
    }

    @Test
    fun testPurityAfterFullBackspace() {
        // 步驟 1: 模擬輸入一系列按鍵 (包含 8 5 8 6 3 2 等可能組成長句的按鍵)
        val dirtyKeys = listOf(8, 5, 8, 6, 3, 2)
        for (k in dirtyKeys) {
            engine.pressKey(k)
        }
        assertTrue("此時應有組字狀態", engine.hasComposing())

        // 步驟 2: 逐一退格直到完全清空
        while (engine.hasComposing()) {
            engine.backspace()
        }
        assertTrue("退格後引擎應無組字狀態", !engine.hasComposing())

        // 步驟 3: 按下新鍵 (Key 8: ㄏ ㄒ ㄠ ㄡ)
        engine.pressKey(8)
        val dirtyCandidates = engine.getCandidates().map { it.word }

        // 步驟 4: 使用對照引擎只按 Key 8
        referenceEngine.pressKey(8)
        val freshCandidates = referenceEngine.getCandidates().map { it.word }

        // 步驟 5: 驗證兩者前 20 名候選詞 100% 嚴格一致！
        assertEquals(
            "退格後按新鍵的候選詞，必須與全新引擎完全一致，不得有任何前次按鍵或 previousTopWord 殘留！",
            freshCandidates.take(20),
            dirtyCandidates.take(20)
        )
    }

    @Test
    fun testSingleKeyTopCandidatesAreSingleChars() {
        // 驗證按 1 個鍵 (例如 Key 8: ㄏ ㄒ ㄠ ㄡ)：
        // 首選必須是單字（如 黑、很、回、偶、和 等），前 3 名絕不得出現 2 字以上多字詞
        engine.pressKey(8)
        val candidates = engine.getCandidates()

        assertTrue("候選詞列表不得為空", candidates.isNotEmpty())
        val top1 = candidates[0].word
        assertEquals("單鍵輸入首選必須為單字 (長度 1)，實際為: $top1", 1, top1.length)

        val top3 = candidates.take(3)
        for ((idx, entry) in top3.withIndex()) {
            assertEquals(
                "單鍵輸入前 3 名不得出現 2 字以上詞，第 ${idx + 1} 名為: ${entry.word}",
                1,
                entry.word.length
            )
        }
    }

    @Test
    fun testPartialBackspacePurity() {
        // 驗收條件 6: 按 3 鍵 → 退格 1 鍵 → 預測結果必須等於「全新引擎直接按剩下 2 鍵」的結果（previousTopWord 無殘留）
        // 測試案例 A: Key 8, 5, 2 -> 退格 -> 剩 8, 5
        engine.pressKey(8)
        engine.pressKey(5)
        engine.pressKey(2)
        engine.backspace()
        val afterBackspacedCandidatesA = engine.getCandidates().map { it.word }

        referenceEngine.pressKey(8)
        referenceEngine.pressKey(5)
        val freshCandidatesA = referenceEngine.getCandidates().map { it.word }

        assertEquals(
            "按 3 鍵退格 1 鍵後的預測結果，必須 100% 等同於全新引擎直接輸入 2 鍵，不得有 previousTopWord 殘留！",
            freshCandidatesA.take(20),
            afterBackspacedCandidatesA.take(20)
        )

        // 測試案例 B: Key 2, 6, 9 -> 退格 -> 剩 2, 6
        engine.clear()
        referenceEngine.clear()

        engine.pressKey(2)
        engine.pressKey(6)
        engine.pressKey(9)
        engine.backspace()
        val afterBackspacedCandidatesB = engine.getCandidates().map { it.word }

        referenceEngine.pressKey(2)
        referenceEngine.pressKey(6)
        val freshCandidatesB = referenceEngine.getCandidates().map { it.word }

        assertEquals(
            "測試組 B：按 3 鍵退格 1 鍵後的預測結果必須與全新引擎 2 鍵完全一致！",
            freshCandidatesB.take(20),
            afterBackspacedCandidatesB.take(20)
        )
    }
}
