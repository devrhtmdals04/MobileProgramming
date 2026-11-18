package com.mope.app.data.fake

import android.app.Activity
import com.mope.app.data.model.User
import com.mope.app.data.repository.*
import com.mope.app.domain.AuthInput
import kotlinx.coroutines.flow.*

class FakeAuthStore {
    val accounts = mutableMapOf<String, String>()
    val profiles = mutableMapOf<String, User>()
    var sessionUid: String? = null
}

class FakeAuthRepository(val store: FakeAuthStore = FakeAuthStore()) : AuthRepository {
    private val state = MutableStateFlow<AuthSession?>(null)
    override val session: Flow<AuthSession> = state.filterNotNull()
    var requests = 0
    var verifications = 0
    var nextProfileFailure: Throwable? = null
    private val challenges = mutableMapOf<String, String>()
    fun completeRestoration() {
        val uid = store.sessionUid
        state.value = if (uid == null) AuthSession.SignedOut else store.profiles[uid]?.let { AuthSession.SignedIn(it) } ?: AuthSession.ProfileRequired
    }
    override fun requestPhoneCode(phoneNumber: String, activity: Activity?): Flow<PhoneCodeEvent> = flow {
        val phone = AuthInput.phoneNumber(phoneNumber)
        requests++
        val id = "challenge-$requests"
        challenges[id] = phone
        emit(PhoneCodeEvent.CodeSent(id))
    }
    override suspend fun verifyPhoneCode(verificationId: String, code: String) {
        verifications++
        AuthInput.verificationCode(code)
        val phone = challenges[verificationId] ?: throw AuthFailure(AuthFailureReason.CODE_EXPIRED)
        if (code != "123456") throw AuthFailure(AuthFailureReason.INVALID_CREDENTIALS)
        challenges.remove(verificationId)
        store.sessionUid = store.accounts.getOrPut(phone) { "fake-${store.accounts.size + 1}" }
        completeRestoration()
    }
    override suspend fun completeProfile(displayName: String) {
        val name = AuthInput.displayName(displayName)
        val uid = store.sessionUid ?: throw AuthFailure(AuthFailureReason.PERMISSION_DENIED)
        nextProfileFailure?.let { nextProfileFailure = null; throw it }
        store.profiles.getOrPut(uid) { User(uid, name) }
        completeRestoration()
    }
    override suspend fun signOut() { store.sessionUid = null; completeRestoration() }
}
