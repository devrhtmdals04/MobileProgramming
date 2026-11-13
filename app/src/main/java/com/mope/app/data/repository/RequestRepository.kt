package com.mope.app.data.repository

import com.mope.app.data.model.MembershipApplication
import kotlinx.coroutines.flow.Flow

interface RequestRepository : ClubRequestRepository {
    suspend fun applyToClub(clubId: String, introduction: String): String
    fun observeMyApplications(): Flow<List<MembershipApplication>>
}
