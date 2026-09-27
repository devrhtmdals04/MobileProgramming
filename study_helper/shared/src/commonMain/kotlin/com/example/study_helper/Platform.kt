package com.example.study_helper

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform