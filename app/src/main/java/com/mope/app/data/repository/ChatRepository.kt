package com.mope.app.data.repository

import com.mope.app.data.model.ChatRoom
import com.mope.app.data.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeMyRooms(): Flow<List<ChatRoom>>
    suspend fun getOrCreateRoom(clubId: String): String
    fun observeMessages(roomId: String): Flow<List<Message>>
    suspend fun sendMessage(roomId: String, text: String)
}
