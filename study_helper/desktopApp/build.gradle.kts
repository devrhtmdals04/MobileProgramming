import org.jetbrains.compose.desktop.application.dsl.TargetFormat


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":studyCore"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
        }
        jvmTest.dependencies { implementation(libs.kotlin.test) }
    }
}

compose.desktop {
    application {
        mainClass = "com.example.study_helper.desktop.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("desktop-resources"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Study Helper"
            packageVersion = "1.0.0"
            modules("java.net.http", "jdk.httpserver", "java.prefs", "java.desktop", "jdk.crypto.ec")
            macOS {
                bundleID = "com.example.studyhelper.desktop"
                infoPlist { extraKeysRawXml = "<key>NSMicrophoneUsageDescription</key><string>강의를 녹음하고 학습 노트로 정리합니다.</string>" }
            }
        }
    }
}

val prepareDesktopGame by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("desktop-resources/common")
    commandLine(providers.environmentVariable("PYTHON").orElse("python3").get(), rootProject.file("desktopApp/prepare_game.py"),
        "--output", output.get().asFile,
        "--godot", providers.environmentVariable("GODOT_BIN").orElse("/Applications/Godot.app/Contents/MacOS/Godot").get())
}
tasks.matching { it.name == "prepareAppResources" || it.name == "createDistributable" || it.name == "runDistributable" || it.name.startsWith("package") }.configureEach {
    dependsOn(prepareDesktopGame)
}

val desktopAppDirectory = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
val buildOS = System.getProperty("os.name")
tasks.matching { it.name == "createDistributable" }.configureEach {
    // Compose's resource copy normalizes modes; restore the bundled helper's executable bit.
    val helper = if (buildOS.startsWith("Mac")) desktopAppDirectory.resolve("Study Helper.app/Contents/app/resources/godot")
        else if (buildOS.startsWith("Windows")) null else desktopAppDirectory.resolve("Study Helper/lib/app/resources/godot")
    doLast {
        if (helper != null) {
            check(helper.setExecutable(true, false)) { "Cannot make bundled Godot executable: $helper" }
        }
    }
}
