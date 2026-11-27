package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.BigramModel
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.KeyNeighbors
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Test
import java.io.File
import java.util.Random

/** 糾錯成本與「排第一」差距的參數掃描（只輸出 `SWEEP` 行，沒有斷言）。九鍵不打聲調鍵、雜訊 10% 與乾淨輸入各一組。 */
class SlipPenaltySweepTest {
    @Test
    fun sweep() {
        // 約 25 分鐘，平常不跑；要掃參數時設 SLIPBENCH_SWEEP=1
        org.junit.Assume.assumeTrue(System.getenv("SLIPBENCH_SWEEP") == "1")
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest(File("src/main/assets/dict_tw.txt"))
        engine.setBigramModel(BigramModel.load(File("src/main/assets/bigram_core.bin")))
        engine.bigramScaleT9Cost = 3.0; engine.bigramScaleT9Weight = 6.0
        engine.bigramScaleFull = 4.0; engine.bigramScaleSentence = 1.0; engine.fullNoToneFirstBonus = 30_000
        class W(val word: String, val zy: String, val ctx: String?)
        val words = ArrayList<W>()
        for (line in javaClass.classLoader.getResourceAsStream("bigram_eval.tsv")!!.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }) {
            val p = line.split('\t'); val ws = p[1].split('|'); val zy = p[2].split('|')
            var ctx: String? = null
            for (i in ws.indices) { words.add(W(ws[i], zy[i], ctx)); ctx = ws[i] }
        }
        fun noisy(keys: List<Int>, rng: Random): List<Int> {
            if (rng.nextDouble() >= 0.10) return keys
            val ok = keys.indices.filter { KeyNeighbors.neighbors(keys[it]).isNotEmpty() }
            if (ok.isEmpty()) return keys
            val pos = ok[rng.nextInt(ok.size)]; val near = KeyNeighbors.neighbors(keys[pos])
            return keys.toMutableList().also { it[pos] = near[rng.nextInt(near.size)] }
        }
        fun run(pen: Double, margin: Double, level: Int, noise: Boolean): Pair<Double, Double> {
            engine.slipPenaltyBase = pen; engine.slipFirstMargin = margin
            val rng = Random(20261125L + 100)
            var n = 0; var t1 = 0; var t5 = 0
            for (w in words) {
                val clean = KeyMapping.getSequence(w.zy, ignoreTones = true)
                val keys = if (noise) noisy(clean, rng) else clean
                engine.clear(); engine.currentContextWord = w.ctx
                for (k in keys) engine.pressKey(k)
                val c = (if (level > 0) engine.applySlipCorrection(level) else null) ?: engine.getCandidates()
                val r = c.indexOfFirst { it.word == w.word }
                n++; if (r == 0) t1++; if (r in 0..4) t5++
            }
            return 100.0 * t1 / n to 100.0 * t5 / n
        }
        val off = run(5.0, 2.0, 0, true); val offClean = run(5.0, 2.0, 0, false)
        println("SWEEP 關：雜訊10% 第1名 ${"%.2f".format(off.first)} 前5名 ${"%.2f".format(off.second)}；乾淨 第1名 ${"%.2f".format(offClean.first)} 前5名 ${"%.2f".format(offClean.second)}")
        for (pen in listOf(2.5, 3.5, 5.0, 6.5, 8.0)) {
            for (margin in listOf(0.0, 2.0, 4.0)) {
                val n = run(pen, margin, 2, true); val c = run(pen, margin, 2, false)
                println("SWEEP 標準 成本=$pen 差距=$margin：雜訊10% 第1名 ${"%+.2f".format(n.first - off.first)} 前5名 ${"%+.2f".format(n.second - off.second)}；乾淨 第1名 ${"%+.2f".format(c.first - offClean.first)} 前5名 ${"%+.2f".format(c.second - offClean.second)}")
            }
            val n = run(pen, 2.0, 1, true); val c = run(pen, 2.0, 1, false)
            println("SWEEP 保守 成本=$pen：雜訊10% 前5名 ${"%+.2f".format(n.second - off.second)}；乾淨 第1名 ${"%+.2f".format(c.first - offClean.first)} 前5名 ${"%+.2f".format(c.second - offClean.second)}")
        }
    }
}
