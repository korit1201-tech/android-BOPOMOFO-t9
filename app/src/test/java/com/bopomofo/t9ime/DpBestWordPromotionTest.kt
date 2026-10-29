package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 九鍵整句 DP 的最佳路徑若本身就是詞庫詞（綠燈），不能被「兩個高頻單字拼起來」（風等）壓到後面。 */
class DpBestWordPromotionTest {
    private lateinit var e: ZhuyinT9Engine

    @Before fun setUp() {
        UserDictionaryManager.getInstance(null).clearDictionary()
        e = ZhuyinT9Engine(null)
        e.loadDictionaryForTest()
    }

    private fun top(zhuyin: String): String {
        e.clear()
        var last = emptyList<com.bopomofo.t9ime.engine.DictEntry>()
        for (k in KeyMapping.getSequence(zhuyin, ignoreTones = true)) last = e.pressKey(k)
        return last.first().word
    }

    @Test fun greenLightBeatsTwoFrequentChars() {
        assertEquals("綠燈", top("ㄌㄩㄉㄥ"))
    }
}
