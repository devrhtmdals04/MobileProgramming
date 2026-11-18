package com.mope.app.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.Club
import com.mope.app.data.repository.*
import com.mope.app.ui.components.LoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ClubAdminState(
    val userId: String? = null,
    val isOperator: Boolean = false,
    val clubs: LoadState<List<Club>> = LoadState.Loading,
    val applications: LoadState<List<ClubAdminApplication>> = LoadState.Loading,
    val pending: LoadState<List<ClubAdminApplication>> = LoadState.Loading,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClubAdminViewModel(
    private val auth: AuthRepository,
    private val repository: ClubAdminRepository,
    private val directory: ClubRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ClubAdminState())
    val state = mutableState.asStateFlow()
    private var observeJob: Job? = null
    private var actionJob: Job? = null
    init { reload() }

    fun reload() {
        observeJob?.cancel(); actionJob?.cancel()
        observeJob = viewModelScope.launch {
            auth.currentUser.catch { actionJob?.cancel(); mutableState.value = ClubAdminState(error = "로그인 상태를 확인하지 못했어요.") }
                .collectLatest { user ->
                    actionJob?.cancel()
                    mutableState.value = ClubAdminState(userId = user?.id)
                    if (user != null) coroutineScope {
                        launch {
                            directory.observeClubs().catch { mutableState.update { it.copy(clubs = LoadState.Error("공식 동아리 목록을 불러오지 못했어요.")) } }
                                .collect { clubs -> mutableState.update { it.copy(clubs = LoadState.Content(clubs)) } }
                        }
                        launch {
                            repository.observeMyApplications().catch { failure -> mutableState.update { it.copy(applications = LoadState.Error(adminMessage(failure))) } }
                                .collect { apps -> mutableState.update { it.copy(applications = if (apps.isEmpty()) LoadState.Empty else LoadState.Content(apps)) } }
                        }
                        launch {
                            try {
                                val operator = repository.isOperator()
                                mutableState.update { it.copy(isOperator = operator) }
                                if (operator) repository.observePendingApplications()
                                    .catch { failure -> mutableState.update { it.copy(pending = LoadState.Error(adminMessage(failure))) } }
                                    .collect { apps -> mutableState.update { it.copy(pending = if (apps.isEmpty()) LoadState.Empty else LoadState.Content(apps)) } }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Throwable) { mutableState.update { it.copy(error = adminMessage(failure)) } }
                        }
                    }
                }
        }
    }
    fun apply(clubId: String, reason: String) {
        val club = (state.value.clubs as? LoadState.Content)?.value?.find { it.id == clubId } ?: return
        act("관리 권한 신청이 접수됐어요. 운영자가 공식 채널로 확인한 뒤 처리합니다.") { repository.apply(club, reason) }
    }
    fun review(id: String, approve: Boolean, channel: String, note: String, confirmed: Boolean) {
        if (!state.value.isOperator || (approve && !confirmed)) return
        val application = (state.value.pending as? LoadState.Content)?.value?.find { it.id == id } ?: return
        val official = (state.value.clubs as? LoadState.Content)?.value?.find { it.id == application.clubId }
        if (approve && official == null) return
        act(if (approve) "확인 기록을 저장하고 동아리 관리자 권한을 연결했어요." else "반려 사유를 저장했어요.") {
            repository.review(id, approve, official, channel, note)
        }
    }
    fun saveClub(id: String, description: String, recruitment: String) {
        if ((state.value.clubs as? LoadState.Content)?.value?.none { it.id == id && it.adminId == state.value.userId } != false) return
        act("동아리 소개와 모집 안내를 저장했어요.") { repository.updateClub(id, description, recruitment) }
    }
    private fun act(notice: String, block: suspend () -> Unit) {
        val uid = state.value.userId ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null, error = null) }
        actionJob = viewModelScope.launch {
            try {
                block()
                if (state.value.userId == uid) mutableState.update { it.copy(busy = false, notice = notice) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (state.value.userId == uid) mutableState.update { it.copy(busy = false, error = adminMessage(failure)) }
            }
        }
    }
}

private fun adminMessage(failure: Throwable): String = when ((failure as? RequestFailure)?.reason) {
    RequestFailureReason.INVALID_INPUT -> "입력 길이와 공식 채널의 HTTPS 주소를 확인해 주세요."
    RequestFailureReason.UNAUTHENTICATED -> "로그인 후 다시 시도해 주세요."
    RequestFailureReason.PERMISSION_DENIED -> "이 작업의 권한이 없거나 운영자 권한이 해제됐어요."
    RequestFailureReason.CONFLICT -> "이미 관리자가 등록됐거나 처리된 신청이에요. 검토 중인 신청은 수정할 수 없어요."
    RequestFailureReason.NETWORK -> "네트워크 연결을 확인하고 다시 시도해 주세요."
    else -> "처리하지 못했어요. 새로고침 후 다시 시도해 주세요."
}
