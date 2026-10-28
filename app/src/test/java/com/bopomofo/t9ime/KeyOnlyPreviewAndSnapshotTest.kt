package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 九鍵「真實按鍵預覽」與「按下前後快照還原」測試。
 * 驗收條件：
 * 1. 上方輸入狀態（getKeyOnlyPreview）的注音符號數量永遠等於實際按鍵數，不會被前綴預測詞污染。
 * 2. 只按 1 鍵（ㄏ ㄒ ㄠ ㄡ 鍵）時，預覽恰為 1 個注音符號，即使候選列第一名是多字詞。
 * 3. 快照還原後，按鍵、聲調、候選與按下前完全一致（取代以 backspace 推算的回滾）。
 * 注意：本測試只涵蓋九鍵引擎新增 API，不涉及全鍵盤 / 實體鍵盤路徑。
 */
class KeyOnlyPreviewAndSnapshotTest {

    companion object {
        private lateinit var engine: ZhuyinT9Engine

        @BeforeClass
        @JvmStatic
        fun initEngine() {
            engine = ZhuyinT9Engine()
            engine.loadDictionaryForTest()
        }

        private val TONE_AND_SYLLABLE_CHARS = "ˇˋˊ˙"
    }

    @Before
    fun reset() {
        engine.clear()
    }

    private fun symbolCount(preview: String) = preview.count { it !in TONE_AND_SYLLABLE_CHARS }

    @Test
    fun singleKeyPreviewIsExactlyOneSymbol() {
        engine.pressKey(8)
        val preview = engine.getKeyOnlyPreview()
        assertEquals("按 1 鍵預覽必須恰為 1 個注音符號", 1, preview.length)
        assertEquals("ㄏ", preview)
    }

    @Test
    fun previewIgnoresPredictionLength() {
        engine.pressKey(8)
        val topWord = engine.getCandidates().firstOrNull()?.word ?: ""
        val preview = engine.getKeyOnlyPreview()
        // 無論候選第一名是幾個字，預覽長度只取決於按鍵數
        assertEquals(1, preview.length)
        assertTrue("測試前提：候選列有內容 (top=$topWord)", engine.getCandidates().isNotEmpty())
    }

    @Test
    fun previewSymbolCountEqualsKeyCount() {
        val sequences = listOf(
            listOf(8, 7),
            listOf(8, 5, 8, 6, 3, 2),
            listOf(1, 2, 3, 4, 5),
            listOf(9, 12, 10),
            listOf(6, 6, 6, 6, 6, 6, 6)
        )
        for (seq in sequences) {
            engine.clear()
            for (k in seq) engine.pressKey(k)
            val preview = engine.getKeyOnlyPreview()
            assertEquals("按鍵 $seq 的預覽 '$preview' 符號數必須等於按鍵數", seq.size, symbolCount(preview))
        }
    }

    @Test
    fun previewShowsTrailingToneOnly() {
        engine.pressKey(8)
        engine.cycleTone() // ˇ
        assertEquals("ㄏˇ", engine.getKeyOnlyPreview())
    }

    @Test
    fun previewIsEmptyWithoutKeys() {
        assertEquals("", engine.getKeyOnlyPreview())
    }

    @Test
    fun snapshotRestoreUndoesOrdinaryKey() {
        engine.pressKey(8)
        engine.pressKey(7)
        val before = engine.captureInputState()
        val beforePreview = engine.getKeyOnlyPreview()
        val beforeWords = engine.getCandidates().map { it.word }

        engine.pressKey(1)
        val after = engine.captureInputState()
        assertTrue(engine.isSameInputState(after))
        assertFalse(engine.isSameInputState(before))

        engine.restoreInputState(before)
        assertEquals(listOf(8, 7), engine.getCurrentKeys())
        assertEquals(beforePreview, engine.getKeyOnlyPreview())
        assertEquals(beforeWords, engine.getCandidates().map { it.word })
    }

    @Test
    fun snapshotRestoreKeepsEarlierToneWhenToneKeyIsSwiped() {
        engine.pressKey(8)
        engine.cycleTone() // 目前聲調 ˇ，keys=[8,11]
        val before = engine.captureInputState()
        assertEquals("ㄏˇ", engine.getKeyOnlyPreview())

        engine.cycleTone() // 手指按下聲調鍵：ˇ -> ˋ
        assertEquals("ㄏˋ", engine.getKeyOnlyPreview())

        engine.restoreInputState(before) // 隨後滑動：必須回到 ˇ，而不是被 backspace 刪成沒有聲調
        assertEquals("ㄏˇ", engine.getKeyOnlyPreview())
        assertEquals(listOf(8, 11), engine.getCurrentKeys())
    }

    @Test
    fun snapshotRestoreDoesNotEatPreviousKeyWhenToneCycleWrapsToNone() {
        engine.pressKey(8)
        engine.pressKey(7)
        engine.cycleTone() // ˇ
        engine.cycleTone() // ˋ
        engine.cycleTone() // ˊ
        engine.cycleTone() // ˙
        val before = engine.captureInputState()
        engine.cycleTone() // 繞回無聲調，keys 的尾端 11 被移除
        engine.restoreInputState(before)
        // 舊做法 backspace() 此時會誤刪最後一個真實按鍵 7
        assertEquals(listOf(8, 7, 11), engine.getCurrentKeys())
    }

    @Test
    fun snapshotMismatchWhenAnotherKeyWasTypedMeanwhile() {
        engine.pressKey(8)
        val after = engine.captureInputState()
        engine.pressKey(5) // 另一隻手指又輸入了別的鍵
        assertFalse("狀態已變，不可整體還原", engine.isSameInputState(after))
    }

    @Test
    fun restoreToEmptyClearsComposing() {
        val before = engine.captureInputState() // 閒置狀態
        engine.pressKey(8)
        engine.restoreInputState(before)
        assertFalse(engine.hasComposing())
        assertEquals("", engine.getKeyOnlyPreview())
        assertTrue(engine.getCandidates().isEmpty())
    }

    @Test
    fun testCapsulePreviewStrictlyEqualsKeyOnlyPreviewAndNeverUsesPredictions() {
        // 核心鐵律：無論候選列預測出多長的詞，九鍵輸入狀態與頂部膠囊注音符號數永遠等於按鍵數，絕不可把預測當輸入
        engine.pressKey(8) // ㄏ ㄒ ㄠ ㄡ 鍵，候選第一名可能是「會議」等長詞
        val candidates = engine.getCandidates()
        assertTrue("候選詞必須有預測內容", candidates.isNotEmpty())
        val topCandidate = candidates.first()

        val pureKeyPreview = engine.getKeyOnlyPreview()
        assertEquals("按 1 鍵時預覽必須恰為 1 個符號", 1, pureKeyPreview.length)
        assertEquals("ㄏ", pureKeyPreview)

        // 驗證絕不可被 topCandidate.zhuyin 污染
        if (topCandidate.zhuyin.contains(" ")) {
            assertFalse("輸入狀態絕不可包含多音節預測詞的注音", pureKeyPreview.contains(" "))
        }
    }
}
