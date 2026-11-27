package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.BigramModel
import com.bopomofo.t9ime.engine.KeyMapping
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Test
import java.io.File

/**
 * 九鍵整句上限分析（docs/CYIME_REFERENCE_PLAN.md 的 P5，只量不做、沒有斷言）。
 *
 * 整個子句的按鍵一次打完（鍵數 4～16，不含聲調鍵），看正確整句在不在候選列、排第幾，並把沒排第一的子句分成：
 *  - 排序問題：正確整句在候選列裡但不是第 1 名（改排序、加 trigram 才可能有用）
 *  - 覆蓋問題：正確整句根本沒出現。再細分「每個詞單獨打都找得到」（切分／組句問題）與「有詞單獨打也找不到」（詞庫缺詞）
 * 結果寫入 build/sentence-ceiling.json，主要輸出 `CEILING ...` 行。
 */
class SentenceCeilingBenchTest {
    private class Clause(val words: List<String>, val zhuyin: List<String>)

    @Test
    fun analyse() {
        val engine = ZhuyinT9Engine()
        engine.loadDictionaryForTest(File("src/main/assets/dict_tw.txt"))
        engine.setBigramModel(BigramModel.load(File("src/main/assets/bigram_core.bin")))
        engine.bigramScaleT9Cost = 3.0
        engine.bigramScaleT9Weight = 6.0
        engine.bigramScaleFull = 4.0
        engine.bigramScaleSentence = 1.0
        engine.fullNoToneFirstBonus = 30_000

        val stream = javaClass.classLoader.getResourceAsStream("bigram_eval.tsv") ?: error("找不到 bigram_eval.tsv")
        val clauses = stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.map {
            val p = it.split('\t'); Clause(p[1].split('|'), p[2].split('|'))
        }

        var total = 0; var top1 = 0; var top5 = 0; var inList = 0
        var absentSegmentation = 0; var absentMissingWord = 0
        val rankHist = IntArray(11)
        val examples = ArrayList<String>()
        val missingWords = LinkedHashMap<String, Int>()

        for (c in clauses) {
            val gold = c.words.joinToString("")
            val keys = KeyMapping.getSequence(c.zhuyin.joinToString(""), ignoreTones = true)
            if (keys.size !in 4..16) continue
            total++
            engine.clear(); engine.currentContextWord = null
            for (k in keys) engine.pressKey(k)
            val cands = engine.getCandidates()
            val rank = cands.indexOfFirst { it.word == gold }
            if (rank == 0) top1++
            if (rank in 0..4) top5++
            if (rank >= 0) inList++
            rankHist[if (rank < 0 || rank >= 10) 10 else rank]++
            if (rank != 0 && examples.size < 40) {
                examples.add("${if (rank < 0) "缺" else "第${rank + 1}"}\t$gold\t→ ${cands.take(2).joinToString("/") { it.word }}")
            }
            if (rank < 0) {
                // 每個詞單獨打得出來嗎
                var allReachable = true
                for (i in c.words.indices) {
                    engine.clear(); engine.currentContextWord = null
                    for (k in KeyMapping.getSequence(c.zhuyin[i], ignoreTones = true)) engine.pressKey(k)
                    val single = engine.getCandidates()
                    if (single.none { it.word == c.words[i] }) {
                        allReachable = false
                        missingWords.merge(c.words[i], 1, Int::plus)
                    }
                }
                if (allReachable) absentSegmentation++ else absentMissingWord++
            }
        }

        fun pct(a: Int) = if (total == 0) 0.0 else 100.0 * a / total
        println("CEILING 子句 $total（4～16 鍵）整句第1名 $top1 (${"%.1f".format(pct(top1))}%)  前5名 $top5 (${"%.1f".format(pct(top5))}%)  在候選列內 $inList (${"%.1f".format(pct(inList))}%)")
        println("CEILING 排序問題（在列內但不是第1名）${inList - top1}；覆蓋問題（不在列內）${total - inList}：其中每個詞單獨打都找得到 $absentSegmentation（切分／組句），有詞單獨打也找不到 $absentMissingWord（詞庫缺詞）")
        println("CEILING 名次分佈（第1..10名、10=未出現或第10名之後）：${rankHist.joinToString(" ")}")
        println("CEILING 缺詞前幾名：${missingWords.entries.sortedByDescending { it.value }.take(15).joinToString(" ") { "${it.key}×${it.value}" }}")
        examples.take(25).forEach { println("CEILING 例 $it") }

        val out = File("build/sentence-ceiling.json")
        out.parentFile?.mkdirs()
        out.writeText(
            """{"total":$total,"top1":$top1,"top5":$top5,"inList":$inList,"absentSegmentation":$absentSegmentation,""" +
                """"absentMissingWord":$absentMissingWord,"rankHist":[${rankHist.joinToString(",")}]}"""
        )
    }
}
