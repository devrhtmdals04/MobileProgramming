package com.mope.app.ui.screens.chat

import com.mope.app.data.model.ChatRoom
import com.mope.app.data.model.Message
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.ChatFailure
import com.mope.app.data.repository.ChatFailureReason

data class ChatUiError(val message: String, val requiresSignIn: Boolean = false, val accessDenied: Boolean = false)

data class ChatListUiState(
    val isLoading: Boolean = true,
    val userId: String? = null,
    val rooms: List<ChatRoom> = emptyList(),
    val error: ChatUiError? = null,
) {
    val isEmpty: Boolean get() = !isLoading && userId != null && error == null && rooms.isEmpty()
}

/** 동아리 상세에서는 Club, 대화 목록에서는 Room을 전달한다. ID 의미를 혼용하지 않는다. */
sealed interface ChatTarget {
    data class Club(val clubId: String) : ChatTarget
    data class Room(val roomId: String) : ChatTarget
}

enum class ChatConnection { RESTORING, CONNECTING, READY, FAILED, SIGNED_OUT }
enum class ChatDelivery { SENT, SENDING, FAILED }
data class PendingChatMessage(val id: String, val text: String, val delivery: ChatDelivery, val error: ChatUiError? = null)
data class ChatTimelineItem(
    val id: String,
    val text: String,
    val isMine: Boolean,
    val createdAtMillis: Long?,
    val delivery: ChatDelivery,
    val error: ChatUiError? = null,
)

data class ChatRoomUiState(
    val connection: ChatConnection = ChatConnection.RESTORING,
    val userId: String? = null,
    val roomId: String? = null,
    val messages: List<Message> = emptyList(),
    val pending: List<PendingChatMessage> = emptyList(),
    val draft: String = "",
    val error: ChatUiError? = null,
    val inputError: ChatUiError? = null,
) {
    val isSending: Boolean get() = pending.any { it.delivery == ChatDelivery.SENDING }
    val canSend: Boolean get() = connection == ChatConnection.READY && userId != null && roomId != null
        && !isSending && draft.isNotBlank()

    /** 서버 수신과 로컬 전송 행은 같은 ID의 한 행으로 합친다. */
    val timeline: List<ChatTimelineItem> get() {
        val outgoing = pending.associateBy { it.id }
        val receivedIds = messages.map { it.id }.toSet()
        return messages.map { message ->
            val local = outgoing[message.id]
            ChatTimelineItem(message.id, message.text, message.senderId == userId, message.createdAtMillis,
                local?.delivery ?: if (message.createdAtMillis == null) ChatDelivery.SENDING else ChatDelivery.SENT,
                local?.error)
        } + pending.filter { it.id !in receivedIds }.map {
            ChatTimelineItem(it.id, it.text, true, null, it.delivery, it.error)
        }
    }
}

internal object ChatMessages {
    fun from(error: Throwable): ChatUiError = when (error) {
        is ChatFailure -> when (error.reason) {
            ChatFailureReason.UNAUTHENTICATED -> ChatUiError("로그인 후 대화를 이용해 주세요.", requiresSignIn = true)
            ChatFailureReason.PERMISSION_DENIED -> ChatUiError("이 대화에 접근할 수 없어요.", accessDenied = true)
            ChatFailureReason.NOT_FOUND -> ChatUiError("동아리 또는 대화를 찾을 수 없어요.", accessDenied = true)
            ChatFailureReason.SELF_CHAT -> ChatUiError("본인이 관리하는 동아리에는 문의할 수 없어요.")
            ChatFailureReason.INVALID_INPUT -> ChatUiError("메시지와 대화 정보를 확인해 주세요.")
            ChatFailureReason.CONFLICT -> ChatUiError("기존 전송 정보와 달라 다시 보낼 수 없어요.")
            ChatFailureReason.NETWORK -> ChatUiError("연결을 확인하고 다시 시도해 주세요.")
            ChatFailureReason.INVALID_DATA -> ChatUiError("대화 정보를 읽지 못했어요. 다시 시도해 주세요.")
            ChatFailureReason.UNKNOWN -> ChatUiError("대화를 처리하지 못했어요. 다시 시도해 주세요.")
        }
        is AuthFailure -> ChatUiError("로그인 정보를 확인하지 못했어요. 다시 시도해 주세요.")
        else -> ChatUiError("대화를 처리하지 못했어요. 다시 시도해 주세요.")
    }
    val signedOut = ChatUiError("로그인 후 대화를 이용해 주세요.", requiresSignIn = true)
    val interrupted = ChatUiError("전송 결과를 확인하지 못했어요. 같은 메시지로 다시 시도할 수 있어요.")
}
