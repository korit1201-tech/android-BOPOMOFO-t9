package com.bopomofo.t9ime.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 語音輸入狀態回調介面
 */
interface VoiceInputCallback {
    fun onVoiceStateChanged(isListening: Boolean, promptText: String)
    fun onVoiceRmsChanged(rmsdB: Float)
    fun onVoicePartialResult(partialText: String)
    fun onVoiceResult(text: String)
    fun onVoiceError(errorMsg: String)
}

/**
 * 系統原生語音辨識控制器（優先使用離線模式，自帶詳細除錯 Log）
 */
class VoiceInputController(
    private val context: Context,
    private val callback: VoiceInputCallback
) {
    companion object {
        const val TAG = "BopomofoIME_Voice"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == VoicePermissionActivity.ACTION_PERMISSION_RESULT) {
                val granted = intent.getBooleanExtra(VoicePermissionActivity.EXTRA_GRANTED, false)
                Log.i(TAG, "收到權限申請結果廣播: granted = $granted")
                if (granted) {
                    startListeningInternal()
                } else {
                    callback.onVoiceError("未取得麥克風錄音權限")
                }
            }
        }
    }

    private var receiverRegistered = false

    fun register() {
        if (!receiverRegistered) {
            val filter = IntentFilter(VoicePermissionActivity.ACTION_PERMISSION_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permissionReceiver, filter)
            }
            receiverRegistered = true
            Log.d(TAG, "VoicePermissionReceiver 已註冊")
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver 失敗", e)
            }
            receiverRegistered = false
        }
        destroy()
    }

    fun toggleListening() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "startListening() 觸發，RECORD_AUDIO 權限狀態: $hasPermission")

        if (!hasPermission) {
            Log.i(TAG, "尚未取得錄音權限，啟動 VoicePermissionActivity 申請權限...")
            val intent = Intent(context, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        startListeningInternal()
    }

    private fun startListeningInternal() {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.i(TAG, "檢查 SpeechRecognizer.isRecognitionAvailable: $available")
        if (!available) {
            val errMsg = "系統未提供語音辨識服務（請確認是否已安裝 Google 語音服務）"
            Log.e(TAG, errMsg)
            callback.onVoiceError(errMsg)
            return
        }

        try {
            destroy()
            Log.d(TAG, "建立 SpeechRecognizer 實例...")
            // 使用 applicationContext 避免 Service 視窗洩漏
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        Log.i(TAG, "RecognitionListener: onReadyForSpeech（麥克風就緒，開始接收說話）")
                        callback.onVoiceStateChanged(true, "🎙️ 正在聆聽，請說話...")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.i(TAG, "RecognitionListener: onBeginningOfSpeech（偵測到使用者開始說話）")
                        callback.onVoiceStateChanged(true, "🎙️ 正在接收語音...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        callback.onVoiceRmsChanged(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.i(TAG, "RecognitionListener: onEndOfSpeech（說話結束，正在辨識）")
                        callback.onVoiceStateChanged(true, "⏳ 正在辨識文字...")
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val (errTitle, errDetail) = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "音訊錄製錯誤" to "ERROR_AUDIO (3)"
                            SpeechRecognizer.ERROR_CLIENT -> "客戶端內部錯誤" to "ERROR_CLIENT (5，請檢查語音服務)"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少錄音權限" to "ERROR_INSUFFICIENT_PERMISSIONS (9)"
                            SpeechRecognizer.ERROR_NETWORK -> "網路連線錯誤" to "ERROR_NETWORK (2)"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路逾時" to "ERROR_NETWORK_TIMEOUT (1)"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未能聽清語音" to "ERROR_NO_MATCH (7，無匹配結果)"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音服務忙碌中" to "ERROR_RECOGNIZER_BUSY (8)"
                            SpeechRecognizer.ERROR_SERVER -> "伺服器錯誤" to "ERROR_SERVER (4)"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未偵測到聲音" to "ERROR_SPEECH_TIMEOUT (6，說話逾時)"
                            12 -> "語系不支援" to "ERROR_LANGUAGE_NOT_SUPPORTED (12)"
                            13 -> "離線語音包未安裝" to "ERROR_LANGUAGE_UNAVAILABLE (13)"
                            14 -> "伺服器斷線" to "ERROR_SERVER_DISCONNECTED (14)"
                            else -> "辨識錯誤" to "ERROR_CODE ($error)"
                        }

                        Log.e(TAG, "RecognitionListener: onError -> $errTitle ($errDetail)")
                        callback.onVoiceError("語音錯誤: $errTitle ($errDetail)")
                        destroy()
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        Log.i(TAG, "RecognitionListener: onResults -> 取得候選文字清單: $matches")
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            callback.onVoiceResult(text)
                        } else {
                            callback.onVoiceError("未辨識出有效文字")
                        }
                        destroy()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            Log.d(TAG, "RecognitionListener: onPartialResults -> $text")
                            callback.onVoicePartialResult(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {
                        Log.d(TAG, "RecognitionListener: onEvent -> eventType=$eventType")
                    }
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-TW")
                // 優先使用離線模式
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // 支援即時結果
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            Log.i(TAG, "呼叫 speechRecognizer.startListening(intent)...")
            speechRecognizer?.startListening(intent)
            isListening = true
            callback.onVoiceStateChanged(true, "🎙️ 正在啟動語音...")
        } catch (e: Exception) {
            Log.e(TAG, "啟動語音辨識發生例外", e)
            isListening = false
            callback.onVoiceError("啟動例外: ${e.message}")
            destroy()
        }
    }

    fun stopListening() {
        Log.i(TAG, "stopListening() 觸發")
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening 失敗", e)
        }
    }

    fun cancel() {
        Log.i(TAG, "cancel() 觸發")
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel 失敗", e)
        }
        isListening = false
        destroy()
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy 失敗", e)
        }
        speechRecognizer = null
        isListening = false
    }
}
