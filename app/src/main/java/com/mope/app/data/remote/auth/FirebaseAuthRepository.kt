package com.mope.app.data.remote.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
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
) : AuthRepository {
    private val mutation = Mutex()
    private val revision = MutableStateFlow(0L)

    override val currentUser: Flow<User?> = combine(
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(Unit) }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        },
        revision,
    ) { _, _ -> Unit }.mapLatest {
        mutation.withLock {
            auth.currentUser?.let { account ->
                profileOperation {
                    decodeProfile(firestore.collection("users").document(account.uid).get(Source.SERVER).await())
                }
            }
        }
    }.distinctUntilChanged()

    override suspend fun signUp(email: String, password: String, displayName: String) {
        val input = AuthInput.signUp(email, password, displayName)
        mutation.withLock {
            try {
                val account = authOperation {
                    auth.createUserWithEmailAndPassword(input.email, input.password).await().user
                        ?: throw AuthFailure(AuthFailureReason.UNKNOWN)
                }
                profileOperation {
                    account.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(input.displayName).build()).await()
                    ensureProfile(account, input.displayName)
                }
            } finally {
                revision.value++
            }
        }
    }

    override suspend fun signIn(email: String, password: String) {
        val input = AuthInput.signIn(email, password)
        mutation.withLock {
            try {
                val account = authOperation {
                    auth.signInWithEmailAndPassword(input.email, input.password).await().user
                        ?: throw AuthFailure(AuthFailureReason.UNKNOWN)
                }
                profileOperation { ensureProfile(account, recoveryName(account)) }
            } finally {
                revision.value++
            }
        }
    }

    override suspend fun signOut() {
        mutation.withLock {
            authOperation { auth.signOut() }
            revision.value++
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

    // Auth 표시 이름 저장 자체가 실패했어도 다음 로그인에서 프로필을 복구할 수 있다.
    private fun recoveryName(account: FirebaseUser): String =
        account.displayName?.trim()?.takeIf { it.isNotBlank() }?.take(30)
            ?: account.email?.substringBefore('@')?.trim()?.takeIf { it.isNotBlank() }?.take(30)
            ?: "학생"

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
    is FirebaseTooManyRequestsException -> AuthFailureReason.PERMISSION_DENIED
    is FirebaseAuthException -> when (failure.errorCode) {
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthFailureReason.ACCOUNT_ALREADY_EXISTS
        "ERROR_INVALID_EMAIL", "ERROR_WEAK_PASSWORD", "ERROR_MISSING_PASSWORD" -> AuthFailureReason.INVALID_INPUT
        "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> AuthFailureReason.INVALID_CREDENTIALS
        "ERROR_USER_DISABLED", "ERROR_OPERATION_NOT_ALLOWED", "ERROR_TOO_MANY_REQUESTS" -> AuthFailureReason.PERMISSION_DENIED
        else -> AuthFailureReason.UNKNOWN
    }
    is FirebaseFirestoreException -> when (failure.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> AuthFailureReason.PERMISSION_DENIED
        FirebaseFirestoreException.Code.UNAVAILABLE -> AuthFailureReason.NETWORK
        else -> AuthFailureReason.UNKNOWN
    }
    else -> AuthFailureReason.UNKNOWN
}
