package com.bopomofo.t9ime.engine

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * 預先訓練的詞對（bigram）關聯強度表：給定「前一個詞」與「候選詞」，回傳一個以 nat 為單位的對數加分。
 * 先查「詞 → 詞」；沒收錄時退一步查「前詞最後一字 → 候選詞第一字」的字元層級後備（檔案裡以前綴 U+0001
 * 與真實詞對分開，強度在建檔時已乘上後備倍率）。兩者都沒有就回傳 0（不加分也不扣分，退回只看詞頻）。
 *
 * 檔案格式 BGM1（little-endian），由 tools/bigram/build_model.py 產生：
 *   [0..3]   魔術字 "BGM1"
 *   [4..5]   格式版本（u16，目前 1）
 *   [6..7]   保留
 *   [8..11]  詞對數量 N（u32）
 *   [12..15] 保留
 *   [16 .. 16+8N)    N 個 64 位元雜湊鍵（有號 Long，由小到大排序）
 *   [16+8N .. 16+9N) N 個位元組：關聯強度，單位 0.02 nat（byte / 50.0 即加分）
 * 雜湊鍵 = FNV-1a 64 位元，輸入為 UTF-8(前詞) + 0x00 + UTF-8(候選詞)。
 * 64 位元雜湊對 50 萬組詞對的碰撞機率約 1e-8，查不存在的詞對誤中機率約 1e-14，可忽略。
 */
class BigramModel private constructor(
    private val keys: LongBuffer,
    private val weights: ByteBuffer,
    val count: Int
) {
    /**
     * 分桶索引：鍵是均勻的雜湊值，取前 16 位元當桶號（因為鍵以有號 Long 排序，桶號要把最高位元翻轉才會單調遞增），
     * bucketStart[b] 是第一個桶號 ≥ b 的位置。查詢時只在大小約 count/65536（約 8）的小範圍內二分搜尋，
     * 比整個 50 萬筆二分搜尋少約 4 倍比較次數。索引只佔 256 KB，載入時掃一遍鍵即可建立。
     */
    private val bucketStart = IntArray(BUCKETS + 1)

    init {
        var b = 0
        for (i in 0 until count) {
            val bucket = bucketOf(keys.get(i))
            while (b <= bucket) { bucketStart[b] = i; b++ }
        }
        while (b <= BUCKETS) { bucketStart[b] = count; b++ }
    }

    /** 回傳 prev → cur 的對數加分（nat，≥ 0）。 */
    fun bonus(prev: String, cur: String): Double {
        if (count == 0) return 0.0
        val w = lookup(hash(prev, cur))
        if (w >= 0) return w / 50.0
        if (prev.isEmpty() || cur.isEmpty()) return 0.0
        val x = prev[prev.length - 1]
        val y = cur[0]
        if (x.isSurrogate() || y.isSurrogate()) return 0.0
        val c = lookup(hashChars(x, y))
        return if (c >= 0) c / 50.0 else 0.0
    }

    /** 二分搜尋；找到回傳 0..255 的強度位元組，找不到回傳 -1。 */
    private fun lookup(key: Long): Int {
        val bucket = bucketOf(key)
        var lo = bucketStart[bucket]
        var hi = bucketStart[bucket + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = keys.get(mid)
            if (v < key) lo = mid + 1
            else if (v > key) hi = mid - 1
            else return weights.get(mid).toInt() and 0xFF
        }
        return -1
    }

    companion object {
        private const val HEADER = 16
        private const val BUCKETS = 65536

        private fun bucketOf(key: Long): Int = ((key ushr 48).toInt()) xor 0x8000
        private const val FNV_OFFSET = -3750763034362895579L   // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L

        /** 格式錯誤、版本不認得或檔案被截斷時回傳 null，呼叫端應退回沒有模型的狀態。 */
        fun load(buffer: ByteBuffer): BigramModel? {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (b.limit() < HEADER) return null
            if (b.get(0) != 'B'.code.toByte() || b.get(1) != 'G'.code.toByte() ||
                b.get(2) != 'M'.code.toByte() || b.get(3) != '1'.code.toByte()) return null
            if ((b.getShort(4).toInt() and 0xFFFF) != 1) return null
            val n = b.getInt(8)
            if (n < 0 || b.limit().toLong() != HEADER + 9L * n) return null
            b.position(HEADER).limit(HEADER + 8 * n)
            val keys = b.slice().order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
            b.limit(HEADER + 9 * n).position(HEADER + 8 * n)
            val weights = b.slice()
            return BigramModel(keys, weights, n)
        }

        fun load(bytes: ByteArray): BigramModel? = load(ByteBuffer.wrap(bytes))
        fun load(stream: InputStream): BigramModel? = load(stream.readBytes())
        fun load(file: File): BigramModel? = if (file.isFile) load(file.readBytes()) else null

        fun hash(prev: String, cur: String): Long {
            var h = FNV_OFFSET
            h = feed(h, prev)
            h = (h xor 0L) * FNV_PRIME
            return feed(h, cur)
        }

        private fun mix(h: Long, byte: Int): Long = (h xor byte.toLong()) * FNV_PRIME

        /** 字元層級後備的鍵：UTF-8(U+0001 + 前字) + 0x00 + UTF-8(後字)，與 build_model.py 的 fnv('\u0001'+x, y) 一致。 */
        fun hashChars(prevLast: Char, curFirst: Char): Long {
            var h = mix(FNV_OFFSET, 0x01)
            h = feedCodePoint(h, prevLast.code)
            h = (h xor 0L) * FNV_PRIME
            return feedCodePoint(h, curFirst.code)
        }

        /** 逐字元以 UTF-8 規則餵進雜湊，不產生暫存陣列。 */
        private fun feed(h0: Long, s: String): Long {
            var h = h0
            var i = 0
            while (i < s.length) {
                var cp = s[i].code
                if (cp in 0xD800..0xDBFF && i + 1 < s.length) {
                    val lo = s[i + 1].code
                    if (lo in 0xDC00..0xDFFF) {
                        cp = 0x10000 + ((cp - 0xD800) shl 10) + (lo - 0xDC00)
                        i++
                    }
                }
                i++
                h = feedCodePoint(h, cp)
            }
            return h
        }

        private fun feedCodePoint(h0: Long, cp: Int): Long {
            var h = h0
            when {
                cp < 0x80 -> h = mix(h, cp)
                cp < 0x800 -> { h = mix(h, 0xC0 or (cp shr 6)); h = mix(h, 0x80 or (cp and 0x3F)) }
                cp < 0x10000 -> {
                    h = mix(h, 0xE0 or (cp shr 12)); h = mix(h, 0x80 or ((cp shr 6) and 0x3F)); h = mix(h, 0x80 or (cp and 0x3F))
                }
                else -> {
                    h = mix(h, 0xF0 or (cp shr 18)); h = mix(h, 0x80 or ((cp shr 12) and 0x3F))
                    h = mix(h, 0x80 or ((cp shr 6) and 0x3F)); h = mix(h, 0x80 or (cp and 0x3F))
                }
            }
            return h
        }
    }
}
