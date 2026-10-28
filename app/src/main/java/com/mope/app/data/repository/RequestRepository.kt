package com.mope.app.data.repository

import com.mope.app.data.model.ClubRequest
import com.mope.app.data.model.MembershipApplication
import com.mope.app.data.model.RequestStatus
import kotlinx.coroutines.flow.Flow

interface RequestRepository {
    suspend fun submitClubRequest(name: String, purpose: String): String
    fun observeMyRequests(): Flow<List<ClubRequest>>
    fun observePendingRequests(): Flow<List<ClubRequest>>
    suspend fun reviewRequest(id: String, status: RequestStatus)
    suspend fun applyToClub(clubId: String, introduction: String): String
    fun observeMyApplications(): Flow<List<MembershipApplication>>
}
