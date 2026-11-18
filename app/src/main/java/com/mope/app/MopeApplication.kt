package com.mope.app

import android.app.Application
import com.mope.app.data.mock.MockClubRepository
import com.mope.app.data.remote.club.SoongsilClubRepository
import com.mope.app.data.repository.ClubAdminRepository
import com.mope.app.data.remote.club.LinkedClubRepository
import com.mope.app.data.repository.ClubRepository
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ClubRequestRepository
import com.mope.app.data.repository.ChatRepository
import com.mope.app.data.remote.auth.FirebaseBackend

class MopeApplication : Application() {
    val clubRepository: ClubRepository by lazy {
        if (BuildConfig.BACKEND == "firebase") LinkedClubRepository(SoongsilClubRepository(), requireNotNull(clubAdminRepository)) else MockClubRepository()
    }
    private val backend by lazy {
        if (BuildConfig.BACKEND == "mock") null else FirebaseBackend.createRepositories(this)
    }
    val clubAdminRepository: ClubAdminRepository? get() = backend?.clubAdmins
    val authRepository: AuthRepository? get() = backend?.auth
    val clubRequestRepository: ClubRequestRepository? get() = backend?.clubRequests
    val chatRepository: ChatRepository? get() = backend?.chat
}
