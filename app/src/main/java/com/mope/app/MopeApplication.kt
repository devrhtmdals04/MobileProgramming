package com.mope.app

import android.app.Application
import com.mope.app.data.mock.MockClubRepository
import com.mope.app.data.repository.ClubRepository
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ClubRequestRepository
import com.mope.app.data.repository.ChatRepository
import com.mope.app.data.remote.auth.FirebaseBackend

class MopeApplication : Application() {
    val clubRepository: ClubRepository by lazy { MockClubRepository() }
    private val backend by lazy {
        if (BuildConfig.BACKEND == "mock") null else FirebaseBackend.createRepositories(this)
    }
    val authRepository: AuthRepository? get() = backend?.auth
    val clubRequestRepository: ClubRequestRepository? get() = backend?.clubRequests
    val chatRepository: ChatRepository? get() = backend?.chat
}
