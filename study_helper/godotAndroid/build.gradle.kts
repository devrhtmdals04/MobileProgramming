plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val gameDirectory = rootProject.layout.projectDirectory.dir("godot-runner")
val generatedAssets = layout.buildDirectory.dir("generated/godotAssets")
val gameArchive = layout.buildDirectory.file("intermediates/godot/runner.zip")
val godotExecutable = providers.environmentVariable("GODOT_BIN")
    .orElse("/Applications/Godot.app/Contents/MacOS/Godot")

val importGodotAssets by tasks.registering(Exec::class) {
    group = "godot"
    description = "Import Godot resources before creating the Android game pack."
    commandLine(godotExecutable.get(), "--headless", "--path", gameDirectory.asFile,
        "--editor", "--import", "--quit")
}

val packGodotGame by tasks.registering(Exec::class) {
    group = "godot"
    description = "Export the 3D runner resources for the Android host."
    dependsOn(importGodotAssets)
    val outputDirectory = gameArchive.get().asFile.parentFile
    doFirst { outputDirectory.mkdirs() }
    commandLine(godotExecutable.get(), "--headless", "--path", gameDirectory.asFile,
        "--export-pack", "Android pack", gameArchive.get().asFile)
}

val unpackGodotGame by tasks.registering(Sync::class) {
    dependsOn(packGodotGame)
    from(zipTree(gameArchive))
    into(generatedAssets)
}

android {
    namespace = "com.example.study_helper.jaderun"
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("../native-whisper/CMakeLists.txt"); version = "3.22.1" } }
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.study_helper.jaderun"
        minSdk = 24
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets["main"].assets.directories.add(generatedAssets.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(rootProject.file("native-whisper/notices").absolutePath)
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.gitignore:!.ds_store:!*.scc:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
        // Godot seeks within these resources. APK deflate makes each seek costly.
        noCompress += listOf("scn", "res", "fontdata", "ctex", "sample", "gdc", "binary")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = false }
    buildFeatures { compose = true }
}

tasks.named("preBuild") { dependsOn(unpackGodotGame) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(project(":studyCore"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation("org.godotengine:godot:4.6.2.stable")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
}
