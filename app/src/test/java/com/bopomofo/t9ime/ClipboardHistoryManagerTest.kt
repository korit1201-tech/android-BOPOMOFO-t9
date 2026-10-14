package com.bopomofo.t9ime

import android.content.Context
import android.content.SharedPreferences
import com.bopomofo.t9ime.engine.ClipboardHistoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class ClipboardHistoryManagerTest {

    private lateinit var mockContext: Context
    private val memoryPrefs = mutableMapOf<String, String>()

    @Before
    fun setup() {
        lateinit var mockEditor: SharedPreferences.Editor
        mockEditor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    val key = args?.getOrNull(0) as? String
                    val value = args?.getOrNull(1) as? String ?: ""
                    if (key != null) memoryPrefs[key] = value
                    proxy
                }
                "apply" -> null
                "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor

        val mockSharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> memoryPrefs[args[0] as String] ?: (args.getOrNull(1) as? String)
                "edit" -> mockEditor
                else -> null
            }
        } as SharedPreferences

        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return mockSharedPreferences
            }
        }

        ClipboardHistoryManager.clearAll(mockContext)
    }

    @Test
    fun testAddClipAndLimit() {
        for (i in 1..15) {
            ClipboardHistoryManager.addClip(mockContext, "Item $i")
        }

        val history = ClipboardHistoryManager.getHistory()
        assertEquals(10, history.size)
        // 最新加入的應該在最前面
        assertEquals("Item 15", history[0])
        assertEquals("Item 6", history[9])
    }

    @Test
    fun testAddDuplicateBringsToFront() {
        ClipboardHistoryManager.addClip(mockContext, "First")
        ClipboardHistoryManager.addClip(mockContext, "Second")
        ClipboardHistoryManager.addClip(mockContext, "Third")

        // 重新加入 First，應移到首位
        ClipboardHistoryManager.addClip(mockContext, "First")

        val history = ClipboardHistoryManager.getHistory()
        assertEquals(3, history.size)
        assertEquals("First", history[0])
        assertEquals("Third", history[1])
        assertEquals("Second", history[2])
    }

    @Test
    fun testIgnoreBlankAndTooLong() {
        ClipboardHistoryManager.addClip(mockContext, "")
        ClipboardHistoryManager.addClip(mockContext, "   ")
        ClipboardHistoryManager.addClip(mockContext, "a".repeat(2001))

        assertTrue(ClipboardHistoryManager.getHistory().isEmpty())
    }

    @Test
    fun testRemoveAndClear() {
        ClipboardHistoryManager.addClip(mockContext, "A")
        ClipboardHistoryManager.addClip(mockContext, "B")
        assertEquals(2, ClipboardHistoryManager.getHistory().size)

        ClipboardHistoryManager.removeClip(mockContext, "A")
        assertEquals(1, ClipboardHistoryManager.getHistory().size)
        assertEquals("B", ClipboardHistoryManager.getHistory()[0])

        ClipboardHistoryManager.clearAll(mockContext)
        assertTrue(ClipboardHistoryManager.getHistory().isEmpty())
    }
}
