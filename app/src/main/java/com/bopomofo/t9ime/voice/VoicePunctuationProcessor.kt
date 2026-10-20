package com.bopomofo.t9ime.voice

/**
 * 語音口述標點符號與排版控制字元轉換器
 * 支援將口述的「逗號、句號、問號、驚嘆號、頓號、換行、空格」等即時轉為標點
 */
object VoicePunctuationProcessor {

    fun process(raw: String): String {
        if (raw.isEmpty()) return raw

        var text = raw

        // 1. 全形標點符號指令（自動清除全形標點兩側的多餘半形空白）
        // 逗號 / 逗點
        text = text.replace(Regex("[，,]?\\s*(逗[號點])\\s*[，,]?"), "，")
        // 句號 / 句點
        text = text.replace(Regex("[。.]?\\s*(句[號點])\\s*[。.]?"), "。")
        // 問號 / 疑問號
        text = text.replace(Regex("[？?]?\\s*(問號|疑問號)\\s*[？?]?"), "？")
        // 驚嘆號 / 感嘆號
        text = text.replace(Regex("[！!]?\\s*(驚嘆號|感嘆號)\\s*[！!]?"), "！")
        // 頓號
        text = text.replace(Regex("[、,]?\\s*頓號\\s*[、,]?"), "、")
        // 冒號
        text = text.replace(Regex("[：:]?\\s*冒號\\s*[：:]?"), "：")
        // 分號
        text = text.replace(Regex("[；;]?\\s*分號\\s*[；;]?"), "；")
        // 刪節號 / 省略號 / 點點點
        text = text.replace(Regex("\\s*(刪節號|省略號|點點點)\\s*"), "……")
        // 破折號
        text = text.replace(Regex("\\s*破折號\\s*"), "——")
        // 波浪號
        text = text.replace(Regex("\\s*波浪號\\s*"), "～")
        // 引號
        text = text.replace(Regex("\\s*(左引號|上引號|開引號)\\s*"), "「")
        text = text.replace(Regex("\\s*(右引號|下引號|關引號)\\s*"), "」")

        // 2. 換行與空格排版指令（不吞噬前置標點）
        text = text.replace(Regex("\\s*(換下一行|換行|下一行)\\s*"), "\n")
        text = text.replace(Regex("(空格|空白)"), " ")

        // 3. 清理多餘換行旁半形空格與連續重複標點
        text = text.replace(Regex(" +\\n"), "\n")
        text = text.replace(Regex("\\n +"), "\n")
        text = text.replace(Regex("，+"), "，")
        text = text.replace(Regex("。+"), "。")
        text = text.replace(Regex("？+"), "？")
        text = text.replace(Regex("！+"), "！")
        text = text.replace(Regex("、+"), "、")
        text = text.replace(Regex("，。"), "。")
        text = text.replace(Regex("。，"), "。")

        return text
    }
}
