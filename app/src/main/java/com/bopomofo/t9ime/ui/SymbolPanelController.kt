package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bopomofo.t9ime.R
import com.bopomofo.t9ime.ZhuyinInputMethodService
import com.bopomofo.t9ime.ZhuyinInputMethodService.HapticType
import com.bopomofo.t9ime.ZhuyinInputMethodService.SymbolTab
import com.bopomofo.t9ime.engine.ClipboardHistoryManager
import com.bopomofo.t9ime.engine.EmojiKaomojiManager
import com.bopomofo.t9ime.engine.SnippetManager

/**
 * 符號/方向鍵/剪貼簿/顏文字/短語面板控制器
 * 負責管理面板的 UI 建構、分頁切換與點擊事件，透過 Callback 與輸入法主服務互動
 */
class SymbolPanelController(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onCommitText(text: String)
        fun onCommitProcessedText(text: String)
        fun onSafeCommitText(text: String)
        fun onPerformEnter()
        fun onSendDpadKey(keyCode: Int)
        fun onPerformContextMenuAction(actionId: Int)
        fun onToggleOneHandedMode(): String
        fun getCurrentOneHandedTitle(): String
        fun onTriggerHaptic(type: HapticType = HapticType.KEY_PRESS)
        fun onShowPanel()
        fun onHidePanel()
    }

    private var rootView: View? = null
    private lateinit var layoutSymbolPanel: LinearLayout
    private lateinit var btnSymbolDrawer: Button
    private lateinit var containerSymbolContent: FrameLayout
    var currentSymbolTab = SymbolTab.DPAD
        private set

    val isVisible: Boolean
        get() = ::layoutSymbolPanel.isInitialized && layoutSymbolPanel.visibility == View.VISIBLE

    fun setup(root: View) {
        rootView = root
        layoutSymbolPanel = root.findViewById(R.id.layout_symbol_panel)
        btnSymbolDrawer = root.findViewById(R.id.btn_symbol_drawer)
        containerSymbolContent = root.findViewById(R.id.container_symbol_content)

        val tabFull = root.findViewById<Button>(R.id.tab_sym_fullwidth)
        val tabHalf = root.findViewById<Button>(R.id.tab_sym_halfwidth)
        val tabDpad = root.findViewById<Button>(R.id.tab_sym_dpad)
        val tabMath = root.findViewById<Button>(R.id.tab_sym_math)
        val tabClip = root.findViewById<Button>(R.id.tab_sym_clipboard)
        val tabEmoji = root.findViewById<Button>(R.id.tab_sym_emoji)
        val tabKaomoji = root.findViewById<Button>(R.id.tab_sym_kaomoji)
        val tabSnippet = root.findViewById<Button>(R.id.tab_sym_snippet)

        tabFull?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.FULLWIDTH) }
        tabHalf?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.HALFWIDTH) }
        tabDpad?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.DPAD) }
        tabMath?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.MATH) }
        tabClip?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.CLIPBOARD) }
        tabEmoji?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.EMOJI) }
        tabKaomoji?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.KAOMOJI) }
        tabSnippet?.setOnClickListener { callback.onTriggerHaptic(HapticType.MODE_SWITCH); switchSymbolTab(SymbolTab.SNIPPET) }

        btnSymbolDrawer.setOnClickListener {
            callback.onTriggerHaptic(HapticType.MODE_SWITCH)
            if (layoutSymbolPanel.visibility == View.VISIBLE) {
                hideSymbolPanel()
            } else {
                showSymbolPanel()
            }
        }
    }

    fun showSymbolPanel() {
        if (!::layoutSymbolPanel.isInitialized) return
        callback.onShowPanel()
        layoutSymbolPanel.visibility = View.VISIBLE
        btnSymbolDrawer.text = "✕"
        switchSymbolTab(currentSymbolTab)
    }

    fun hideSymbolPanel(notify: Boolean = true) {
        if (!::layoutSymbolPanel.isInitialized) return
        layoutSymbolPanel.visibility = View.GONE
        btnSymbolDrawer.text = "✛"
        if (notify) {
            callback.onHidePanel()
        }
    }

    fun show() = showSymbolPanel()
    fun hide(notify: Boolean = true) = hideSymbolPanel(notify)
    fun switchTab(tab: SymbolTab) = switchSymbolTab(tab)

    fun switchSymbolTab(tab: SymbolTab) {
        currentSymbolTab = tab
        val tabFull = rootView?.findViewById<Button>(R.id.tab_sym_fullwidth)
        val tabHalf = rootView?.findViewById<Button>(R.id.tab_sym_halfwidth)
        val tabDpad = rootView?.findViewById<Button>(R.id.tab_sym_dpad)
        val tabMath = rootView?.findViewById<Button>(R.id.tab_sym_math)
        val tabClip = rootView?.findViewById<Button>(R.id.tab_sym_clipboard)
        val tabEmoji = rootView?.findViewById<Button>(R.id.tab_sym_emoji)
        val tabKaomoji = rootView?.findViewById<Button>(R.id.tab_sym_kaomoji)
        val tabSnippet = rootView?.findViewById<Button>(R.id.tab_sym_snippet)

        val activeColor = ContextCompat.getColor(context, R.color.kb_accent)
        val normalColor = ContextCompat.getColor(context, R.color.kb_text_primary)

        tabFull?.setTextColor(if (tab == SymbolTab.FULLWIDTH) activeColor else normalColor)
        tabHalf?.setTextColor(if (tab == SymbolTab.HALFWIDTH) activeColor else normalColor)
        tabDpad?.setTextColor(if (tab == SymbolTab.DPAD) activeColor else normalColor)
        tabMath?.setTextColor(if (tab == SymbolTab.MATH) activeColor else normalColor)
        tabClip?.setTextColor(if (tab == SymbolTab.CLIPBOARD) activeColor else normalColor)
        tabEmoji?.setTextColor(if (tab == SymbolTab.EMOJI) activeColor else normalColor)
        tabKaomoji?.setTextColor(if (tab == SymbolTab.KAOMOJI) activeColor else normalColor)
        tabSnippet?.setTextColor(if (tab == SymbolTab.SNIPPET) activeColor else normalColor)

        if (!::containerSymbolContent.isInitialized) return
        containerSymbolContent.removeAllViews()

        when (tab) {
            SymbolTab.DPAD -> containerSymbolContent.addView(createDpadView())
            SymbolTab.FULLWIDTH -> {
                val fullSymbols = listOf(
                    "，", "。", "！", "？", "、", "；", "：", "～",
                    "…", "「", "」", "『", "』", "《", "》", "（",
                    "）", "【", "】", "〔", "〕", "“", "”", "‘", "’", "·", "—", "￥"
                )
                containerSymbolContent.addView(createSymbolGrid(fullSymbols, 7))
            }
            SymbolTab.HALFWIDTH -> {
                val halfSymbols = listOf(
                    ",", ".", "!", "?", ":", ";", "/", "\\",
                    "~", "@", "#", "$", "%", "^", "&", "*",
                    "-", "_", "+", "=", "(", ")", "[", "]",
                    "{", "}", "<", ">", "\"", "'", "`", "|"
                )
                containerSymbolContent.addView(createSymbolGrid(halfSymbols, 8))
            }
            SymbolTab.MATH -> {
                val mathSymbols = listOf(
                    "↑", "↓", "←", "→", "±", "×", "÷", "≠",
                    "≈", "≤", "≥", "℃", "★", "✔", "❤", "☺",
                    "©", "®", "™", "¥", "€", "£", "§", "¶",
                    "∞", "π", "√", "°", "‰", "▲", "▼", "◆"
                )
                containerSymbolContent.addView(createSymbolGrid(mathSymbols, 8))
            }
            SymbolTab.CLIPBOARD -> containerSymbolContent.addView(createClipboardView())
            SymbolTab.EMOJI -> containerSymbolContent.addView(createSymbolGrid(EmojiKaomojiManager.POPULAR_EMOJIS, 7))
            SymbolTab.KAOMOJI -> containerSymbolContent.addView(createKaomojiView())
            SymbolTab.SNIPPET -> containerSymbolContent.addView(createSnippetView())
        }
    }

    private fun createClipboardView(): View {
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val clips = ClipboardHistoryManager.getHistory()
        if (clips.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "剪貼簿目前尚無紀錄\n複製任何文字將自動保存在此"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
            }
            layout.addView(emptyTv)
        } else {
            // 頂部列：提示與清空按鈕
            val topBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 2, 4, 6)
            }
            val hintTv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "保留最近 10 則紀錄（長按可刪除）"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
            }
            val btnClear = Button(context).apply {
                text = "清空"
                textSize = 12f
                setTextColor(Color.parseColor("#E53935"))
                setBackgroundResource(R.drawable.bg_key_action)
                setOnClickListener {
                    callback.onTriggerHaptic(HapticType.DELETE)
                    ClipboardHistoryManager.clearAll(context)
                    switchSymbolTab(SymbolTab.CLIPBOARD)
                }
            }
            topBar.addView(hintTv)
            topBar.addView(btnClear)
            layout.addView(topBar)

            for (clip in clips) {
                val btn = Button(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4
                        bottomMargin = 4
                    }
                    text = clip.take(60) + if (clip.length > 60) "..." else ""
                    textSize = 14f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_key)
                    setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                    setOnClickListener {
                        callback.onTriggerHaptic(HapticType.COMMIT)
                        callback.onSafeCommitText(clip)
                        hideSymbolPanel()
                    }
                    setOnLongClickListener {
                        callback.onTriggerHaptic(HapticType.DELETE)
                        ClipboardHistoryManager.removeClip(context, clip)
                        switchSymbolTab(SymbolTab.CLIPBOARD)
                        true
                    }
                }
                layout.addView(btn)
            }
        }
        scrollView.addView(layout)
        return scrollView
    }

    private fun createKaomojiView(): View {
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 4, 6, 4)
        }

        val rows = EmojiKaomojiManager.KAOMOJI_LIST.chunked(3)
        for (rowItems in rows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                    bottomMargin = 4
                }
            }
            for (km in rowItems) {
                val btn = Button(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 3
                        marginEnd = 3
                    }
                    text = km
                    textSize = 13f
                    setBackgroundResource(R.drawable.bg_key)
                    setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                    setOnClickListener {
                        callback.onTriggerHaptic(HapticType.COMMIT)
                        callback.onCommitProcessedText(km)
                        hideSymbolPanel()
                    }
                }
                rowLayout.addView(btn)
            }
            layout.addView(rowLayout)
        }
        scrollView.addView(layout)
        return scrollView
    }

    private fun createSnippetView(): View {
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val snippets = SnippetManager.getAllSnippets()
        for ((trigger, expansion) in snippets) {
            val btn = Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                    bottomMargin = 4
                }
                text = "[$trigger] $expansion"
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_key)
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setOnClickListener {
                    callback.onTriggerHaptic(HapticType.COMMIT)
                    callback.onCommitProcessedText(expansion)
                    hideSymbolPanel()
                }
            }
            layout.addView(btn)
        }
        scrollView.addView(layout)
        return scrollView
    }

    private fun createDpadView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            weightSum = 3f
        }

        // 左側快捷操作
        val leftActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
        }
        val btnHome = Button(context).apply {
            text = "Home"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onSendDpadKey(KeyEvent.KEYCODE_MOVE_HOME) }
        }
        val btnSelectAll = Button(context).apply {
            text = "全選"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onPerformContextMenuAction(android.R.id.selectAll) }
        }
        val btnCopy = Button(context).apply {
            text = "複製"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onPerformContextMenuAction(android.R.id.copy) }
        }
        leftActions.addView(btnHome)
        leftActions.addView(btnSelectAll)
        leftActions.addView(btnCopy)

        // 中間十字方向鍵盤
        val centerDpad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f)
        }

        val rowUp = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER
        }
        val btnUp = Button(context).apply {
            text = "▲"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(4, 2, 4, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onSendDpadKey(KeyEvent.KEYCODE_DPAD_UP) }
        }
        rowUp.addView(btnUp)

        val rowMid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val btnLeft = Button(context).apply {
            text = "◀"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onSendDpadKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        }
        val btnEnter = Button(context).apply {
            text = "↵"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onPerformEnter() }
        }
        val btnRight = Button(context).apply {
            text = "▶"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onSendDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        rowMid.addView(btnLeft)
        rowMid.addView(btnEnter)
        rowMid.addView(btnRight)

        val rowDown = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER
        }
        val btnDown = Button(context).apply {
            text = "▼"
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key)
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(4, 2, 4, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onSendDpadKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        }
        rowDown.addView(btnDown)

        centerDpad.addView(rowUp)
        centerDpad.addView(rowMid)
        centerDpad.addView(rowDown)

        // 右側快捷操作
        val rightActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
        }
        val btnEnd = Button(context).apply {
            text = "End"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onSendDpadKey(KeyEvent.KEYCODE_MOVE_END) }
        }
        val btnCut = Button(context).apply {
            text = "剪下"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener { callback.onTriggerHaptic(); callback.onPerformContextMenuAction(android.R.id.cut) }
        }
        val btnPaste = Button(context).apply {
            text = "貼上"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener {
                callback.onTriggerHaptic()
                val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clipData = clipManager?.primaryClip
                val clipText = if (clipData != null && clipData.itemCount > 0) {
                    clipData.getItemAt(0)?.coerceToText(context)?.toString()
                } else null

                if (!clipText.isNullOrEmpty()) {
                    callback.onSafeCommitText(clipText)
                } else {
                    callback.onPerformContextMenuAction(android.R.id.paste)
                }
            }
        }
        val btnOneHanded = Button(context).apply {
            text = callback.getCurrentOneHandedTitle()
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            setBackgroundResource(R.drawable.bg_key_action)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(2, 2, 2, 2) }
            layoutParams = p
            setOnClickListener {
                val newTitle = callback.onToggleOneHandedMode()
                text = newTitle
            }
        }
        rightActions.addView(btnEnd)
        rightActions.addView(btnCut)
        rightActions.addView(btnPaste)
        rightActions.addView(btnOneHanded)

        root.addView(leftActions)
        root.addView(centerDpad)
        root.addView(rightActions)
        return root
    }

    private fun createSymbolGrid(symbols: List<String>, columns: Int): View {
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        var currentRow: LinearLayout? = null
        for ((idx, sym) in symbols.withIndex()) {
            if (idx % columns == 0) {
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 46.dpToPx())
                }
                container.addView(currentRow)
            }
            val btn = Button(context).apply {
                text = sym
                textSize = 17f
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setBackgroundResource(R.drawable.bg_key)
                val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(1, 1, 1, 1) }
                layoutParams = p
                setOnClickListener {
                    callback.onTriggerHaptic()
                    callback.onCommitText(sym)
                }
            }
            currentRow?.addView(btn)
        }
        scrollView.addView(container)
        return scrollView
    }

    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()
}
