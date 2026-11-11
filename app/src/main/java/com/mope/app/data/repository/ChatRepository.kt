package com.mope.app.data.repository

import com.mope.app.data.model.ChatRoom
import com.mope.app.data.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /** 로그아웃/계정 변경 시 구독은 오류로 종료한다. 다시 로그인한 뒤 재구독한다. */
    fun observeMyRooms(): Flow<List<ChatRoom>>
    suspend fun getOrCreateRoom(clubId: String): String
    /** 최신 50개를 서버 시각, 같은 시각이면 문서 ID 순서로 제공한다. */
    fun observeMessages(roomId: String): Flow<List<Message>>
    /** UI는 최초 전송 전에 생성한 ID를 저장하고 재시도에 그대로 사용한다. */
    fun newMessageId(): String
    /** 같은 ID·발신자·본문의 재시도는 성공, 다른 내용의 ID 재사용은 충돌이다. */
    suspend fun sendMessage(roomId: String, text: String, messageId: String = newMessageId(), expectedUserId: String? = null)
}
