package com.example.study_helper

actual fun getPlatform(): Platform = object : Platform {
    override val name: String = System.getProperty("os.name")
}
