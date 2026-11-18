package com.mope.app.data.remote.auth

import android.app.Activity
import android.content.SharedPreferences
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.mope.app.data.repository.AuthSession
import com.mope.app.data.repository.PhoneCodeEvent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.mope.app.data.model.User
import com.mope.app.data.model.UserRole
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import com.mope.app.data.repository.AuthRepository
import com.mope.app.domain.AuthInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** 인증 변경과 프로필 저장을 직렬화해 미완성 프로필이 UI로 전달되는 것을 막는다. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirebaseAuthRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val logoutPreferences: SharedPreferences? = null,
) : AuthRepository {
    private val mutation = Mutex()
    private val revision = MutableStateFlow(0L)
    @Volatile private var lastCodeRequest: Pair<String, PhoneAuthProvider.ForceResendingToken>? = null

    init {
        // 앱의 명시적 로그아웃을 SDK의 이전 영속 세션보다 우선한다.
        if (wasExplicitlySignedOut()) auth.signOut()
    }

    override val session: Flow<AuthSession> = combine(
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(Unit) }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        },
        revision,
    ) { _, _ -> Unit }.mapLatest {
        mutation.withLock {
            val account = if (wasExplicitlySignedOut()) null else auth.currentUser
            if (account == null) AuthSession.SignedOut else {
                // Email-only legacy sessions must not bypass the new phone sign-in.
                if (account.phoneNumber == null) {
                    auth.signOut()
                    AuthSession.SignedOut
                } else profileOperation {
                    val document = firestore.collection("users").document(account.uid).get(Source.SERVER).await()
                    if (document.exists()) AuthSession.SignedIn(decodeProfile(document))
                    else AuthSession.ProfileRequired
                }
            }
        }
    }.distinctUntilChanged()

    override fun requestPhoneCode(phoneNumber: String, activity: Activity?): Flow<PhoneCodeEvent> = callbackFlow {
        val normalized = AuthInput.phoneNumber(phoneNumber)
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                launch {
                    try {
                        authenticate(credential)
                        trySend(PhoneCodeEvent.AutomaticallyVerified)
                        close()
                    } catch (failure: Exception) { close(failure) }
                }
            }
            override fun onVerificationFailed(failure: FirebaseException) {
                close(AuthFailure(authFailureReason(failure), failure))
            }
            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                if (isActive) {
                    lastCodeRequest = normalized to token
                    trySend(PhoneCodeEvent.CodeSent(id))
                }
            }
            override fun onCodeAutoRetrievalTimeOut(id: String) { close() }
        }
        try {
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(normalized)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setCallbacks(callbacks)
            lastCodeRequest?.takeIf { it.first == normalized }?.let { options.setForceResendingToken(it.second) }
            if (activity != null) options.setActivity(activity)
            PhoneAuthProvider.verifyPhoneNumber(options.build())
        } catch (failure: Exception) { close(AuthFailure(authFailureReason(failure), failure)) }
        awaitClose { /* Firebase callbacks are Activity-scoped; a cancelled flow ignores late events. */ }
    }

    override suspend fun verifyPhoneCode(verificationId: String, code: String) {
        if (verificationId.isBlank()) throw AuthFailure(AuthFailureReason.CODE_EXPIRED)
        val input = AuthInput.verificationCode(code)
        authenticate(PhoneAuthProvider.getCredential(verificationId, input))
    }

    private suspend fun authenticate(credential: PhoneAuthCredential) {
        mutation.withLock {
            try {
                authOperation { auth.signInWithCredential(credential).await() }
                authOperation { persistLogout(false) }
                // The session flow keeps new users on name entry until profile creation succeeds.
            } finally { revision.value++ }
        }
    }

    override suspend fun completeProfile(displayName: String) {
        val name = AuthInput.displayName(displayName)
        mutation.withLock {
            try {
                val account = auth.currentUser?.takeIf { !wasExplicitlySignedOut() && it.phoneNumber != null }
                    ?: throw AuthFailure(AuthFailureReason.PERMISSION_DENIED)
                profileOperation { ensureProfile(account, name) }
            } finally { revision.value++ }
        }
    }

    override suspend fun signOut() {
        mutation.withLock {
            authOperation {
                persistLogout(true)
                auth.signOut()
            }
            revision.value++
        }
    }

    private fun wasExplicitlySignedOut(): Boolean = logoutPreferences?.getBoolean("signedOut", false) == true

    private suspend fun persistLogout(signedOut: Boolean) {
        val preferences = logoutPreferences ?: return
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("signedOut", signedOut).commit()) {
                "Could not persist authentication logout state"
            }
        }
    }

    private suspend fun ensureProfile(account: FirebaseUser, name: String): User {
        val ref = firestore.collection("users").document(account.uid)
        return firestore.runTransaction { transaction ->
            val existing = transaction.get(ref)
            if (existing.exists()) {
                decodeProfile(existing)
            } else {
                transaction.set(ref, mapOf(
                    "displayName" to name,
                    "role" to "student",
                    "createdAt" to FieldValue.serverTimestamp(),
                ))
                User(account.uid, name, UserRole.STUDENT)
            }
        }.await()
    }

    private fun decodeProfile(document: DocumentSnapshot): User {
        val name = document.getString("displayName")
        val role = when (document.getString("role")) {
            "student" -> UserRole.STUDENT
            "club_admin" -> UserRole.CLUB_ADMIN
            else -> throw AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE)
        }
        if (name == null || name.isBlank() || name.length !in 1..30 || document.getTimestamp("createdAt") == null) {
            throw AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE)
        }
        return User(document.id, name, role)
    }

    private suspend fun <T> profileOperation(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE, failure)
    }

    private suspend fun <T> authOperation(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AuthFailure) {
        throw failure
    } catch (failure: Exception) {
        throw AuthFailure(authFailureReason(failure), failure)
    }
}

internal fun authFailureReason(failure: Exception): AuthFailureReason = when (failure) {
    is FirebaseNetworkException -> AuthFailureReason.NETWORK
    is FirebaseTooManyRequestsException -> AuthFailureReason.TOO_MANY_REQUESTS
    is FirebaseAuthException -> when (failure.errorCode) {
        "ERROR_INVALID_PHONE_NUMBER", "ERROR_MISSING_PHONE_NUMBER", "ERROR_MISSING_VERIFICATION_CODE" -> AuthFailureReason.INVALID_INPUT
        "ERROR_INVALID_VERIFICATION_CODE", "ERROR_INVALID_CREDENTIAL" -> AuthFailureReason.INVALID_CREDENTIALS
        "ERROR_SESSION_EXPIRED", "ERROR_INVALID_VERIFICATION_ID" -> AuthFailureReason.CODE_EXPIRED
        "ERROR_TOO_MANY_REQUESTS", "ERROR_QUOTA_EXCEEDED" -> AuthFailureReason.TOO_MANY_REQUESTS
        "ERROR_USER_DISABLED", "ERROR_OPERATION_NOT_ALLOWED" -> AuthFailureReason.PERMISSION_DENIED
        else -> AuthFailureReason.UNKNOWN
    }
    is FirebaseFirestoreException -> when (failure.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> AuthFailureReason.PERMISSION_DENIED
        FirebaseFirestoreException.Code.UNAVAILABLE -> AuthFailureReason.NETWORK
        else -> AuthFailureReason.UNKNOWN
    }
    else -> AuthFailureReason.UNKNOWN
}
