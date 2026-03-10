package com.azazo1.auto_adb_wl_client.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val darkTheme = isSystemInDarkTheme()

            val colorScheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF81C784),
                    onPrimary = Color(0xFF003311),
                    primaryContainer = Color(0xFF004D26),
                    onPrimaryContainer = Color(0xFFA5D6A7),
                    secondary = Color(0xFF81C784),
                    onSecondary = Color(0xFF003311),
                    secondaryContainer = Color(0xFF004D26),
                    onSecondaryContainer = Color(0xFFA5D6A7),
                    tertiary = Color(0xFF4FC3F7),
                    onTertiary = Color(0xFF003544),
                    tertiaryContainer = Color(0xFF004D66),
                    onTertiaryContainer = Color(0xFFB3E5FC),
                    error = Color(0xFFFF6B6B),
                    errorContainer = Color(0xFF5D0000),
                    onError = Color(0xFFFFFFFF),
                    onErrorContainer = Color(0xFFFFDAD6)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF2E7D32),
                    onPrimary = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFFC8E6C9),
                    onPrimaryContainer = Color(0xFF002205),
                    secondary = Color(0xFF4CAF50),
                    onSecondary = Color(0xFFFFFFFF),
                    secondaryContainer = Color(0xFFE8F5E9),
                    onSecondaryContainer = Color(0xFF002205),
                    tertiary = Color(0xFF0288D1),
                    onTertiary = Color(0xFFFFFFFF),
                    tertiaryContainer = Color(0xFFE1F5FE),
                    onTertiaryContainer = Color(0xFF001F2A),
                    error = Color(0xFFD32F2F),
                    errorContainer = Color(0xFFFFE5E5),
                    onError = Color(0xFFFFFFFF),
                    onErrorContainer = Color(0xFF410002)
                )
            }

            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
