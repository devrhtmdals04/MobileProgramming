package com.mope.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.mope.app.BuildConfig
import com.mope.app.MopeApplication
import com.mope.app.data.repository.AuthSession
import com.mope.app.data.repository.PhoneCodeEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in cloud smoke test. Only a console-configured fictional number is allowed.
 * Supply testSmsCode at runtime; never store it in source or use a real phone number here.
 * App verification bypass exists only in the instrumentation process, not application code.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseProjectConnectionTest {
    @Test fun configuredTestPhoneCanCreateProfileAndSignInAgain() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.BACKEND == "firebase" && args.getString("firebaseConnection") == "true")
        check(BuildConfig.DEBUG)
        val phone = requireNotNull(args.getString("testPhone"))
        require(phone == "+16505550101") { "Only the designated fictional test number is allowed" }
        val code = requireNotNull(args.getString("testSmsCode"))
        require(code.matches(Regex("[0-9]{6}")))
        withTimeout(90_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MopeApplication
            val repository = requireNotNull(app.authRepository)
            check(FirebaseApp.getInstance().options.projectId == "gru-campus")
            val sdk = FirebaseAuth.getInstance()
            sdk.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)
            val store = FirebaseFirestore.getInstance()
            try {
                repository.signOut()
                suspend fun authenticate() {
                    val challenge = repository.requestPhoneCode(phone, null)
                        .first { it is PhoneCodeEvent.CodeSent } as PhoneCodeEvent.CodeSent
                    repository.verifyPhoneCode(challenge.verificationId, code)
                }
                authenticate()
                if (repository.session.first() == AuthSession.ProfileRequired) repository.completeProfile("연결 확인")
                val user = requireNotNull(repository.currentUser.first())
                assertEquals(phone, sdk.currentUser!!.phoneNumber)
                val document = store.collection("users").document(user.id).get(Source.SERVER).await()
                assertEquals(user.displayName, document.getString("displayName"))
                assertNotNull(document.getTimestamp("createdAt"))
                assertEquals(setOf("displayName", "role", "createdAt"), document.data!!.keys)
                try {
                    store.collection("users").document("connection-other-user").get(Source.SERVER).await()
                    fail("Other users' private profiles must be denied")
                } catch (failure: FirebaseFirestoreException) {
                    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, failure.code)
                }
                repository.signOut()
                assertEquals(AuthSession.SignedOut, repository.session.first())
                authenticate()
                assertEquals(user, repository.currentUser.first())
            } finally {
                repository.signOut()
                sdk.firebaseAuthSettings.setAppVerificationDisabledForTesting(false)
            }
        }
    }
}
