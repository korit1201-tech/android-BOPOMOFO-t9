package com.bopomofo.t9ime.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat

/**
 * 輕量透明的執行時期權限申請 Activity
 * 專為 InputMethodService 申請 RECORD_AUDIO 麥克風權限設計
 */
class VoicePermissionActivity : Activity() {

    companion object {
        const val ACTION_PERMISSION_RESULT = "com.bopomofo.t9ime.VOICE_PERMISSION_RESULT"
        const val EXTRA_GRANTED = "granted"
        private const val REQUEST_CODE_AUDIO = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = requestCode == REQUEST_CODE_AUDIO &&
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

        val resultIntent = Intent(ACTION_PERMISSION_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_GRANTED, granted)
        }
        sendBroadcast(resultIntent)
        finish()
    }
}
