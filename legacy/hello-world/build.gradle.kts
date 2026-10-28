plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}

android {
    namespace = "com.example.helloworld"
    compileSdk = 36
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.example.helloworld"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Keep the requested entry file at the project root.
    sourceSets.getByName("main").kotlin.apply {
        setSrcDirs(listOf(projectDir))
        include("test.kt")
    }
}
