package com.example.study_helper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.study_helper.runner.RunnerScreen

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFEAC878),
            onPrimary = Color(0xFF142F2C),
            background = Color(0xFF102A29),
            surface = Color(0xFF193B38),
            onSurface = Color(0xFFF6F2DE),
        ),
    ) {
        RunnerScreen()
    }
}
