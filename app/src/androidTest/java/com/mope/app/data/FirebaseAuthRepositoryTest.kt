package com.mope.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import com.mope.app.BuildConfig
import com.mope.app.MopeApplication
import com.mope.app.data.model.UserRole
import com.mope.app.data.remote.auth.FirebaseAuthRepository
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 실제 Android SDK → Auth/Firestore Emulator → users Rules 통합 검증. */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthRepositoryTest {
    private lateinit var repository: FirebaseAuthRepository
    private val email = "test-${UUID.randomUUID()}@example.com"

    @Before fun setUp() {
        check(BuildConfig.BACKEND == "emulator") { "Run with -PmopeBackend=emulator" }
        auth.signOut()
        secondaryAuth.signOut()
        repository = FirebaseAuthRepository(auth, firestore)
    }

    @After fun tearDown() = runBlocking {
        auth.signOut()
        secondaryAuth.signOut()
    }

    @Test fun signUpProfileRestoreSignOutAndSignIn() = runBlocking {
        withTimeout(60_000) {
            assertNull(repository.currentUser.first())
            repository.signUp(email, "secret123", " 승민 ")
            val user = repository.currentUser.first()!!
            assertEquals("승민", user.displayName)
            assertEquals(UserRole.STUDENT, user.role)
            val profile = firestore.collection("users").document(user.id).get(Source.SERVER).await()
            assertEquals(setOf("displayName", "role", "createdAt"), profile.data!!.keys)
            val createdAt = profile.getTimestamp("createdAt")
            assertNotNull(createdAt)
            assertEquals(user, FirebaseAuthRepository(auth, firestore).currentUser.first())
            repository.signOut()
            assertNull(repository.currentUser.first())
            repository.signIn(email, "secret123")
            assertEquals(user, repository.currentUser.first())
            assertEquals(createdAt, firestore.collection("users").document(user.id).get(Source.SERVER).await().getTimestamp("createdAt"))
        }
    }

    @Test fun duplicateEmailAndWrongPasswordAreMapped() = runBlocking {
        withTimeout(60_000) {
            repository.signUp(email, "secret123", "승민")
            repository.signOut()
            failure(AuthFailureReason.ACCOUNT_ALREADY_EXISTS) { repository.signUp(email, "secret123", "민성") }
            failure(AuthFailureReason.INVALID_CREDENTIALS) { repository.signIn(email, "wrong123") }
            assertNull(repository.currentUser.first())
        }
    }

    @Test fun profileFailureDoesNotEmitSuccessAndNextLoginRepairsIt() = runBlocking {
        withTimeout(60_000) {
            // Auth 가입은 성공하지만 별도 앱의 미인증 Firestore 접근은 Rules가 거절한다.
            repository = FirebaseAuthRepository(auth, secondaryFirestore)
            failure(AuthFailureReason.PROFILE_UNAVAILABLE) { repository.signUp(email, "secret123", "승민") }
            failure(AuthFailureReason.PROFILE_UNAVAILABLE) { repository.currentUser.first() }
            secondaryAuth.signInWithEmailAndPassword(email, "secret123").await()
            repository.signIn(email, "secret123")
            assertEquals("승민", repository.currentUser.first()!!.displayName)
        }
    }

    @Test fun observerWaitsForCommittedProfileDuringSignUp() = runBlocking {
        withTimeout(60_000) {
            val observed = async { repository.currentUser.first { it != null } }
            repository.signUp(email, "secret123", "민성")
            val user = observed.await()!!
            assertEquals("민성", user.displayName)
            assertTrue(firestore.collection("users").document(user.id).get(Source.SERVER).await().exists())
            repository.signOut()
            assertNull(repository.currentUser.first())
        }
    }

    @Test fun applicationProvidesWorkingEmulatorRepository() = runBlocking {
        withTimeout(60_000) {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MopeApplication
            val wired = requireNotNull(application.authRepository)
            try {
                wired.signOut()
                wired.signUp(email, "secret123", "앱 연결")
                assertEquals("앱 연결", wired.currentUser.first()!!.displayName)
            } finally {
                wired.signOut()
            }
        }
    }

    @Test fun passwordResetRequestIsNeutralAndPreservesSession() = runBlocking {
        withTimeout(60_000) {
            repository.signUp(email, "secret123", "승민")
            val user = repository.currentUser.first()
            repository.requestPasswordReset(" $email ")
            repository.requestPasswordReset("unknown-${UUID.randomUUID()}@example.com")
            assertEquals(user, repository.currentUser.first())
            failure(AuthFailureReason.INVALID_INPUT) { repository.requestPasswordReset("invalid") }
            repository.signOut()
            repository.requestPasswordReset(email)
            assertNull(repository.currentUser.first())
        }
    }

    @Test fun passwordResetCodeChangesPasswordWithoutChangingProfile() = runBlocking {
        withTimeout(60_000) {
            repository.signUp(email, "secret123", "승민")
            val user = repository.currentUser.first()!!
            repository.signOut()
            repository.requestPasswordReset(email)
            val connection = URL("http://10.0.2.2:9099/emulator/v1/projects/demo-mope/oobCodes").openConnection() as HttpURLConnection
            val code = try {
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                assertEquals(200, connection.responseCode)
                val codes = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("oobCodes")
                (0 until codes.length()).map { codes.getJSONObject(it) }
                    .last { it.optString("email") == email && it.optString("requestType") == "PASSWORD_RESET" }.getString("oobCode")
            } finally { connection.disconnect() }
            auth.confirmPasswordReset(code, "new-secret123").await()
            failure(AuthFailureReason.INVALID_CREDENTIALS) { repository.signIn(email, "secret123") }
            repository.signIn(email, "new-secret123")
            assertEquals(user, repository.currentUser.first())
        }
    }

    private suspend fun failure(reason: AuthFailureReason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (actual: AuthFailure) {
            assertEquals(reason, actual.reason)
        }
    }

    companion object {
        private lateinit var auth: FirebaseAuth
        private lateinit var firestore: FirebaseFirestore
        private lateinit var secondaryAuth: FirebaseAuth
        private lateinit var secondaryFirestore: FirebaseFirestore

        @JvmStatic @BeforeClass fun initializeSdk() {
            check(BuildConfig.BACKEND == "emulator")
            fun backend(name: String): Pair<FirebaseAuth, FirebaseFirestore> {
                val app = FirebaseApp.initializeApp(InstrumentationRegistry.getInstrumentation().targetContext,
                    FirebaseOptions.Builder().setProjectId("demo-mope")
                        .setApplicationId("1:1234567890:android:mope-tests")
                        .setApiKey("demo-mope-api-key").build(), name)
                val auth = FirebaseAuth.getInstance(app).apply { useEmulator("10.0.2.2", 9099) }
                val store = FirebaseFirestore.getInstance(app).apply {
                    useEmulator("10.0.2.2", 8080)
                    firestoreSettings = FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
                }
                return auth to store
            }
            val primary = backend("auth-tests")
            auth = primary.first
            firestore = primary.second
            val secondary = backend("unauthenticated-store-tests")
            secondaryAuth = secondary.first
            secondaryFirestore = secondary.second
        }

        @JvmStatic @AfterClass fun terminateSdk() = runBlocking {
            if (::firestore.isInitialized) firestore.terminate().await()
            if (::secondaryFirestore.isInitialized) secondaryFirestore.terminate().await()
        }
    }
}
