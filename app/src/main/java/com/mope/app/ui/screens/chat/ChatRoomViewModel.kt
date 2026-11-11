package com.mope.app.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.User
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatRoomViewModel(
    private val target: ChatTarget,
    private val auth: AuthRepository,
    private val chat: ChatRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatRoomUiState())
    val state = mutableState.asStateFlow()
    private var subscription: Job? = null
    private var sendJob: Job? = null
    private var messagesJob: Job? = null
    private var generation = 0L
    private val targetKey = when (target) {
        is ChatTarget.Club -> "club:${target.clubId}"
        is ChatTarget.Room -> "room:${target.roomId}"
    }

    init { retryConnection() }

    fun retryConnection() {
        generation++
        sendJob?.cancel()
        subscription?.cancel()
        mutableState.value = ChatRoomUiState()
        subscription = viewModelScope.launch {
            try {
                auth.currentUser.distinctUntilChangedBy { it?.id }.collectLatest { connect(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                generation++
                sendJob?.cancel()
                mutableState.value = ChatRoomUiState(connection = ChatConnection.FAILED, error = ChatMessages.from(failure))
            }
        }
    }

    private suspend fun connect(user: User?) {
        val currentGeneration = ++generation
        sendJob?.cancel()
        if (user == null) {
            clearPrivateState(ChatMessages.signedOut)
            return
        }
        mutableState.value = restore(user.id)
        try {
            val roomId = when (target) {
                is ChatTarget.Club -> chat.getOrCreateRoom(target.clubId)
                is ChatTarget.Room -> target.roomId
            }
            if (generation != currentGeneration) return
            mutableState.update {
                if (it.roomId != null && it.roomId != roomId) it.copy(roomId = roomId, draft = "", pending = emptyList())
                else it.copy(roomId = roomId)
            }
            persist()
            coroutineScope {
                messagesJob = launch {
                    chat.observeMessages(roomId).collect { messages ->
                        if (generation == currentGeneration) {
                            val confirmed = messages.filter { it.createdAtMillis != null }.map { it.id }.toSet()
                            mutableState.update { current -> current.copy(connection = ChatConnection.READY, messages = messages,
                                pending = current.pending.filterNot { it.delivery == ChatDelivery.SENT && it.id in confirmed }, error = null) }
                            persist()
                        }
                    }
                }
                messagesJob?.join()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation != currentGeneration) return
            connectionFailed(ChatMessages.from(failure))
        }
    }

    fun onDraftChange(value: String) {
        if (mutableState.value.userId == null) return
        if (value.length > MAX_TEXT_LENGTH) {
            mutableState.update { it.copy(inputError = ChatUiError("메시지는 2000자 이내로 입력해 주세요.")) }
            return
        }
        mutableState.update { it.copy(draft = value, inputError = null) }
        persist()
    }

    fun sendDraft() {
        val current = mutableState.value
        if (current.connection != ChatConnection.READY || current.userId == null || current.roomId == null || current.isSending) return
        if (current.draft.isBlank()) {
            mutableState.update { it.copy(inputError = ChatUiError("메시지를 입력해 주세요.")) }
            return
        }
        val pending = PendingChatMessage(chat.newMessageId(), current.draft, ChatDelivery.SENDING)
        mutableState.update { it.copy(draft = "", inputError = null, pending = it.pending + pending) }
        persist()
        transmit(pending)
    }

    fun retryMessage(messageId: String) {
        val current = mutableState.value
        if (current.connection != ChatConnection.READY || current.isSending) return
        val pending = current.pending.firstOrNull { it.id == messageId && it.delivery == ChatDelivery.FAILED } ?: return
        mutableState.update { it.copy(pending = it.pending.map { item ->
            if (item.id == messageId) item.copy(delivery = ChatDelivery.SENDING, error = null) else item
        }) }
        persist()
        transmit(pending)
    }

    /** 실패/전송 완료 행만 제거한다. 전송 중에는 결과가 불명확하므로 제거하지 않는다. */
    fun dismissMessage(messageId: String) {
        mutableState.update { it.copy(pending = it.pending.filterNot { item -> item.id == messageId && item.delivery != ChatDelivery.SENDING }) }
        persist()
    }

    private fun transmit(message: PendingChatMessage) {
        val current = mutableState.value
        val roomId = current.roomId ?: return
        val owner = current.userId ?: return
        val currentGeneration = generation
        sendJob = viewModelScope.launch {
            try {
                chat.sendMessage(roomId, message.text, message.id, expectedUserId = owner)
                if (generation == currentGeneration && mutableState.value.userId == owner) {
                    mutableState.update { state -> state.copy(pending = state.pending.mapNotNull {
                        when {
                            it.id != message.id -> it
                            state.messages.any { remote -> remote.id == it.id && remote.createdAtMillis != null } -> null
                            else -> it.copy(delivery = ChatDelivery.SENT, error = null)
                        }
                    }) }
                    persist()
                }
            } catch (cancelled: CancellationException) {
                if (generation == currentGeneration) markFailed(message.id, ChatMessages.interrupted)
                throw cancelled
            } catch (failure: Exception) {
                if (generation == currentGeneration && mutableState.value.userId == owner) {
                    val error = ChatMessages.from(failure)
                    if (error.requiresSignIn || error.accessDenied) connectionFailed(error)
                    else markFailed(message.id, error)
                }
            }
        }
    }

    private fun markFailed(id: String, error: ChatUiError) {
        mutableState.update { it.copy(pending = it.pending.map { item ->
            if (item.id == id) item.copy(delivery = ChatDelivery.FAILED, error = error) else item
        }) }
        persist()
    }

    private fun connectionFailed(error: ChatUiError) {
        generation++
        sendJob?.cancel()
        messagesJob?.cancel()
        if (error.requiresSignIn || error.accessDenied) clearPrivateState(error)
        else {
            mutableState.update { current -> current.copy(connection = ChatConnection.FAILED, error = error,
                pending = current.pending.map { if (it.delivery == ChatDelivery.SENDING) it.copy(delivery = ChatDelivery.FAILED, error = ChatMessages.interrupted) else it }) }
            persist()
        }
    }

    private fun clearPrivateState(error: ChatUiError) {
        savedStateHandle.remove<ArrayList<String>>(SAVED_KEY)
        mutableState.value = ChatRoomUiState(connection = if (error.requiresSignIn) ChatConnection.SIGNED_OUT else ChatConnection.FAILED, error = error)
    }

    private fun restore(owner: String): ChatRoomUiState {
        val saved = savedStateHandle.get<ArrayList<String>>(SAVED_KEY)
        if (saved == null || saved.size < 4 || saved[0] != owner || saved[1] != targetKey || (saved.size - 4) % 2 != 0) {
            savedStateHandle.remove<ArrayList<String>>(SAVED_KEY)
            return ChatRoomUiState(connection = ChatConnection.CONNECTING, userId = owner)
        }
        val pending = saved.drop(4).chunked(2).map {
            PendingChatMessage(it[0], it[1], ChatDelivery.FAILED, ChatMessages.interrupted)
        }.distinctBy { it.id }
        return ChatRoomUiState(connection = ChatConnection.CONNECTING, userId = owner,
            roomId = saved[3].takeIf { it.isNotEmpty() }, draft = saved[2], pending = pending)
    }

    private fun persist() {
        val current = mutableState.value
        val owner = current.userId ?: return
        val saved = arrayListOf(owner, targetKey, current.draft, current.roomId.orEmpty())
        current.pending.filter { it.delivery != ChatDelivery.SENT }.forEach { saved.add(it.id); saved.add(it.text) }
        savedStateHandle[SAVED_KEY] = saved
    }

    companion object {
        const val MAX_TEXT_LENGTH = 2000
        private const val SAVED_KEY = "chat.room.state.v1"
    }
}
