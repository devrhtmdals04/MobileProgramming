package com.mope.app

import android.app.Application
import com.mope.app.data.mock.MockClubRepository
import com.mope.app.data.repository.ClubRepository
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.remote.auth.FirebaseBackend

class MopeApplication : Application() {
    val clubRepository: ClubRepository by lazy { MockClubRepository() }
    val authRepository: AuthRepository? by lazy {
        if (BuildConfig.BACKEND == "mock") null else FirebaseBackend.createAuthRepository(this)
    }
}
