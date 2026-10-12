package com.aireadio.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.aireadio.app.ui.*

class MainActivity : ComponentActivity() {
    private val model by viewModels<RadioViewModel>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RadioTheme { RadioScreen(model, onPlay = {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            model.toggle()
        }, onVoiceSettings = {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }, onArticlePlay = { id ->
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            model.playArticle(id)
        }) } }
    }
    override fun onResume() { super.onResume(); model.checkReadiness() }
}
