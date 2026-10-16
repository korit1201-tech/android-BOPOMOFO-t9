package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZhuyinFullPreviewTest {

    private lateinit var engine: ZhuyinT9Engine

    @Before
    fun setup() {
        engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest()
    }

    @Test
    fun testSingleSyllableWithoutToneHasNoToneSymbol() {
        // 輸入 Key 4, 9 (對應 ㄆ/ㄊ/ㄛ 與 ㄕ/ㄙ/ㄤ/ㄨ)
        engine.pressKey(4)
        engine.pressKey(9)

        val preview = engine.getFullComposingZhuyin()
        // 驗證頂部預覽絕對不包含任何聲調符號（ˇˋˊ˙）！
        assertFalse("未輸入聲調時頂部膠囊不得包含任何聲調符號 (ˇˋˊ˙)，實際為: $preview", preview.any { it in "ˇˋˊ˙" })
    }

    @Test
    fun testExplicitToneAppliesUserSpecifiedTone() {
        // 輸入 Key 4, 9，接著主動設定四聲 (ˋ)
        engine.pressKey(4)
        engine.pressKey(9)
        engine.setTone('ˋ')

        val preview = engine.getFullComposingZhuyin()
        // 驗證主動指定聲調後，頂部預覽必須以該聲調結尾
        assertTrue("主動指定聲調 ˋ 後，頂部預覽必須包含該聲調，實際為: $preview", preview.endsWith("ˋ"))
    }

    @Test
    fun testMultiSyllableSentenceWithoutToneHasNoToneSymbols() {
        // 輸入 Key 7, 6, 8, 8 (對應 ㄋㄧ ㄏㄠ「你好」)
        engine.pressKey(7)
        engine.pressKey(6)
        engine.pressKey(8)
        engine.pressKey(8)

        val preview = engine.getFullComposingZhuyin()
        // 驗證在未輸入聲調時，即使詞庫首選詞為「你好」(ㄋㄧˇ ㄏㄠˇ)，預覽也必須去除字典自帶聲調
        assertEquals("ㄋㄧㄏㄠ", preview)
        assertFalse("未輸入聲調時多字預覽不得包含任何聲調符號", preview.any { it in "ˇˋˊ˙" })
    }

    @Test
    fun testConfirmedSyllablePreservesToneWhileRemainingHasNoTone() {
        // 步驟 1: 輸入 4, 9，設定四聲 ˋ，並階梯式確認該音節為「ㄆㄤˋ」
        engine.pressKey(4)
        engine.pressKey(9)
        engine.confirmStepSyllable("ㄆㄤˋ")

        // 步驟 2: 繼續輸入第二個字 Key 7 (ㄇㄋㄜ)
        engine.pressKey(7)

        val preview = engine.getFullComposingZhuyin()
        // 第一個字為確認的「ㄆㄤˋ」，第二個字未輸入聲調，絕不得自帶二聲或輕聲
        assertTrue("第一字應保留確認的 ㄆㄤˋ，實際為: $preview", preview.startsWith("ㄆㄤˋ"))
        val remainingPart = preview.substringAfter("ㄆㄤˋ").trim()
        assertFalse("剩餘未輸入聲調的第二字不得帶有聲調符號，實際為: $remainingPart", remainingPart.any { it in "ˇˋˊ˙" })
    }
}
