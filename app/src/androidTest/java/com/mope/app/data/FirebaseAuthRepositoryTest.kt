package com.mope.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.mope.app.BuildConfig
import com.mope.app.MopeApplication
import com.mope.app.data.model.UserRole
import com.mope.app.data.remote.auth.FirebaseAuthRepository
import com.mope.app.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Android SDK and local Auth/Firestore; no production accounts or SMS. */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthRepositoryTest {
    private lateinit var repository: FirebaseAuthRepository
    @Before fun setUp() {
        check(BuildConfig.BACKEND == "emulator")
        auth.signOut(); secondaryAuth.signOut()
        repository = FirebaseAuthRepository(auth, firestore)
    }
    @After fun tearDown() { auth.signOut(); secondaryAuth.signOut() }

    @Test fun phoneSignupProfileRestoreSignOutAndReturningLogin() = runBlocking {
        withTimeout(60_000) {
            val phone = newTestPhone()
            assertNull(repository.currentUser.first())
            val first = repository.testPhoneCode(phone)
            repository.verifyPhoneCode(first.id, first.code)
            assertEquals(AuthSession.ProfileRequired, repository.session.first())
            assertNull(repository.currentUser.first())
            assertEquals(AuthSession.ProfileRequired, FirebaseAuthRepository(auth, firestore).session.first())
            repository.completeProfile(" 승민 ")
            val user = repository.currentUser.first()!!
            assertEquals("승민", user.displayName)
            assertEquals(UserRole.STUDENT, user.role)
            assertEquals(phone, auth.currentUser!!.phoneNumber)
            assertNull(auth.currentUser!!.email)
            val ref = firestore.collection("users").document(user.id)
            val profile = ref.get(Source.SERVER).await()
            assertEquals(setOf("displayName", "role", "createdAt"), profile.data!!.keys)
            assertEquals(user, FirebaseAuthRepository(auth, firestore).currentUser.first())
            repository.signOut(); assertNull(repository.currentUser.first())
            val next = repository.testPhoneCode(phone)
            repository.verifyPhoneCode(next.id, next.code)
            assertEquals(user, repository.currentUser.first())
            repository.completeProfile("덮어쓰지 않음")
            assertEquals(user, repository.currentUser.first())
            assertEquals(profile.getTimestamp("createdAt"), ref.get(Source.SERVER).await().getTimestamp("createdAt"))
        }
    }
    @Test fun wrongCodeDoesNotAuthenticateAndCorrectCodeCanRetry() = runBlocking {
        withTimeout(60_000) {
            val challenge = repository.testPhoneCode(newTestPhone())
            val wrong = if (challenge.code == "000000") "111111" else "000000"
            failure(AuthFailureReason.INVALID_CREDENTIALS) { repository.verifyPhoneCode(challenge.id, wrong) }
            assertNull(repository.currentUser.first())
            failure(AuthFailureReason.INVALID_INPUT) { repository.verifyPhoneCode(challenge.id, "123") }
            repository.verifyPhoneCode(challenge.id, challenge.code)
            assertEquals(AuthSession.ProfileRequired, repository.session.first())
        }
    }
    @Test fun profileWriteFailureIsNotSuccessfulLoginAndCanRetry() = runBlocking {
        withTimeout(60_000) {
            val challenge = repository.testPhoneCode(newTestPhone())
            repository.verifyPhoneCode(challenge.id, challenge.code)
            val denied = FirebaseAuthRepository(auth, secondaryFirestore)
            failure(AuthFailureReason.PROFILE_UNAVAILABLE) { denied.completeProfile("승민") }
            assertEquals(AuthSession.ProfileRequired, repository.session.first())
            repository.completeProfile("승민")
            assertEquals("승민", repository.currentUser.first()!!.displayName)
        }
    }
    @Test fun unfinishedSignupCanBeCancelledAndProfileRequiresAuthentication() = runBlocking {
        withTimeout(60_000) {
            failure(AuthFailureReason.PERMISSION_DENIED) { repository.completeProfile("승민") }
            val challenge = repository.testPhoneCode(newTestPhone())
            repository.verifyPhoneCode(challenge.id, challenge.code)
            repository.signOut()
            assertEquals(AuthSession.SignedOut, repository.session.first())
            failure(AuthFailureReason.PERMISSION_DENIED) { repository.completeProfile("승민") }
        }
    }
    @Test fun applicationProvidesPhoneAuthRepository() = runBlocking {
        withTimeout(60_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MopeApplication
            val wired = requireNotNull(app.authRepository)
            try {
                wired.signOut()
                wired.createPhoneTestUser("앱 연결")
                assertEquals("앱 연결", wired.currentUser.first()!!.displayName)
            } finally { wired.signOut() }
        }
    }
    private suspend fun failure(reason: AuthFailureReason, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (actual: AuthFailure) { assertEquals(reason, actual.reason) }
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
