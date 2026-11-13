package com.mope.app.data

import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.mope.app.BuildConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** 에뮬레이터에서만 실제 네트워크를 끊고, 실패해도 기존 무선 설정을 복원한다. */
internal suspend fun <T> withoutEmulatorNetwork(block: suspend () -> T): T {
    check(BuildConfig.BACKEND == "emulator" && Build.MODEL.startsWith("sdk_gphone"))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    val wifi = shell("settings get global wifi_on") == "1"
    val data = shell("settings get global mobile_data") == "1"
    val connectivity = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
    try {
        shell("svc wifi disable")
        shell("svc data disable")
        withTimeout(15_000) { while (connectivity.activeNetwork != null) delay(100) }
        return block()
    } finally {
        withContext(NonCancellable) {
            shell(if (wifi) "svc wifi enable" else "svc wifi disable")
            shell(if (data) "svc data enable" else "svc data disable")
            if (wifi || data) withTimeout(20_000) { while (connectivity.activeNetwork == null) delay(100) }
        }
    }
}
