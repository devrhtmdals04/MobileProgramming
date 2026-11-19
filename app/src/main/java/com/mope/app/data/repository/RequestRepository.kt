package com.mope.app.data.repository

import com.mope.app.data.model.MembershipApplication
import com.mope.app.data.model.Promotion
import kotlinx.coroutines.flow.Flow

interface RequestRepository : ClubRequestRepository {
    fun observePromotionFeed(): Flow<List<Promotion>>
    suspend fun publishPromotion(clubId: String, title: String, body: String, postId: String = newRequestId()): String
    suspend fun applyToClub(clubId: String, introduction: String): String
    fun observeMyApplications(): Flow<List<MembershipApplication>>
}
