package com.mope.app.ui.chat

import com.mope.app.data.model.ChatRoom
import com.mope.app.data.model.Message
import com.mope.app.data.model.User
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ChatRepository
import com.mope.app.data.repository.ChatFailure
import com.mope.app.data.repository.ChatFailureReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal class ChatAuthFixture : AuthRepository {
    val users = MutableStateFlow<User?>(User("alice", "학생"))
    var failure: Exception? = null
    override val currentUser: Flow<User?> get() = flow {
        failure?.let { throw it }
        emitAll(users)
    }
    override suspend fun signIn(email: String, password: String) = error("Not used by chat tests")
    override suspend fun signUp(email: String, password: String, displayName: String) = error("Not used by chat tests")
    override suspend fun signOut() { users.value = null }
}

internal class ChatFixture(private val auth: ChatAuthFixture) : ChatRepository {
    data class Sent(val roomId: String, val text: String, val id: String, val owner: String)
    var activeRoomListeners = 0
    var activeMessageListeners = 0
    var generatedIds = 0
    val openedClubs = mutableListOf<String>()
    val sent = mutableListOf<Sent>()
    var openFailure: Exception? = null
    var sendAction: suspend (Sent) -> Unit = {}
    private val roomFeeds = mutableMapOf<String, MutableSharedFlow<Result<List<ChatRoom>>>>()
    private val messageFeeds = mutableMapOf<Pair<String, String>, MutableSharedFlow<Result<List<Message>>>>()

    fun rooms(owner: String = "alice") = roomFeeds.getOrPut(owner) {
        MutableSharedFlow<Result<List<ChatRoom>>>(replay = 1).apply { tryEmit(Result.success(emptyList())) }
    }
    fun messages(owner: String = "alice", room: String = "club__alice") = messageFeeds.getOrPut(owner to room) {
        MutableSharedFlow<Result<List<Message>>>(replay = 1).apply { tryEmit(Result.success(emptyList())) }
    }
    override fun observeMyRooms(): Flow<List<ChatRoom>> = flow {
        val owner = requireNotNull(auth.users.value).id
        activeRoomListeners++
        try { rooms(owner).collect { emit(it.getOrThrow()) } }
        finally { activeRoomListeners-- }
    }
    override suspend fun getOrCreateRoom(clubId: String): String {
        openedClubs.add(clubId)
        openFailure?.let { throw it }
        return "${clubId}__${requireNotNull(auth.users.value).id}"
    }
    override fun observeMessages(roomId: String): Flow<List<Message>> = flow {
        val owner = requireNotNull(auth.users.value).id
        activeMessageListeners++
        try { messages(owner, roomId).collect { emit(it.getOrThrow()) } }
        finally { activeMessageListeners-- }
    }
    override fun newMessageId(): String = "message-${++generatedIds}"
    override suspend fun sendMessage(roomId: String, text: String, messageId: String, expectedUserId: String?) {
        if (expectedUserId != null && expectedUserId != auth.users.value?.id) throw ChatFailure(ChatFailureReason.UNAUTHENTICATED)
        val request = Sent(roomId, text, messageId, requireNotNull(auth.users.value).id)
        sent.add(request)
        sendAction(request)
        val feed = messages(request.owner, roomId)
        val existing = feed.replayCache.lastOrNull()?.getOrNull().orEmpty()
        val confirmed = Message(messageId, request.owner, text, 1000L)
        feed.emit(Result.success(existing.filterNot { it.id == messageId } + confirmed))
    }
}
