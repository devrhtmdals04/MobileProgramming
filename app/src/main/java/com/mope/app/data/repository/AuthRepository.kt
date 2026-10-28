package com.mope.app.data.repository

import com.mope.app.data.model.User
import kotlinx.coroutines.flow.Flow

// Firebase 구현 전 합의할 계약. Mock 탐색은 실제 인증으로 취급하지 않는다.
interface AuthRepository {
    val currentUser: Flow<User?>
    suspend fun signIn(email: String, password: String)
    suspend fun signUp(email: String, password: String, displayName: String)
    suspend fun signOut()
}
