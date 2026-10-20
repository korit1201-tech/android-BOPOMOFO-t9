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
    fun onVoicePartialResult(partialText: String)
    fun onVoiceResult(text: String)
    fun onVoiceError(errorMsg: String)
}

/**
 * 系統原生語音辨識控制器（優先使用離線模式）
 */
class VoiceInputController(
    private val context: Context,
    private val callback: VoiceInputCallback
) {
    companion object {
        private const val TAG = "VoiceInputController"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == VoicePermissionActivity.ACTION_PERMISSION_RESULT) {
                val granted = intent.getBooleanExtra(VoicePermissionActivity.EXTRA_GRANTED, false)
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
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver error", e)
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(context, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        startListeningInternal()
    }

    private fun startListeningInternal() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onVoiceError("系統未提供語音辨識服務")
            return
        }

        try {
            destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        callback.onVoiceStateChanged(true, "🎤 請說話...")
                    }

                    override fun onBeginningOfSpeech() {
                        callback.onVoiceStateChanged(true, "🎤 正在聆聽...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        callback.onVoiceStateChanged(true, "⏳ 辨識中...")
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "音訊錄製錯誤"
                            SpeechRecognizer.ERROR_CLIENT -> "客戶端內部錯誤"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少錄音權限"
                            SpeechRecognizer.ERROR_NETWORK -> "網路連線錯誤"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路逾時"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未能聽清語音"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音服務忙碌中"
                            SpeechRecognizer.ERROR_SERVER -> "伺服器錯誤"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "說話逾時，請再試一次"
                            else -> "辨識錯誤 ($error)"
                        }
                        callback.onVoiceError(errorMsg)
                        destroy()
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            callback.onVoiceResult(text)
                        } else {
                            callback.onVoiceError("未取得辨識文字")
                        }
                        destroy()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            callback.onVoicePartialResult(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-TW")
                // 指定優先使用離線模式
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // 支援邊說邊出字即時預覽
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            callback.onVoiceStateChanged(true, "🎤 正在啟動語音...")
        } catch (e: Exception) {
            Log.e(TAG, "啟動語音辨識失敗", e)
            isListening = false
            callback.onVoiceError("啟動失敗: ${e.message}")
            destroy()
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening error", e)
        }
    }

    fun cancel() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel error", e)
        }
        isListening = false
        destroy()
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy error", e)
        }
        speechRecognizer = null
        isListening = false
    }
}
