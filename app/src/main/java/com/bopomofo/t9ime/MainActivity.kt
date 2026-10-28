package com.bopomofo.t9ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bopomofo.t9ime.data.PreferencesRepository
import com.bopomofo.t9ime.engine.UserDictionaryManager

/**
 * 設定頁面與個人化詞庫管理 (匯入/匯出)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvDictStats: TextView
    private val userDictManager by lazy { UserDictionaryManager.getInstance(this) }

    // SAF 檔案建立器 (匯出)
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    userDictManager.exportToStream(os)
                }
                Toast.makeText(this, "✅ 詞庫已成功匯出！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 匯出失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // SAF 檔案挑選器 (匯入)
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val count = contentResolver.openInputStream(uri)?.use { inputStream ->
                    userDictManager.importFromStream(inputStream)
                } ?: 0
                Toast.makeText(this, "✅ 成功匯入 $count 筆詞條！", Toast.LENGTH_SHORT).show()
                updateDictStats()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 匯入失敗: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.bopomofo.t9ime.util.CrashLogger.install(this)
        setContentView(R.layout.activity_main)

        tvDictStats = findViewById(R.id.tv_dict_stats)

        findViewById<Button>(R.id.btn_enable_ime)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_select_ime)?.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        }

        // 匯出個人詞庫
        findViewById<Button>(R.id.btn_export_dict)?.setOnClickListener {
            val count = userDictManager.getEntryCount()
            if (count == 0) {
                Toast.makeText(this, "目前尚無記錄任何詞彙，請先使用輸入法打字或匯入詞庫", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createDocumentLauncher.launch("bopomofo_user_dict.txt")
        }

        // 匯入自訂詞庫
        findViewById<Button>(R.id.btn_import_dict)?.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/plain", "*/*"))
        }

        // 檢視與編輯專屬詞彙清單
        findViewById<Button>(R.id.btn_view_dict)?.setOnClickListener {
            showUserDictDialog()
        }

        // 清空學習紀錄
        findViewById<Button>(R.id.btn_clear_dict)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("確認清空個人詞庫")
                .setMessage("這將會清除您目前在手機上學習的所有自訂詞彙與高頻加權記錄，確定要清空嗎？")
                .setPositiveButton("確定清空") { _, _ ->
                    userDictManager.clearDictionary()
                    updateDictStats()
                    Toast.makeText(this, "個人詞庫已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 清除學到的詞對（打字時記下的「前一個詞→下一個詞」關聯，不影響個人詞庫與內建詞對）
        findViewById<Button>(R.id.btn_clear_bigram)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("確認清除學到的詞對")
                .setMessage("這會清除輸入法依您打字習慣學到的前後詞關聯（例如「我」後面常接什麼）。個人詞庫與內建的詞對資料不受影響，確定要清除嗎？")
                .setPositiveButton("確定清除") { _, _ ->
                    com.bopomofo.t9ime.engine.ZhuyinT9Engine.clearLearnedBigrams(this)
                    Toast.makeText(this, "已清除學到的詞對", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        setupVibrationSettings()
        setupTolerantInputSettings()
        setupThemeSettings()
        setupFullKeyboardSettings()
        setupThirdPartyNotices()
        setupCrashLogSettings()
    }

    private fun setupFullKeyboardSettings() {
        val spinnerStyle = findViewById<android.widget.Spinner>(R.id.spinner_full_keyboard_style)
        val switchBottomPunc = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_show_bottom_punctuation)

        if (spinnerStyle != null) {
            val styleOptions = listOf(
                "📱 主流手機風格 (類 Gboard / SwiftKey，退格在第 4 排)",
                "💻 電腦標準風格 (標準大千 PC 排列，退格在第 1 排)"
            )
            val styleValues = listOf(
                PreferencesRepository.STYLE_GBOARD,
                PreferencesRepository.STYLE_STANDARD_PC
            )

            val adapter = android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                styleOptions
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinnerStyle.adapter = adapter

            val currentStyle = PreferencesRepository.getFullKeyboardLayoutStyle(this)
            val initialIndex = styleValues.indexOf(currentStyle).coerceAtLeast(0)
            spinnerStyle.setSelection(initialIndex)

            spinnerStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val selectedStyle = styleValues[position]
                    if (selectedStyle != PreferencesRepository.getFullKeyboardLayoutStyle(this@MainActivity)) {
                        PreferencesRepository.setFullKeyboardLayoutStyle(this@MainActivity, selectedStyle)
                        val styleName = if (selectedStyle == PreferencesRepository.STYLE_GBOARD) "主流手機風格 (Gboard)" else "電腦標準風格"
                        Toast.makeText(this@MainActivity, "⌨️ 全鍵盤排版已切換為：$styleName", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        if (switchBottomPunc != null) {
            val isShow = PreferencesRepository.isShowBottomPunctuation(this)
            switchBottomPunc.isChecked = isShow
            switchBottomPunc.setOnCheckedChangeListener { _, isChecked ->
                PreferencesRepository.setShowBottomPunctuation(this, isChecked)
                val msg = if (isChecked) "✅ 已開啟底排標點鍵（，與。）" else "🔒 已隱藏底排標點（已大幅加寬空白鍵）"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTolerantInputSettings() {
        val switchTolerant = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_tolerant_input)
        if (switchTolerant != null) {
            val isEnabled = com.bopomofo.t9ime.data.PreferencesRepository.isTolerantInputEnabled(this)
            switchTolerant.isChecked = isEnabled
            switchTolerant.setOnCheckedChangeListener { _, isChecked ->
                com.bopomofo.t9ime.data.PreferencesRepository.setTolerantInputEnabled(this, isChecked)
                val msg = if (isChecked) "✅ 已開啟發音容錯輸入（候選字將包含前後鼻音候補）" else "🔒 已關閉發音容錯（僅嚴格匹配輸入按鍵）"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        val switchInline = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_inline_composing)
        if (switchInline != null) {
            val isInline = PreferencesRepository.isInlineComposingEnabled(this)
            switchInline.isChecked = isInline
            switchInline.setOnCheckedChangeListener { _, isChecked ->
                PreferencesRepository.setInlineComposingEnabled(this, isChecked)
                val msg = if (isChecked) "✅ 已開啟行內組字底線" else "🛡️ 已開啟相容模式（僅在候選列組字，杜絕第三方 App 疊加注音）"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupThemeSettings() {
        val spinnerTheme = findViewById<android.widget.Spinner>(R.id.spinner_theme) ?: return
        val themes = com.bopomofo.t9ime.theme.AppTheme.values()
        val themeNames = themes.map { it.displayName }

        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            themeNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerTheme.adapter = adapter

        val currentTheme = com.bopomofo.t9ime.theme.ThemeManager.getCurrentTheme(this)
        val selectedIndex = themes.indexOf(currentTheme).coerceAtLeast(0)
        spinnerTheme.setSelection(selectedIndex)

        spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedTheme = themes[position]
                if (selectedTheme != com.bopomofo.t9ime.theme.ThemeManager.getCurrentTheme(this@MainActivity)) {
                    com.bopomofo.t9ime.theme.ThemeManager.setTheme(this@MainActivity, selectedTheme)
                    Toast.makeText(this@MainActivity, "🎨 主題已切換為：${selectedTheme.displayName}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupVibrationSettings() {
        val switchVib = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_vibration)
        val layoutStrength = findViewById<android.view.View>(R.id.layout_vibration_strength)
        val seekbarStrength = findViewById<android.widget.SeekBar>(R.id.seekbar_vibration_strength)
        val tvStrengthVal = findViewById<TextView>(R.id.tv_vibration_strength_val)

        val isEnabled = PreferencesRepository.isVibrationEnabled(this)
        val savedStrength = PreferencesRepository.getVibrationStrength(this).coerceIn(5, 100)

        switchVib?.isChecked = isEnabled
        layoutStrength?.visibility = if (isEnabled) android.view.View.VISIBLE else android.view.View.GONE
        seekbarStrength?.progress = savedStrength
        tvStrengthVal?.text = "$savedStrength ms"

        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        }

        val testVibrate = { strength: Int ->
            try {
                if (vibrator != null && vibrator.hasVibrator()) {
                    val s = strength.coerceIn(5, 100)
                    val durationMs = ((s * 0.45f) + 6f).toLong().coerceIn(8L, 50L)
                    val amplitude = ((s / 100f) * 190 + 65).toInt().coerceIn(1, 255)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val effect = android.os.VibrationEffect.createOneShot(durationMs, amplitude)
                        vibrator.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(durationMs)
                    }
                }
            } catch (_: Exception) {}
        }

        switchVib?.setOnCheckedChangeListener { _, isChecked ->
            PreferencesRepository.setVibrationEnabled(this, isChecked)
            layoutStrength?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked) {
                testVibrate(seekbarStrength?.progress ?: 5)
            }
        }

        seekbarStrength?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val strength = progress.coerceIn(5, 100)
                tvStrengthVal?.text = "$strength ms"
                if (fromUser) {
                    PreferencesRepository.setVibrationStrength(this@MainActivity, strength)
                    testVibrate(strength)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val strength = (seekBar?.progress ?: 5).coerceIn(5, 100)
                PreferencesRepository.setVibrationStrength(this@MainActivity, strength)
                testVibrate(strength)
            }
        })

        setupUpdateCheck()
    }

    private fun setupUpdateCheck() {
        val tvCurrentVer = findViewById<TextView>(R.id.tv_current_version)
        val btnCheck = findViewById<Button>(R.id.btn_check_update)

        tvCurrentVer?.text = "目前安裝版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        val tvRepo = findViewById<TextView>(R.id.tv_github_repo_link)
        tvRepo?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/korit1201-tech/android-BOPOMOFO-t9"))
                startActivity(intent)
            } catch (_: Exception) {}
        }

        btnCheck?.setOnClickListener {
            btnCheck.isEnabled = false
            btnCheck.text = "正在連線檢查 GitHub Releases..."
            com.bopomofo.t9ime.update.AppUpdateManager.checkUpdate { hasUpdate, info, error ->
                btnCheck.isEnabled = true
                btnCheck.text = "🔍 檢查 GitHub 新版本"
                if (error != null) {
                    Toast.makeText(this, "檢查更新失敗: $error", Toast.LENGTH_LONG).show()
                } else if (hasUpdate && info != null) {
                    com.bopomofo.t9ime.update.AppUpdateManager.showUpdateDialog(this, info)
                } else {
                    Toast.makeText(this, "🎉 目前已是最新版本 (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupThirdPartyNotices() {
        val tvThirdParty = findViewById<TextView>(R.id.tv_third_party_link)
        tvThirdParty?.setOnClickListener {
            val message = """
                本輸入法之離線詞庫與技術參考以下開源專案：

                1. chewing/libchewing-data (新酷音)
                - 繁體中文全量高頻詞庫與注音語料庫 (LGPL-2.1 / MIT)。

                2. easyprog/pime (PIME 輸入法平台)
                - 音節連打跨詞動態規劃、游標編輯改字與實體外接鍵盤互動架構設計啟發 (GPL / MIT)。

                3. polobread/KeyKey (琦琦輸入法)
                - 引用 chichi77Collection (MIT License, Copyright 2026 Chui-Ping Cheng)，收錄 29 類現代專業與動漫生活分類詞庫。

                4. openvanilla/McBopomofo (小麥注音)
                - 提供精準字音與詞頻資料 (MIT License, Copyright 2011-2026 Mengjuei Hsieh et al.)。

                5. Rizumu85/fcitx5-android-t9-phone
                - 啟發 12 鍵九宮格手勢交互概念。
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("📜 第三方開源資料庫與授權致謝")
                .setMessage(message)
                .setPositiveButton("開啟 GitHub 授權聲明") { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/korit1201-tech/android-BOPOMOFO-t9/blob/main/THIRD-PARTY-NOTICES.md"))
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
                .setNegativeButton("關閉", null)
                .show()
        }
    }

    private fun showUserDictDialog() {
        val entries = userDictManager.getAllEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, "目前個人詞庫內尚無記錄任何詞彙", Toast.LENGTH_SHORT).show()
            return
        }

        val displayItems = entries.map { entry ->
            val zhuyinPart = if (entry.zhuyin.isNotEmpty()) " (${entry.zhuyin})" else ""
            "${entry.word}$zhuyinPart  —  次數: ${entry.count}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("專屬詞彙清單 (${entries.size} 條)")
            .setItems(displayItems) { _, which ->
                val selectedEntry = entries[which]
                AlertDialog.Builder(this)
                    .setTitle("刪除專屬詞彙")
                    .setMessage("確定要從個人詞庫中刪除「${selectedEntry.word}」嗎？\n（刪除後該詞將不再享有優先加權排序）")
                    .setPositiveButton("刪除") { _, _ ->
                        userDictManager.removeEntry(selectedEntry.word)
                        updateDictStats()
                        Toast.makeText(this, "已刪除「${selectedEntry.word}」", Toast.LENGTH_SHORT).show()
                        showUserDictDialog() // 重新刷新對話框
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun setupCrashLogSettings() {
        val btnCrashLog = findViewById<Button>(R.id.btn_view_crash_log) ?: return
        btnCrashLog.setOnClickListener {
            val logs = com.bopomofo.t9ime.util.CrashLogger.getCrashLogs(this)
            if (logs.isBlank()) {
                val runtime = Runtime.getRuntime()
                val maxMem = runtime.maxMemory() / (1024 * 1024)
                val totalMem = runtime.totalMemory() / (1024 * 1024)
                val freeMem = runtime.freeMemory() / (1024 * 1024)
                val usedMem = totalMem - freeMem

                AlertDialog.Builder(this)
                    .setTitle("系統診斷狀態")
                    .setMessage(
                        "✅ 目前無任何崩潰紀錄，輸入法運作狀態良好！\n\n" +
                        "【裝置與環境診斷資訊】\n" +
                        "• App 版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})\n" +
                        "• 裝置型號：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "• Android 系統：${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                        "• JVM 記憶體：已分配 ${usedMem}MB / 堆上限 ${maxMem}MB\n" +
                        "• 處理器架構：${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}"
                    )
                    .setPositiveButton("確定", null)
                    .show()
            } else {
                val scrollView = android.widget.ScrollView(this).apply {
                    setPadding(32, 24, 32, 24)
                }
                val tvLog = TextView(this).apply {
                    text = logs
                    setTextIsSelectable(true)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(android.graphics.Color.parseColor("#333333"))
                }
                scrollView.addView(tvLog)

                AlertDialog.Builder(this)
                    .setTitle("診斷與崩潰日誌")
                    .setView(scrollView)
                    .setPositiveButton("複製日誌") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Bopomofo Crash Log", logs)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "✅ 已複製日誌內容至剪貼簿！", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("清空日誌") { _, _ ->
                        com.bopomofo.t9ime.util.CrashLogger.clearCrashLogs(this)
                        Toast.makeText(this, "日誌已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("關閉", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateDictStats()
        } catch (_: Exception) {}
    }

    private fun updateDictStats() {
        try {
            val count = userDictManager.getEntryCount()
            tvDictStats.text = "目前已記錄：$count 個常用專屬詞彙"
        } catch (_: Exception) {}
    }
}
