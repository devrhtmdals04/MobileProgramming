package com.mope.app.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ChatRepository

data class ChatRoomActions(
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onRetryMessage: (String) -> Unit,
    val onDismissMessage: (String) -> Unit,
    val onRetryConnection: () -> Unit,
)

/** NavBackStackEntry 안에서 호출하면 화면을 pop할 때 ViewModel 구독도 종료된다. */
@Composable
fun ChatListRoute(
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    content: @Composable (ChatListUiState, onRetry: () -> Unit) -> Unit,
) {
    val vm: ChatListViewModel = viewModel(key = "chat:list", factory = viewModelFactory {
        initializer { ChatListViewModel(authRepository, chatRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    content(state, vm::retry)
}

/** 기존 화면 디자인과 별개로 상태와 콜백을 content에 전달한다. */
@Composable
fun ChatRoomRoute(
    target: ChatTarget,
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    content: @Composable (ChatRoomUiState, ChatRoomActions) -> Unit,
) {
    val targetKey = when (target) {
        is ChatTarget.Club -> "club:${target.clubId}"
        is ChatTarget.Room -> "room:${target.roomId}"
    }
    val vm: ChatRoomViewModel = viewModel(key = "chat:$targetKey", factory = viewModelFactory {
        initializer { ChatRoomViewModel(target, authRepository, chatRepository, createSavedStateHandle()) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        ChatRoomActions(vm::onDraftChange, vm::sendDraft, vm::retryMessage, vm::dismissMessage, vm::retryConnection)
    }
    content(state, actions)
}
