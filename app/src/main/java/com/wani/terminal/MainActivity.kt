package com.wani.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

class MainActivity : ComponentActivity() {

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        StoragePermission.onResult(StoragePermission.isGranted(this))
    }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> FilePicker.onResult(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StoragePermission.launcher = { perms -> storageLauncher.launch(perms) }
        FilePicker.launcher = { mime -> fileLauncher.launch(mime) }
        setContent {
            val base = LocalDensity.current
            // Poore app ke font size 30% kam (sirf text, layout nahi)
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, base.fontScale * FONT_SCALE)
            ) {
                val scheme = if (IsDarkTheme) {
                    darkColorScheme(
                        primary = Accent,
                        onPrimary = Color.Black,
                        background = Bg,
                        surface = Bg,
                        onSurface = TextMain,
                        onBackground = TextMain
                    )
                } else {
                    lightColorScheme(
                        primary = Accent,
                        onPrimary = Color.White,
                        background = Bg,
                        surface = Bg,
                        onSurface = TextMain,
                        onBackground = TextMain
                    )
                }
                MaterialTheme(colorScheme = scheme) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        StoragePermission.launcher = null
        FilePicker.launcher = null
        super.onDestroy()
    }

    companion object {
        private const val FONT_SCALE = 0.7f
    }
}
