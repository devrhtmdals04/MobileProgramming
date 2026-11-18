package com.mope.app.ui.screens.request

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.ClubRequest
import com.mope.app.data.repository.*
import com.mope.app.ui.components.LoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ClubRequestState(
    val name: String = "",
    val purpose: String = "",
    val userId: String? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val submitted: Boolean = false,
    val history: LoadState<List<ClubRequest>> = LoadState.Loading,
) {
    val canSubmit: Boolean get() = userId != null && !isSubmitting &&
        name.trim().length in 1..50 && purpose.trim().length in 1..2000
}

class ClubRequestViewModel(
    private val auth: AuthRepository,
    private val repository: ClubRequestRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ClubRequestState())
    val state = mutableState.asStateFlow()
    private var submitJob: Job? = null
    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            auth.currentUser.catch {
                clearSession()
                mutableState.update { it.copy(error = "로그인 상태를 확인하지 못했어요. 다시 로그인해 주세요.") }
            }.collect { user ->
                if (user?.id != mutableState.value.userId || user == null) {
                    submitJob?.cancel(); historyJob?.cancel()
                    if (user == null) clearSession() else {
                        if (saved.get<String>("owner") != user.id) clearSaved()
                        saved["owner"] = user.id
                        mutableState.value = ClubRequestState(
                            name = saved["name"] ?: "", purpose = saved["purpose"] ?: "", userId = user.id,
                        )
                        reloadHistory()
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) {
        if (mutableState.value.isSubmitting) return
        saved["name"] = value
        mutableState.update { it.copy(name = value, error = null, submitted = false) }
    }
    fun onPurposeChange(value: String) {
        if (mutableState.value.isSubmitting) return
        saved["purpose"] = value
        mutableState.update { it.copy(purpose = value, error = null, submitted = false) }
    }
    fun submit() {
        val form = mutableState.value
        if (!form.canSubmit) return
        val owner = requireNotNull(form.userId)
        val name = form.name.trim()
        val purpose = form.purpose.trim()
        mutableState.update { it.copy(isSubmitting = true, error = null, submitted = false) }
        submitJob = viewModelScope.launch {
            try {
                val id = if (saved.get<String>("pendingName") == name && saved.get<String>("pendingPurpose") == purpose) {
                    saved.get<String>("pendingId")
                } else null
                val requestId = id ?: repository.newRequestId().also {
                    saved["pendingName"] = name; saved["pendingPurpose"] = purpose; saved["pendingId"] = it
                }
                repository.submitClubRequest(name, purpose, requestId)
                if (mutableState.value.userId == owner) {
                    clearSaved(); saved["owner"] = owner
                    mutableState.update { it.copy(name = "", purpose = "", isSubmitting = false, submitted = true) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (mutableState.value.userId == owner) mutableState.update {
                    it.copy(isSubmitting = false, error = requestMessage(failure))
                }
            }
        }
    }
    fun reloadHistory() {
        if (mutableState.value.userId == null) return
        historyJob?.cancel()
        mutableState.update { it.copy(history = LoadState.Loading) }
        historyJob = viewModelScope.launch {
            repository.observeMyRequests().catch { failure ->
                mutableState.update { it.copy(history = LoadState.Error(requestMessage(failure))) }
            }.collect { requests ->
                mutableState.update { it.copy(history = if (requests.isEmpty()) LoadState.Empty else LoadState.Content(requests)) }
            }
        }
    }
    private fun clearSession() {
        submitJob?.cancel(); historyJob?.cancel(); clearSaved()
        mutableState.value = ClubRequestState(history = LoadState.Empty)
    }
    private fun clearSaved() {
        listOf("owner", "name", "purpose", "pendingName", "pendingPurpose", "pendingId").forEach { saved.remove<String>(it) }
    }
}

private fun requestMessage(failure: Throwable): String = when ((failure as? RequestFailure)?.reason) {
    RequestFailureReason.INVALID_INPUT -> "이름은 1~50자, 개설 목적은 1~2,000자로 입력해 주세요."
    RequestFailureReason.UNAUTHENTICATED -> "로그인 후 다시 시도해 주세요."
    RequestFailureReason.NETWORK -> "네트워크 연결을 확인한 뒤 다시 제출해 주세요. 같은 요청은 중복 저장되지 않아요."
    RequestFailureReason.PERMISSION_DENIED -> "요청 권한을 확인하지 못했어요. 다시 로그인해 주세요."
    RequestFailureReason.CONFLICT -> "이전 요청과 내용이 달라요. 내용을 확인해 주세요."
    else -> "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
}
