package com.example.helloworld

import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {
    private lateinit var toy: MallangScreen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = android.graphics.Color.rgb(249, 246, 240)
        window.navigationBarColor = android.graphics.Color.rgb(249, 246, 240)
        toy = MallangScreen(this)
        setContentView(toy)
    }
    override fun onResume() { super.onResume(); toy.resume() }
    override fun onPause() { toy.pause(); super.onPause() }
    override fun onDestroy() { toy.release(); super.onDestroy() }
}
