package com.mope.app

import android.app.Application
import com.mope.app.data.mock.MockClubRepository
import com.mope.app.data.repository.ClubRepository

class MopeApplication : Application() {
    val clubRepository: ClubRepository by lazy { MockClubRepository() }
}
