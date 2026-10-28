package com.mope.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mope.app.navigation.MopeNavHost
import com.mope.app.ui.theme.MopeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as MopeApplication).clubRepository
        setContent { MopeTheme { MopeNavHost(repository) } }
    }
}
