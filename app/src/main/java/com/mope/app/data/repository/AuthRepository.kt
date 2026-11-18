package com.mope.app.data.repository

import android.app.Activity
import com.mope.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface AuthSession {
    data object SignedOut : AuthSession
    data object ProfileRequired : AuthSession
    data class SignedIn(val user: User) : AuthSession
}

sealed interface PhoneCodeEvent {
    data class CodeSent(val verificationId: String) : PhoneCodeEvent
    data object AutomaticallyVerified : PhoneCodeEvent
}

/** Phone-only authentication. A verified account is not ready until its users profile exists.
 * Phone numbers stay in Firebase Auth; they are not copied to public profiles.
 * Verification IDs/codes are transient and must never be logged or persisted.
 */
interface AuthRepository {
    val session: Flow<AuthSession>
    val currentUser: Flow<User?> get() = session.map { (it as? AuthSession.SignedIn)?.user }
    fun requestPhoneCode(phoneNumber: String, activity: Activity?): Flow<PhoneCodeEvent>
    suspend fun verifyPhoneCode(verificationId: String, code: String)
    suspend fun completeProfile(displayName: String)
    suspend fun signOut()
}
