plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val mopeBackend = providers.gradleProperty("mopeBackend").orElse("mock").get()
require(mopeBackend in setOf("mock", "emulator", "firebase")) {
    "mopeBackend must be mock, emulator, or firebase"
}
if (mopeBackend == "firebase") {
    require(file("google-services.json").isFile) {
        "Firebase mode requires app/google-services.json from the Firebase console"
    }
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.mope.app"
    compileSdk = 36
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.mope.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BACKEND", "\"$mopeBackend\"")
        manifestPlaceholders["allowEmulatorTraffic"] = (mopeBackend == "emulator").toString()
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            manifestPlaceholders["allowEmulatorTraffic"] = "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.coroutines.play.services)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
