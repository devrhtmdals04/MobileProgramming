package com.mope.app.data.model

enum class UserRole { STUDENT, CLUB_ADMIN }
enum class RequestStatus { PENDING, APPROVED, REJECTED }
data class User(val id: String, val displayName: String, val role: UserRole = UserRole.STUDENT)
data class Club(val id: String, val name: String, val category: String, val description: String, val recruitment: String, val adminId: String)
data class Promotion(val id: String, val clubId: String, val title: String, val body: String)
data class ChatRoom(val id: String, val clubId: String, val participantIds: List<String>)
data class Message(val id: String, val senderId: String, val text: String, val createdAtMillis: Long?)
data class ClubRequest(val id: String, val applicantId: String, val name: String, val purpose: String, val status: RequestStatus)
data class MembershipApplication(val id: String, val clubId: String, val applicantId: String, val introduction: String, val status: RequestStatus)
