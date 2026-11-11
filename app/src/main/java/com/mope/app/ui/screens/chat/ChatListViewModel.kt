package com.mope.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class ChatListViewModel(private val auth: AuthRepository, private val chat: ChatRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatListUiState())
    val state = mutableState.asStateFlow()
    private var subscription: Job? = null

    init { retry() }

    fun retry() {
        subscription?.cancel()
        mutableState.value = ChatListUiState()
        subscription = viewModelScope.launch {
            try {
                auth.currentUser.distinctUntilChangedBy { it?.id }.collectLatest { user ->
                    if (user == null) {
                        mutableState.value = ChatListUiState(isLoading = false, error = ChatMessages.signedOut)
                    } else {
                        mutableState.value = ChatListUiState(userId = user.id)
                        try {
                            chat.observeMyRooms().collect { rooms ->
                                mutableState.value = ChatListUiState(isLoading = false, userId = user.id, rooms = rooms)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val error = ChatMessages.from(failure)
                            mutableState.value = ChatListUiState(isLoading = false,
                                userId = if (error.requiresSignIn) null else user.id, error = error)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = ChatListUiState(isLoading = false, error = ChatMessages.from(failure))
            }
        }
    }
}
