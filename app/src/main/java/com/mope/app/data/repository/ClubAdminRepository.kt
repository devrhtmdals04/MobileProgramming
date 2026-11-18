package com.mope.app.data.repository

import com.mope.app.data.model.Club
import com.mope.app.data.model.RequestStatus
import kotlinx.coroutines.flow.Flow

data class ClubAdminApplication(
    val id: String,
    val clubId: String,
    val clubName: String,
    val applicantId: String,
    val reason: String,
    val status: RequestStatus,
    val createdAtMillis: Long,
    val reviewedBy: String? = null,
    val reviewedAtMillis: Long? = null,
    val verifiedChannel: String = "",
    val reviewNote: String = "",
)

interface ClubAdminRepository {
    suspend fun isOperator(): Boolean
    fun observeMyApplications(): Flow<List<ClubAdminApplication>>
    fun observePendingApplications(): Flow<List<ClubAdminApplication>>
    fun observeLinkedClubs(): Flow<List<Club>>
    suspend fun apply(club: Club, reason: String)
    /** Approve only after independently verifying the canonical official page's channel.
     * Approval and first ownership assignment are atomic; an existing owner cannot be replaced.
     */
    suspend fun review(applicationId: String, approve: Boolean, officialClub: Club?, channel: String, note: String)
    suspend fun updateClub(clubId: String, description: String, recruitment: String)
}
