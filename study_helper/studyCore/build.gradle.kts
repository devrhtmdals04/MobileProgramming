import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    jvm()
    android {
        namespace = "com.example.study_helper.core"
        compileSdk = 37
        minSdk = 24
        withHostTest {}
    }
    val framework = XCFramework("StudyCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "StudyCore"
            isStatic = true
            framework.add(this)
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}

tasks.register<JavaExec>("writeContractFixtures") {
    dependsOn("jvmMainClasses")
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.example.study_helper.core.DesktopHostKt")
    args("--fixtures", rootProject.file("godot-runner/tests/fixtures").absolutePath)
}

tasks.register<JavaExec>("runDesktopBridge") {
    dependsOn("jvmMainClasses")
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.example.study_helper.core.DesktopHostKt")
    args("--serve", providers.gradleProperty("bridgeDir").orElse(
        "${System.getProperty("user.home")}/Library/Application Support/Godot/app_userdata/Jade Run · 지식의 유적/study-bridge").get())
}
