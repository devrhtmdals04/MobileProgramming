package com.mope.app.ui.theme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun MopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = MopePurple, background = MopeCream), typography = MopeTypography, content = content)
}
