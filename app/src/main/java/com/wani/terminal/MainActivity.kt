package com.wani.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF5EE0A8),
                    onPrimary = Color.Black,
                    background = Color(0xFF0D0E14),
                    surface = Color(0xFF0D0E14)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0E14)) {
                    AppRoot()
                }
            }
        }
    }
}
