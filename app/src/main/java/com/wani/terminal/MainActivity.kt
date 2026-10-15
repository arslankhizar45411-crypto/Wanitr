package com.wani.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        StoragePermission.onResult(StoragePermission.isGranted(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StoragePermission.launcher = { perms -> storageLauncher.launch(perms) }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = androidx.compose.ui.graphics.Color.Black,
                    background = Bg,
                    surface = Bg
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppRoot()
                }
            }
        }
    }

    override fun onDestroy() {
        StoragePermission.launcher = null
        super.onDestroy()
    }
}
