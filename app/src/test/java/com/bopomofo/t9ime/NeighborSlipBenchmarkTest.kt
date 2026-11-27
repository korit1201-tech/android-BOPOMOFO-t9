package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.BigramModel
import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.KeyNeighbors
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * 按偏鄰鍵糾錯的評測與合併門檻（docs/CYIME_REFERENCE_PLAN.md 的 P0）。
 *
 * 資料與 [BigramBenchmarkTest] 相同（bigram_eval.tsv，600 句，與訓練語料分離）。逐詞輸入九鍵，
 * 以固定種子把每個詞的一個非聲調鍵換成上下左右相鄰的鍵（機率 0%、5%、10%），量三種糾錯檔位
 * （0 關、1 保守、2 標準）的詞第 1 名／前 5 名。引擎不開個人化學習（冷啟動排序品質）。
 *
 * 門檻（任一不過即測試失敗）：
 *  1. 乾淨輸入（0% 雜訊）任何檔位的第 1 名，不得比「關」退步超過 [CLEAN_TOP1_TOLERANCE] 個百分點。
 *  2. 有雜訊時，任何檔位的前 5 名不得比「關」更差。
 *  3. 與 test/resources/bench_baseline.json 的乾淨輸入第 1 名比，不得退步超過容許值（檔案存在時）。
 *  4. 糾錯那一步的平均耗時不得超過 [SLIP_MS_LIMIT]（JVM；手機要另外實測）。
 *
 * 輸出 `SLIPBENCH ...` 行並寫入 build/slip-bench.json。設 SLIPBENCH_WRITE_BASELINE=1 可重寫基準檔。
 */
class NeighborSlipBenchmarkTest {
    private class Word(val word: String, val zhuyin: String, val ctx: String?)

    private class Cell {
        var n = 0
        var top1 = 0
        var top5 = 0
        var slipNanos = 0L
        var slipCalls = 0
        fun add(target: String, cands: List<DictEntry>) {
            n++
            val rank = cands.indexOfFirst { it.word == target }
            if (rank == 0) top1++
            if (rank in 0..4) top5++
        }
        fun pct(a: Int) = if (n == 0) 0.0 else 100.0 * a / n
        val top1Pct get() = pct(top1)
        val top5Pct get() = pct(top5)
        val slipMs get() = if (slipCalls == 0) 0.0 else slipNanos / 1e6 / slipCalls
    }

    private fun loadWords(): List<Word> {
        val stream = javaClass.classLoader.getResourceAsStream("bigram_eval.tsv") ?: error("找不到測試資源 bigram_eval.tsv")
        val out = ArrayList<Word>()
        for (line in stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }) {
            val p = line.split('\t')
            val words = p[1].split('|')
            val zy = p[2].split('|')
            var ctx: String? = null
            for (i in words.indices) {
                out.add(Word(words[i], zy[i], ctx))
                ctx = words[i]
            }
        }
        return out
    }

    /** 以 [p] 的機率把一個非聲調鍵換成鄰鍵；回傳新鍵序（聲調鍵位置不變） */
    private fun addNoise(keys: List<Int>, p: Double, rng: Random): List<Int> {
        if (p <= 0.0 || rng.nextDouble() >= p) return keys
        val slippable = keys.indices.filter { keys[it] != 11 && KeyNeighbors.neighbors(keys[it]).isNotEmpty() }
        if (slippable.isEmpty()) return keys
        val pos = slippable[rng.nextInt(slippable.size)]
        val near = KeyNeighbors.neighbors(keys[pos])
        val out = keys.toMutableList()
        out[pos] = near[rng.nextInt(near.size)]
        return out
    }

    @Test
    fun benchmark() {
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest(File("src/main/assets/dict_tw.txt"))
        engine.setBigramModel(BigramModel.load(File("src/main/assets/bigram_core.bin")))
        engine.bigramScaleT9Cost = 3.0
        engine.bigramScaleT9Weight = 6.0
        engine.bigramScaleFull = 4.0
        engine.bigramScaleSentence = 1.0
        engine.fullNoToneFirstBonus = 30_000

        val words = loadWords()
        val noises = listOf(0.0, 0.05, 0.10)
        val modes = listOf("九鍵 不打聲調鍵" to true, "九鍵 打聲調鍵" to false)
        val levels = listOf(0, 1, 2)
        val levelName = mapOf(0 to "關", 1 to "保守", 2 to "標準")
        val results = LinkedHashMap<String, Cell>()
        println("SLIPBENCH 詞 ${words.size} 個；雜訊 ${noises.joinToString { "${(it * 100).toInt()}%" }}")

        for ((modeName, ignoreTones) in modes) {
            for (p in noises) {
                val rng = Random(20261125L + (p * 1000).toInt())
                val cells = levels.associateWith { Cell() }
                for (w in words) {
                    val clean = KeyMapping.getSequence(w.zhuyin, ignoreTones = ignoreTones)
                    val keys = addNoise(clean, p, rng)
                    engine.clear()
                    engine.currentContextWord = w.ctx
                    for (k in keys) engine.pressKey(k)
                    val snapshot = engine.captureInputState()
                    val base = engine.getCandidates()
                    for (level in levels) {
                        if (level == 0) {
                            cells[0]!!.add(w.word, base)
                            continue
                        }
                        engine.restoreInputState(snapshot)
                        val t0 = System.nanoTime()
                        val r = engine.applySlipCorrection(level)
                        val c = cells[level]!!
                        c.slipNanos += System.nanoTime() - t0
                        c.slipCalls++
                        c.add(w.word, r ?: base)
                    }
                }
                for (level in levels) {
                    val c = cells[level]!!
                    results["$modeName|${(p * 100).toInt()}%|${levelName[level]}"] = c
                    println(
                        "SLIPBENCH [$modeName ／ 雜訊 ${(p * 100).toInt()}% ／ 糾錯${levelName[level]}] " +
                            "詞第1名 ${c.top1}/${c.n} (${"%.1f".format(c.top1Pct)}%)  前5名 ${"%.1f".format(c.top5Pct)}%" +
                            (if (level > 0) "  糾錯耗時 ${"%.2f".format(c.slipMs)} ms" else "")
                    )
                }
                if (p > 0.0) {
                    val off = cells[0]!!
                    val clean = results["$modeName|0%|關"]!!
                    for (level in 1..2) {
                        val c = cells[level]!!
                        val gap5 = clean.top5Pct - off.top5Pct
                        val gain5 = c.top5Pct - off.top5Pct
                        val gap1 = clean.top1Pct - off.top1Pct
                        val gain1 = c.top1Pct - off.top1Pct
                        println(
                            "SLIPBENCH   回補 [$modeName ／ ${(p * 100).toInt()}% ／ ${levelName[level]}] " +
                                "前5名 +${"%.1f".format(gain5)}（差距 ${"%.1f".format(gap5)}，回補 ${"%.0f".format(if (gap5 > 0) 100 * gain5 / gap5 else 0.0)}%）  " +
                                "第1名 ${"%+.1f".format(gain1)}（差距 ${"%.1f".format(gap1)}，回補 ${"%.0f".format(if (gap1 > 0) 100 * gain1 / gap1 else 0.0)}%）"
                        )
                    }
                }
            }
        }

        val out = File("build/slip-bench.json")
        out.parentFile?.mkdirs()
        out.writeText("{" + results.entries.joinToString(",") { (k, c) ->
            "\"$k\":{\"n\":${c.n},\"top1\":${"%.2f".format(c.top1Pct)},\"top5\":${"%.2f".format(c.top5Pct)},\"slipMs\":${"%.3f".format(c.slipMs)}}"
        } + "}")

        if (System.getenv("SLIPBENCH_WRITE_BASELINE") == "1") {
            val b = File("src/test/resources/bench_baseline.json")
            b.writeText("{" + modes.joinToString(",") { (m, _) ->
                "\"$m\":{\"cleanTop1\":${"%.2f".format(results["$m|0%|關"]!!.top1Pct)}}"
            } + "}\n")
            println("SLIPBENCH 已重寫基準檔 ${b.path}")
        }

        // ── 門檻 ──
        val failures = ArrayList<String>()
        for ((modeName, _) in modes) {
            val cleanOff = results["$modeName|0%|關"]!!
            for (level in 1..2) {
                val c = results["$modeName|0%|${levelName[level]}"]!!
                if (c.top1Pct < cleanOff.top1Pct - CLEAN_TOP1_TOLERANCE) {
                    failures.add("[$modeName] 乾淨輸入糾錯${levelName[level]}第1名 ${"%.2f".format(c.top1Pct)}% 比關閉 ${"%.2f".format(cleanOff.top1Pct)}% 退步超過 $CLEAN_TOP1_TOLERANCE")
                }
                if (c.slipMs > SLIP_MS_LIMIT) failures.add("[$modeName] 糾錯${levelName[level]}耗時 ${"%.2f".format(c.slipMs)} ms 超過 $SLIP_MS_LIMIT ms")
                for (noise in listOf(5, 10)) {
                    val off = results["$modeName|$noise%|關"]!!
                    val on = results["$modeName|$noise%|${levelName[level]}"]!!
                    if (on.top5Pct < off.top5Pct) {
                        failures.add("[$modeName] 雜訊 $noise% 糾錯${levelName[level]}前5名 ${"%.2f".format(on.top5Pct)}% 比關閉 ${"%.2f".format(off.top5Pct)}% 更差")
                    }
                }
            }
        }
        val baseline = File("src/test/resources/bench_baseline.json")
        if (baseline.exists()) {
            val text = baseline.readText()
            for ((modeName, _) in modes) {
                val m = Regex("\"" + Regex.escape(modeName) + "\":\\{\"cleanTop1\":([0-9.]+)").find(text) ?: continue
                val base = m.groupValues[1].toDouble()
                val now = results["$modeName|0%|關"]!!.top1Pct
                if (now < base - CLEAN_TOP1_TOLERANCE) {
                    failures.add("[$modeName] 乾淨輸入第1名 ${"%.2f".format(now)}% 比基準 ${"%.2f".format(base)}% 退步超過 $CLEAN_TOP1_TOLERANCE")
                }
            }
        }
        assertTrue("評測門檻未過：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        const val CLEAN_TOP1_TOLERANCE = 0.2
        const val SLIP_MS_LIMIT = 30.0
    }
}
