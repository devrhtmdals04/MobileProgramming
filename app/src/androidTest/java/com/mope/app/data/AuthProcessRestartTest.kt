package com.mope.app.data

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mope.app.BuildConfig
import com.mope.app.MopeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 각 단계는 호스트 스크립트가 force-stop 후 별도 프로세스에서 실행한다. */
@RunWith(AndroidJUnit4::class)
class AuthProcessRestartTest {
    @Test fun sessionStage() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("sessionStage")
        assumeTrue("Run npm run test:auth:restart for process stages", stage != null)
        check(BuildConfig.BACKEND == "emulator")
        withTimeout(60_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as MopeApplication
            val prefs = app.getSharedPreferences("auth-process-test", 0)
            val repository = requireNotNull(app.authRepository)
            if (stage != "seed") {
                assertTrue("Previous stage must finish first", prefs.contains("pid"))
                assertNotEquals("Must use a new app process", prefs.getInt("pid", -1), Process.myPid())
            }
            when (stage) {
                "seed" -> {
                    repository.signOut()
                    repository.signUp("restart-${UUID.randomUUID()}@example.com", "restart123", "재실행 검증")
                    val user = repository.currentUser.first()!!
                    assertTrue(prefs.edit().putString("uid", user.id).commit())
                }
                "restored", "signOut" -> {
                    val user = repository.currentUser.first()!!
                    assertEquals(prefs.getString("uid", null), user.id)
                    assertEquals("재실행 검증", user.displayName)
                    if (stage == "signOut") {
                        repository.signOut()
                        assertNull(repository.currentUser.first())
                    }
                }
                "loggedOut" -> assertNull(repository.currentUser.first())
                else -> error("Unknown session stage: $stage")
            }
            assertTrue(prefs.edit().putInt("pid", Process.myPid()).commit())
            instrumentation.sendStatus(0, Bundle().apply { putString("sessionStageResult", "$stage:passed") })
        }
    }
}
