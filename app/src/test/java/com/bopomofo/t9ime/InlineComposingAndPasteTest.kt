package com.bopomofo.t9ime

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.bopomofo.t9ime.data.PreferencesRepository
import com.bopomofo.t9ime.engine.DictEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class InlineComposingAndPasteTest {

    private lateinit var mockContext: Context
    private val memoryPrefs = mutableMapOf<String, Any>()

    @Before
    fun setup() {
        lateinit var mockEditor: SharedPreferences.Editor
        mockEditor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    val key = args?.getOrNull(0) as? String
                    val value = args?.getOrNull(1) as? Boolean ?: true
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
                "getBoolean" -> {
                    val key = args[0] as String
                    val def = args.getOrNull(1) as? Boolean ?: false
                    memoryPrefs[key] as? Boolean ?: def
                }
                "edit" -> mockEditor
                else -> null
            }
        } as SharedPreferences

        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return mockSharedPreferences
            }
        }
    }

    @Test
    fun testInlineComposingPreference() {
        // 預設值為 true (開啟行內底線組字)
        assertTrue(PreferencesRepository.isInlineComposingEnabled(mockContext))

        // 關閉行內組字 (相容模式)
        PreferencesRepository.setInlineComposingEnabled(mockContext, false)
        assertFalse(PreferencesRepository.isInlineComposingEnabled(mockContext))

        // 重新開啟
        PreferencesRepository.setInlineComposingEnabled(mockContext, true)
        assertTrue(PreferencesRepository.isInlineComposingEnabled(mockContext))
    }

    @Test
    fun testPasteChipEntryFormat() {
        val rawText = "https://example.com/test?q=hello\nworld"
        val preview = rawText.trim().replace("\r", " ").replace("\n", " ")
        val shortPreview = if (preview.length > 12) preview.take(12) + "…" else preview
        val chipTitle = "📋 貼上：「$shortPreview」"
        val entry = DictEntry(chipTitle, rawText, 999_999_999)

        assertTrue(entry.word.startsWith("📋 貼上"))
        assertEquals("📋 貼上：「https://exam…」", entry.word)
        assertEquals(rawText, entry.zhuyin)
    }

    @Test
    fun testTypeNullBypassLogic() {
        val editorInfoNull = EditorInfo().apply {
            inputType = InputType.TYPE_NULL
        }
        val isTypeNull = (editorInfoNull.inputType == InputType.TYPE_NULL) ||
                ((editorInfoNull.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL)
        assertTrue("TYPE_NULL 必須被正確識別", isTypeNull)

        val editorInfoNormal = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        val isNormalNull = (editorInfoNormal.inputType == InputType.TYPE_NULL) ||
                ((editorInfoNormal.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL)
        assertFalse("一般文本輸入框不可誤判為 TYPE_NULL", isNormalNull)
    }
}
