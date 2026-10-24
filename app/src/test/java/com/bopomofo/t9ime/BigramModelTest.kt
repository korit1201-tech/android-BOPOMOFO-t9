package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.BigramModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BigramModelTest {
    /** 依檔案格式在記憶體裡組一份模型：與 tools/bigram/build_model.py 輸出的結構一致。 */
    private fun build(vararg pairs: Triple<String, String, Int>): ByteArray =
        buildRaw(pairs.map { BigramModel.hash(it.first, it.second) to it.third })

    private fun buildRaw(entries: List<Pair<Long, Int>>): ByteArray {
        val rows = entries.sortedBy { it.first }
        val buf = ByteBuffer.allocate(16 + 9 * rows.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("BGM1".toByteArray()).putShort(1).putShort(0).putInt(rows.size).putInt(0)
        rows.forEach { buf.putLong(it.first) }
        rows.forEach { buf.put(it.second.toByte()) }
        return buf.array()
    }

    @Test
    fun hashMatchesKnownPythonValue() {
        // 與 tools/bigram/build_model.py 的 fnv("想","吃") 逐位元一致（跨語言雜湊必須相同，否則模型全部查不到）
        assertEquals(PY_HASH_XIANG_CHI, BigramModel.hash("想", "吃"))
    }

    @Test
    fun charKeyMatchesKnownPythonValue() {
        // 與 build_model.py 的 fnv("\u0001想","吃") 一致：字元層級後備的鍵
        assertEquals(PY_HASH_CHAR_XIANG_CHI, BigramModel.hashChars('想', '吃'))
        assertEquals(BigramModel.hash("\u0001想", "吃"), BigramModel.hashChars('想', '吃'))
    }

    @Test
    fun fallsBackToCharacterPairWhenWordPairMissing() {
        val model = BigramModel.load(buildRaw(listOf(
            BigramModel.hash("想", "吃") to 100,                    // 詞對：2.0 nat
            BigramModel.hashChars('想', '吃') to 40,                // 字元後備：0.8 nat
            BigramModel.hashChars('很', '累') to 30)))              // 只有字元後備：0.6 nat
        assertEquals(2.0, model!!.bonus("想", "吃"), 1e-9)         // 詞對優先
        assertEquals(0.8, model.bonus("好想", "吃飯"), 1e-9)       // 沒有「好想→吃飯」，用「想→吃」字元後備
        assertEquals(0.6, model.bonus("真的很", "累了"), 1e-9)
        assertEquals(0.0, model.bonus("喝", "水"), 1e-9)
        assertEquals(0.0, model.bonus("", "吃"), 1e-9)            // 空字串不當機
        assertEquals(0.0, model.bonus("想", ""), 1e-9)
    }

    @Test
    fun lookupFindsStoredPairsAndMissesOthers() {
        val model = BigramModel.load(build(
            Triple("想", "吃", 120), Triple("天氣", "很", 200), Triple("幫", "我", 255), Triple("不", "我", 0)))
        assertNotNull(model)
        assertEquals(4, model!!.count)
        assertEquals(2.4, model.bonus("想", "吃"), 1e-9)
        assertEquals(4.0, model.bonus("天氣", "很"), 1e-9)
        assertEquals(5.1, model.bonus("幫", "我"), 1e-9)      // 位元組 255 要當無號數讀
        assertEquals(0.0, model.bonus("不", "我"), 1e-9)
        assertEquals(0.0, model.bonus("想", "喝"), 1e-9)      // 沒收錄 → 不加分
        assertEquals(0.0, model.bonus("吃", "想"), 1e-9)      // 方向不同是不同的詞對
    }

    @Test
    fun bucketIndexFindsEveryKeyIncludingNegativeHashes() {
        // 大量隨機詞對：每一筆都要找得到、強度正確；沒收錄的不可誤中（涵蓋雜湊為負數與各個桶的邊界）
        val rnd = java.util.Random(42)
        fun word() = (1..(1 + rnd.nextInt(3))).map { (0x4E00 + rnd.nextInt(5000)).toChar() }.joinToString("")
        val stored = LinkedHashMap<Pair<String, String>, Int>()
        while (stored.size < 20000) stored[word() to word()] = rnd.nextInt(256)
        val model = BigramModel.load(build(*stored.map { Triple(it.key.first, it.key.second, it.value) }.toTypedArray()))!!
        for ((k, v) in stored) assertEquals("${k.first}→${k.second}", v / 50.0, model.bonus(k.first, k.second), 1e-9)
        var falseHits = 0
        repeat(20000) {
            val a = word(); val b = word()
            if ((a to b) !in stored && model.bonus(a, b) != 0.0) falseHits++
        }
        assertEquals(0, falseHits)
    }

    @Test
    fun rejectsMalformedFiles() {
        val good = build(Triple("想", "吃", 100))
        assertNull(BigramModel.load(good.copyOf(good.size - 1)))             // 被截斷
        assertNull(BigramModel.load(byteArrayOf(1, 2, 3)))                   // 太短
        val badMagic = good.copyOf(); badMagic[0] = 'X'.code.toByte()
        assertNull(BigramModel.load(badMagic))
        val badVersion = good.copyOf(); badVersion[4] = 9
        assertNull(BigramModel.load(badVersion))
    }

    @Test
    fun emptyModelNeverMatches() {
        val model = BigramModel.load(build())
        assertNotNull(model)
        assertEquals(0.0, model!!.bonus("想", "吃"), 1e-9)
    }

    companion object {
        // python3: fnv("想","吃") 轉有號 64 位元；由 tools/bigram/build_model.py 的 fnv() 計算
        const val PY_HASH_XIANG_CHI = -3426057848390620631L
        const val PY_HASH_CHAR_XIANG_CHI = -3692900150060187810L
    }
}
