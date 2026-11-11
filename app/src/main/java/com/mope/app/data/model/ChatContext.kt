package com.mope.app.data.model

/** 로그인 사용자에게 공개할 연락 정보. 이메일·연락처·학적 정보는 포함하지 않는다. */
data class PublicChatContact(
    val userId: String,
    val displayName: String,
    val responseHint: String?,
    val updatedAtMillis: Long,
)

/** 대화 참여자만 조회하는 문의 헤더. adminId는 현재 동아리가 아닌 방의 고정 참여자다. */
data class ChatContext(
    val roomId: String,
    val clubId: String,
    val clubName: String,
    val adminId: String,
    val recruitmentTitle: String?,
    val recruitmentDeadlineMillis: Long?,
    val contact: PublicChatContact?,
)
