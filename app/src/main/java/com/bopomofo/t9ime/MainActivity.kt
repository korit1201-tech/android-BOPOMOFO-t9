package com.bopomofo.t9ime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

        setupVibrationSettings()
        setupTolerantInputSettings()
        setupThemeSettings()
        setupThirdPartyNotices()
    }

    private fun setupTolerantInputSettings() {
        val switchTolerant = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_tolerant_input) ?: return
        val isEnabled = com.bopomofo.t9ime.data.PreferencesRepository.isTolerantInputEnabled(this)
        switchTolerant.isChecked = isEnabled
        switchTolerant.setOnCheckedChangeListener { _, isChecked ->
            com.bopomofo.t9ime.data.PreferencesRepository.setTolerantInputEnabled(this, isChecked)
            val msg = if (isChecked) "✅ 已開啟發音容錯輸入（候選字將包含前後鼻音候補）" else "🔒 已關閉發音容錯（僅嚴格匹配輸入按鍵）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
        val prefs = getSharedPreferences("ime_prefs", MODE_PRIVATE)
        val switchVib = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_vibration)
        val layoutStrength = findViewById<android.view.View>(R.id.layout_vibration_strength)
        val seekbarStrength = findViewById<android.widget.SeekBar>(R.id.seekbar_vibration_strength)
        val tvStrengthVal = findViewById<TextView>(R.id.tv_vibration_strength_val)

        val isEnabled = prefs.getBoolean("pref_vibration_enabled", true)
        val savedStrength = prefs.getInt("pref_vibration_strength", 30).coerceIn(5, 100)

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

        val testVibrate = { ms: Int ->
            try {
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val amplitude = ((ms / 100f) * 255).toInt().coerceIn(1, 255)
                        val effect = android.os.VibrationEffect.createOneShot(ms.toLong(), amplitude)
                        vibrator.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(ms.toLong())
                    }
                }
            } catch (_: Exception) {}
        }

        switchVib?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_vibration_enabled", isChecked).apply()
            layoutStrength?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked) {
                testVibrate(seekbarStrength?.progress ?: 30)
            }
        }

        seekbarStrength?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val strength = progress.coerceIn(5, 100)
                tvStrengthVal?.text = "$strength ms"
                if (fromUser) {
                    prefs.edit().putInt("pref_vibration_strength", strength).apply()
                    testVibrate(strength)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val strength = (seekBar?.progress ?: 30).coerceIn(5, 100)
                prefs.edit().putInt("pref_vibration_strength", strength).apply()
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
