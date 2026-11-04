package com.mope.app.data.fake

import com.mope.app.data.model.User
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import com.mope.app.data.repository.AuthRepository
import com.mope.app.domain.AuthInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transform

/** 테스트 전용 메모리 저장소. 실제 앱의 비밀번호 저장에 사용하지 않는다. */
class FakeAuthStore {
    data class Account(val uid: String, val password: String, val displayName: String)
    val accounts = mutableMapOf<String, Account>()
    val profiles = mutableMapOf<String, User>()
    var sessionUid: String? = null
    internal var nextId = 1
}

/** 앱에는 주입하지 않는 인증 계약 테스트용 fake. 복원 완료는 테스트에서 제어한다. */
class FakeAuthRepository(val store: FakeAuthStore = FakeAuthStore()) : AuthRepository {
    private sealed interface Session {
        data object Restoring : Session
        data class Ready(val user: User?) : Session
        data class Failed(val failure: AuthFailure) : Session
    }

    private val session = MutableStateFlow<Session>(Session.Restoring)
    var nextProfileFailure: Throwable? = null

    override val currentUser: Flow<User?> = session.transform { state ->
        when (state) {
            Session.Restoring -> Unit
            is Session.Ready -> emit(state.user)
            is Session.Failed -> throw state.failure
        }
    }

    fun completeRestoration() {
        val uid = store.sessionUid
        session.value = when {
            uid == null -> Session.Ready(null)
            store.profiles[uid] != null -> Session.Ready(store.profiles.getValue(uid))
            else -> Session.Failed(AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE))
        }
    }

    override suspend fun signUp(email: String, password: String, displayName: String) {
        val input = AuthInput.signUp(email, password, displayName)
        val key = input.email.lowercase(java.util.Locale.ROOT)
        if (key in store.accounts) fail(AuthFailureReason.ACCOUNT_ALREADY_EXISTS)
        val account = FakeAuthStore.Account("fake-${store.nextId++}", input.password, input.displayName)
        store.accounts[key] = account
        store.sessionUid = account.uid
        publishProfile(account)
    }

    override suspend fun signIn(email: String, password: String) {
        val input = AuthInput.signIn(email, password)
        val account = store.accounts[input.email.lowercase(java.util.Locale.ROOT)]
        if (account == null || account.password != input.password) {
            fail(AuthFailureReason.INVALID_CREDENTIALS)
        }
        store.sessionUid = account.uid
        publishProfile(account)
    }

    private fun publishProfile(account: FakeAuthStore.Account) {
        // 이전 계정의 사용자 정보를 실패 후에도 노출하지 않는다.
        session.value = Session.Restoring
        val failure = nextProfileFailure
        nextProfileFailure = null
        if (failure != null) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val unavailable = AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE, failure)
            session.value = Session.Failed(unavailable)
            throw unavailable
        }
        val user = store.profiles.getOrPut(account.uid) {
            User(account.uid, account.displayName)
        }
        session.value = Session.Ready(user)
    }

    override suspend fun signOut() {
        store.sessionUid = null
        session.value = Session.Ready(null)
    }

    private fun fail(reason: AuthFailureReason): Nothing = throw AuthFailure(reason)
}
