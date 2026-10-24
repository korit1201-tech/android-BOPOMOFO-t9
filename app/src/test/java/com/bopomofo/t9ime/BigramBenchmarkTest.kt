package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.BigramModel
import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Test
import java.io.File

/**
 * 候選排序基準評測（bigram 計畫的量尺，見 docs/BIGRAM_LM_PLAN.md）。
 *
 * 評測資料 `bigram_eval.tsv`：600 個子句，取自與訓練語料完全分開的 130 篇驗證文章
 * （來源 polobread/KeyKey 的 AI 合成文章，MIT 授權，Copyright (c) 2026 Chui-Ping Cheng；
 * 已檢查沒有任何子句逐字出現在訓練語料）。每列：子句 \t 詞（| 分隔）\t 各詞注音（| 分隔）。
 *
 * 模擬逐詞輸入：每個詞先清空輸入、依模式輸入該詞，上下文詞 = 同一子句的前一個詞（第一個詞無上下文）。
 * 模式：九鍵（含聲調鍵／不含）、全鍵盤（含聲調／不含，使用者通常不打聲調）。
 * 引擎不開個人化學習，所以量到的是「冷啟動」的內建排序品質。
 *
 * 輸出 `BENCH ...` 行並寫入 build/bigram-bench.json，方便前後比較。
 */
class BigramBenchmarkTest {
    private class Clause(val words: List<String>, val zhuyin: List<String>)

    private fun loadClauses(): List<Clause> {
        val stream = javaClass.classLoader.getResourceAsStream("bigram_eval.tsv")
            ?: error("找不到測試資源 bigram_eval.tsv")
        return stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.map {
            val p = it.split('\t')
            Clause(p[1].split('|'), p[2].split('|'))
        }
    }

    private class Score {
        val misses = ArrayList<String>()
        var n = 0; var top1 = 0; var top5 = 0; var rankSum = 0
        var charOk = 0; var charN = 0
        var nanos = 0L
        val rankCap = 10
        fun add(target: String, cands: List<DictEntry>, ctx: String? = null) {
            n++
            val rank = cands.indexOfFirst { it.word == target }
            if (rank != 0) misses.add("${ctx ?: "-"}\t$target\t${if (rank < 0) -1 else rank + 1}\t${cands.take(3).joinToString("/") { it.word }}")
            if (rank == 0) top1++
            if (rank in 0..4) top5++
            rankSum += if (rank < 0 || rank >= rankCap) rankCap else rank + 1
            val top = cands.firstOrNull()?.word
            charN += target.length
            if (top != null && top.length == target.length) charOk += target.indices.count { top[it] == target[it] }
        }
        fun pct(a: Int, b: Int) = if (b == 0) 0.0 else 100.0 * a / b
        fun line(label: String) =
            "BENCH [$label] 詞第1名 ${top1}/$n (${"%.1f".format(pct(top1, n))}%)  前5名 ${"%.1f".format(pct(top5, n))}%  " +
                "平均名次(上限$rankCap) ${"%.2f".format(rankSum.toDouble() / n)}  字元正確率 ${"%.1f".format(pct(charOk, charN))}%  " +
                "每次查詢 ${"%.2f".format(nanos / 1e6 / n)} ms"
        fun json() = """{"n":$n,"top1":${"%.2f".format(pct(top1, n))},"top5":${"%.2f".format(pct(top5, n))},""" +
            """"avgRank":${"%.3f".format(rankSum.toDouble() / n)},"charAcc":${"%.2f".format(pct(charOk, charN))}}"""
    }

    /** 一組評測設定：模型檔（空字串＝不用模型）與四個模式的強度倍率。 */
    private class Config(val label: String, val model: String, val t9Cost: Double, val t9Weight: Double,
                         val full: Double, val sentence: Double, val firstTone: Int = 5_000_000)

    /**
     * 環境變數 BENCH_CONFIGS 以分號分隔多組設定，每組：標籤|模型檔|九鍵成本|九鍵權重|全鍵盤|整句[|全鍵盤不打聲調時一聲字的固定加分]，
     * 例如 "df2 x1|/tmp/ref/model_df2.bin|1|1|1|1;df2 x0.5|/tmp/ref/model_df2.bin|.5|.5|.5|.5"。
     * 沒設定時只跑基準（不用模型，等於 v2.9.5 的排序）。結果寫到 BENCH_OUT（預設 build/bigram-bench.json）。
     */
    private fun configs(): List<Config> {
        val spec = System.getenv("BENCH_CONFIGS")
        if (spec.isNullOrBlank()) {
            // 預設：目前出貨的設定（內建模型 + 引擎預設強度與一聲加分），方便日常回歸；用 BENCH_CONFIGS 才做比較掃描
            return listOf(Config("出貨設定", "src/main/assets/bigram_core.bin", 3.0, 6.0, 4.0, 1.0, 30_000))
        }
        return spec.split(';').filter { it.isNotBlank() }.map {
            val p = it.split('|')
            Config(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(),
                if (p.size > 6) p[6].toInt() else 5_000_000)
        }
    }

    @Test
    fun benchmark() {
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest(File("src/main/assets/dict_tw.txt"))
        val clauses = loadClauses()
        val all = LinkedHashMap<String, Map<String, Score>>()
        println("BENCH 子句 ${clauses.size} 句，詞 ${clauses.sumOf { it.words.size }} 個")

        for (cfg in configs()) {
            engine.setBigramModel(if (cfg.model.isEmpty()) null else BigramModel.load(File(cfg.model)))
            engine.bigramScaleT9Cost = cfg.t9Cost
            engine.bigramScaleT9Weight = cfg.t9Weight
            engine.bigramScaleFull = cfg.full
            engine.bigramScaleSentence = cfg.sentence
            engine.fullNoToneFirstBonus = cfg.firstTone
            val results = LinkedHashMap<String, Score>()

            fun run(label: String, query: (zy: String, ctx: String?) -> List<DictEntry>) {
                val s = Score()
                for (c in clauses) {
                    var ctx: String? = null
                    for (i in c.words.indices) {
                        val t0 = System.nanoTime()
                        val cands = query(c.zhuyin[i], ctx)
                        s.nanos += System.nanoTime() - t0
                        s.add(c.words[i], cands, ctx)
                        ctx = c.words[i]
                    }
                }
                results[label] = s
                println(s.line("${cfg.label} ／ $label"))
            }

            fun nineKey(ignoreTones: Boolean) = { zy: String, ctx: String? ->
                engine.clear(); engine.currentContextWord = ctx
                var cands: List<DictEntry> = emptyList()
                for (k in KeyMapping.getSequence(zy, ignoreTones = ignoreTones)) cands = engine.pressKey(k)
                cands
            }
            fun fullKeyboard(withTones: Boolean) = { zy: String, ctx: String? ->
                engine.currentContextWord = ctx
                val z = zy.replace(" ", "")
                engine.searchFullZhuyin(if (withTones) z else z.filter { it !in "ˇˋˊ˙" }, ctx)
            }

            fun runSentence(label: String, withTones: Boolean) {
                val s = Score()
                for (c in clauses) {
                    engine.currentContextWord = null
                    val z = c.zhuyin.joinToString("").replace(" ", "")
                    val t0 = System.nanoTime()
                    val cands = engine.searchFullZhuyin(if (withTones) z else z.filter { it !in "ˇˋˊ˙" }, null)
                    s.nanos += System.nanoTime() - t0
                    s.add(c.words.joinToString(""), cands, null)
                }
                results[label] = s
                println(s.line("${cfg.label} ／ $label"))
            }

            run("全鍵盤 不打聲調", fullKeyboard(false))
            run("全鍵盤 打聲調", fullKeyboard(true))
            run("九鍵 不打聲調鍵", nineKey(true))
            run("九鍵 打聲調鍵", nineKey(false))
            runSentence("全鍵盤 整句 不打聲調", false)
            runSentence("全鍵盤 整句 打聲調", true)
            all[cfg.label] = results
        }

        System.getenv("BENCH_MISSES")?.takeIf { it.isNotBlank() }?.let { path ->
            val last = all.values.last()
            File(path).writeText(last.entries.joinToString("\n") { (mode, sc) ->
                sc.misses.joinToString("\n") { "$mode\t$it" }
            })
        }
        val out = File(System.getenv("BENCH_OUT") ?: "build/bigram-bench.json")
        out.parentFile?.mkdirs()
        out.writeText("{" + all.entries.joinToString(",") { (label, res) ->
            "\"$label\":{" + res.entries.joinToString(",") { "\"${it.key}\":${it.value.json()}" } + "}"
        } + "}")
    }
}
