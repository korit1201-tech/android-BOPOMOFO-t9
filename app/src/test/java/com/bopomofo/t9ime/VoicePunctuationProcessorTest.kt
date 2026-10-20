package com.bopomofo.t9ime

import com.bopomofo.t9ime.voice.VoicePunctuationProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePunctuationProcessorTest {

    @Test
    fun testCommaConversion() {
        assertEquals("你好，今天天氣真好", VoicePunctuationProcessor.process("你好逗號今天天氣真好"))
        assertEquals("早安，吃飽了嗎", VoicePunctuationProcessor.process("早安逗點吃飽了嗎"))
        assertEquals("你好，今天天氣真好", VoicePunctuationProcessor.process("你好 逗號 今天天氣真好"))
    }

    @Test
    fun testPeriodConversion() {
        assertEquals("今天天氣真好。", VoicePunctuationProcessor.process("今天天氣真好句號"))
        assertEquals("明天見。", VoicePunctuationProcessor.process("明天見句點"))
    }

    @Test
    fun testQuestionAndExclamation() {
        assertEquals("你吃了沒？", VoicePunctuationProcessor.process("你吃了沒問號"))
        assertEquals("太棒了！", VoicePunctuationProcessor.process("太棒了驚嘆號"))
        assertEquals("不可思議！", VoicePunctuationProcessor.process("不可思議感嘆號"))
    }

    @Test
    fun testNewlineAndSpace() {
        assertEquals("第一行\n第二行", VoicePunctuationProcessor.process("第一行換行第二行"))
        assertEquals("第一行\n第二行", VoicePunctuationProcessor.process("第一行下一行第二行"))
        assertEquals("哈囉 世界", VoicePunctuationProcessor.process("哈囉空格世界"))
        assertEquals("哈囉 世界", VoicePunctuationProcessor.process("哈囉空白世界"))
    }

    @Test
    fun testCompositeSentence() {
        val input = "大家早安句號換行今天報告第一點冒號空格準時出席句點"
        val expected = "大家早安。\n今天報告第一點： 準時出席。"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun testQuotesAndDunHao() {
        val input = "採購項目冒號蘋果頓號香蕉頓號西瓜"
        val expected = "採購項目：蘋果、香蕉、西瓜"
        assertEquals(expected, VoicePunctuationProcessor.process(input))

        val quoteInput = "他說左引號好的右引號"
        val quoteExpected = "他說「好的」"
        assertEquals(quoteExpected, VoicePunctuationProcessor.process(quoteInput))
    }
}
