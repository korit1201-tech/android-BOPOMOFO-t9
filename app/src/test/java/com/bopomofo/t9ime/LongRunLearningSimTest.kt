package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.UserDictionaryManager
import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * 長期使用模擬（重現「越用越差」的量尺）：
 * 取評測語料切成 A、B 兩組，模擬使用者逐詞輸入 A 組 10 輪（每輪過一天）——
 * 第一名就接受；不是第一名就點目標詞（偏離預設，正向＋對被跳過者負向）；3% 機率誤點旁邊的候選，隨後刪掉重打。
 * 每輪後在「沒學過的 B 組」量第一名準確率：好的學習不該讓沒練習過的句子變差。
 */
class LongRunLearningSimTest {
    private val um get() = UserDictionaryManager.getInstance(null)
    private lateinit var e: ZhuyinT9Engine

    private class Clause(val words: List<String>, val zhuyin: List<String>)

    private fun clauses(): List<Clause> =
        javaClass.classLoader.getResourceAsStream("bigram_eval.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() }.map { val p = it.split('\t'); Clause(p[1].split('|'), p[2].split('|')) }

    @Before
    fun setUp() { um.clearDictionary(); e = ZhuyinT9Engine(null); e.loadDictionaryForTest() }

    @After
    fun tearDown() { um.clock = null }

    private fun top1(set: List<Clause>): Double {
        var n = 0; var ok = 0
        for (c in set) {
            var prev: String? = null
            for (i in c.words.indices) {
                if (c.zhuyin[i].isNotEmpty()) {
                    val cands = e.searchFullZhuyin(c.zhuyin[i], prev)
                    n++; if (cands.firstOrNull()?.word == c.words[i]) ok++
                }
                prev = c.words[i]
            }
        }
        return 100.0 * ok / maxOf(1, n)
    }

    @Test
    fun learningNeverMakesUnseenSentencesWorse() {
        val all = clauses()
        val a = all.take(300); val b = all.drop(300).take(300)
        var now = 1_000_000_000_000L
        um.clock = { now }
        val baseA = top1(a); val baseB = top1(b)
        val rnd = Random(42)
        val lines = ArrayList<String>()
        for (round in 1..10) {
            for (c in a) {
                var prev: String? = null
                for (i in c.words.indices) {
                    val zy = c.zhuyin[i]; val target = c.words[i]
                    if (zy.isNotEmpty()) {
                        val cands = e.searchFullZhuyin(zy, prev)
                        val rank = cands.indexOfFirst { it.word == target }
                        e.markCommit(target.length)
                        if (rank == 0) e.acceptWord(target, zy, cands.drop(1).filter { it.word.length == target.length }.take(4))
                        else if (rank > 0) {
                            if (rnd.nextDouble() < 0.03 && cands.size > 2) {
                                val wrong = cands[(rnd.nextInt(minOf(5, cands.size)))]
                                if (wrong.word != target) {
                                    e.learnPick(wrong.word, wrong.zhuyin, cands.takeWhile { it.word != wrong.word }, wrong.weight)
                                    e.revokeRecentLearning()           // 發現點錯，刪掉
                                    e.markCommit(target.length)
                                }
                            }
                            e.learnPick(target, zy, cands.subList(0, rank), cands[rank].weight)
                        }
                    }
                    prev = target
                }
            }
            now += 86_400_000L
            lines.add("round %2d  A(練習) %.1f%%  B(沒練習) %.1f%%".format(round, top1(a), top1(b)))
        }
        println("LONGRUN base A=%.1f%% B=%.1f%%".format(baseA, baseB))
        lines.forEach { println("LONGRUN $it") }
        val endA = top1(a); val endB = top1(b)
        assertTrue("沒練習過的句子不該變差超過 0.5 個百分點：$baseB → $endB", endB >= baseB - 0.5)
        assertTrue("練習過的句子應該進步：$baseA → $endA", endA >= baseA)
    }

    /** 9:1 混用兩個同音詞：少數派不能蓋過多數派（舊版會因為「選過就置頂」而翻盤）。 */
    @Test
    fun minorityHomophoneNeverOvertakesTheMajority() {
        val hen = com.bopomofo.t9ime.engine.DictEntry("很", "ㄏㄣˇ", 27890)
        repeat(10) {                       // 每 10 次輸入：9 次接受「很」、1 次改選「狠」
            repeat(9) { e.markCommit(1); e.acceptWord("很", "ㄏㄣˇ", listOf(com.bopomofo.t9ime.engine.DictEntry("狠", "ㄏㄣˇ", 1005))) }
            e.markCommit(1); e.learnPick("狠", "ㄏㄣˇ", listOf(hen), 1005)
        }
        assertTrue("9:1 混用，多數派必須維持第一", e.searchFullZhuyin("ㄏㄣˇ").first().word == "很")
    }
}
