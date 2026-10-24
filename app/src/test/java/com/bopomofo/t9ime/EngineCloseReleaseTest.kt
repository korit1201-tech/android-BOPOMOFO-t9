package com.bopomofo.t9ime

import com.bopomofo.t9ime.engine.ZhuyinT9Engine
import org.junit.Assert.assertTrue
import org.junit.Test

/** 輸入法服務重啟時舊引擎必須能中止詞庫載入並釋放記憶體（曾因新舊引擎同時佔記憶體 OOM） */
class EngineCloseReleaseTest {
    private fun usedMb(): Long {
        repeat(3) { System.gc() }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / 1048576
    }

    @Test
    fun closedEngineAbortsLoad() {
        val base = usedMb()
        val e = ZhuyinT9Engine()
        e.close()
        e.loadDictionaryForTest()
        assertTrue("close 後載入應立即中止", usedMb() - base < 30)
    }
}
