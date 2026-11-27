package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 詞庫補強（以現代語料重新估計被舊語料低估的詞頻）：現代常用詞不必靠學習就該排在前面。 */
class ModernWordsRankingTest {
    private lateinit var e: ZhuyinT9Engine

    @Before
    fun setUp() { UserDictionaryManager.getInstance(null).clearDictionary(); e = ZhuyinT9Engine(null); e.loadDictionaryForTest() }

    @Test
    fun handsetIsFirstInFullKeyboardWithTone() {
        assertEquals("手機", e.searchFullZhuyin("ㄕㄡˇㄐㄧ").first().word)
    }

    @Test
    fun handsetBeatsRareHomophonesOnNineKeyWithoutTone() {
        e.clear()
        "ㄕㄡㄐㄧ".forEach { e.pressKey(KeyMapping.getKeyId(it)!!) }
        val words = e.getCandidates().map { it.word }
        val rank = words.indexOf("手機") + 1
        assertTrue("手機 應在前 3 名，實際第 $rank 名：${words.take(6)}", rank in 1..3)
    }

    @Test
    fun weightsOfModernWordsAreNoLongerNearZero() {
        val w = HashMap<String, Int>()
        java.io.File("src/main/assets/dict_tw.txt").forEachLine {
            val p = it.split('\t'); if (p.size >= 3) w[p[0]] = maxOf(w[p[0]] ?: 0, p[2].toIntOrNull() ?: 0)
        }
        for (word in listOf("手機", "社群", "群組", "登入", "網站", "網友", "景點", "物流"))
            assertTrue("$word 的權重不該還是接近 0：${w[word]}", (w[word] ?: 0) >= 1000)
        assertTrue("補強只能調高，不可把常用詞調低", (w["電腦"] ?: 0) >= 77345 && (w["網路"] ?: 0) >= 107919)
    }
}
