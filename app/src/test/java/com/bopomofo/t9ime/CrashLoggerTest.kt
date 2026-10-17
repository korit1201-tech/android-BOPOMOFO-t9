package com.bopomofo.t9ime

import android.content.Context
import com.bopomofo.t9ime.util.CrashLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        val rootDir = tempFolder.newFolder("files")
        mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File {
                return rootDir
            }

            override fun getApplicationContext(): Context {
                return this
            }
        }
        CrashLogger.clearCrashLogs(mockContext)
    }

    @Test
    fun testCrashLoggerRecordingAndClearing() {
        assertFalse(CrashLogger.hasCrashLogs(mockContext))
        assertTrue(CrashLogger.getCrashLogs(mockContext).isEmpty())

        val testException = IllegalStateException("測試異常訊息 12345")
        CrashLogger.logNonFatal(mockContext, "UnitTest", testException)

        assertTrue(CrashLogger.hasCrashLogs(mockContext))
        val logs = CrashLogger.getCrashLogs(mockContext)
        assertTrue(logs.contains("測試異常訊息 12345"))
        assertTrue(logs.contains("NonFatal-UnitTest"))
        assertTrue(logs.contains("IllegalStateException"))

        CrashLogger.clearCrashLogs(mockContext)
        assertFalse(CrashLogger.hasCrashLogs(mockContext))
        assertTrue(CrashLogger.getCrashLogs(mockContext).isEmpty())
    }
}
