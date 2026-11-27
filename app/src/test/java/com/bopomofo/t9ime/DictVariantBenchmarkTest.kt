package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.BigramModel
import com.bopomofo.t9ime.engine.DictEntry
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 詞庫版本比較（本機開發用，預設跳過）：同一批題目、多個「詞庫＋詞對模型」版本，兩份評測集各跑一次。
 *  - bigram_eval.tsv：AI 合成文章切出的 600 句
 *  - tw_eval.tsv：twngrams（CC0，真實台灣網頁語料）2-gram 分層抽樣的 4000 句，只收原始詞庫就有且讀音明確的詞
 * 環境變數 BENCH_VARIANTS：以分號分隔，每組 "標籤|詞庫路徑|詞對模型路徑[|補缺詞清單路徑]"。
 * 補缺詞清單（TSV：詞、讀音、權重、網站數）會另外量「新詞能不能被打出來」。
 */
class DictVariantBenchmarkTest {
    private class Clause(val words: List<String>, val zhuyin: List<String>)

    private fun load(res: String): List<Clause> =
        javaClass.classLoader.getResourceAsStream(res)!!.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
            .map { val p = it.split('\t'); Clause(p[1].split('|'), p[2].split('|')) }

    private class Score { var n = 0; var top1 = 0; var top5 = 0
        fun add(target: String, c: List<DictEntry>) { n++; val r = c.indexOfFirst { it.word == target }; if (r == 0) top1++; if (r in 0..4) top5++ }
        fun line() = "top1 %.1f%%  top5 %.1f%%  (n=%d)".format(100.0 * top1 / n, 100.0 * top5 / n, n) }

    @Test
    fun compareVariants() {
        val spec = System.getenv("BENCH_VARIANTS")
        assumeTrue("未設定 BENCH_VARIANTS，略過", !spec.isNullOrBlank())
        UserDictionaryManager.getInstance(null).clearDictionary()
        val sets = linkedMapOf("AI文章" to load("bigram_eval.tsv"), "網頁twngrams" to load("tw_eval.tsv"))
        for (v in spec!!.split(';').filter { it.isNotBlank() }) {
            val p = v.split('|')
            val engine = ZhuyinT9Engine()
            engine.loadDictionaryForTest(File(p[1]))
            engine.setBigramModel(BigramModel.load(File(p[2])))
            engine.bigramScaleT9Cost = 3.0; engine.bigramScaleT9Weight = 6.0
            engine.bigramScaleFull = 4.0; engine.bigramScaleSentence = 1.0; engine.fullNoToneFirstBonus = 30_000
            for ((setName, clauses) in sets) {
                fun run(mode: String, query: (String, String?) -> List<DictEntry>) {
                    val s = Score()
                    for (c in clauses) {
                        var ctx: String? = null
                        for (i in c.words.indices) { s.add(c.words[i], query(c.zhuyin[i], ctx)); ctx = c.words[i] }
                    }
                    println("VBENCH ${p[0]} | $setName | $mode | ${s.line()}")
                }
                run("全鍵盤 不打聲調") { zy, ctx -> engine.currentContextWord = ctx; engine.searchFullZhuyin(zy.replace(" ", "").filter { it !in "ˇˋˊ˙" }, ctx) }
                run("全鍵盤 打聲調") { zy, ctx -> engine.currentContextWord = ctx; engine.searchFullZhuyin(zy.replace(" ", ""), ctx) }
                run("九鍵 不打聲調鍵") { zy, ctx ->
                    engine.clear(); engine.currentContextWord = ctx
                    var c: List<DictEntry> = emptyList()
                    for (k in KeyMapping.getSequence(zy, ignoreTones = true)) c = engine.pressKey(k)
                    c
                }
            }
            if (p.size > 3 && File(p[3]).exists()) {
                val s = Score(); val miss = ArrayList<String>()
                for (line in File(p[3]).readLines().filter { it.isNotBlank() }) {
                    val f = line.split('\t'); engine.currentContextWord = null
                    val c = engine.searchFullZhuyin(f[1], null)
                    s.add(f[0], c); if (c.firstOrNull()?.word != f[0] && miss.size < 8) miss.add("${f[0]}→${c.firstOrNull()?.word}")
                }
                println("VBENCH ${p[0]} | 補缺詞(能不能打出來) | 全鍵盤 打聲調 | ${s.line()}  未排第一例: ${miss.joinToString(" ")}")
            }
        }
    }
}
