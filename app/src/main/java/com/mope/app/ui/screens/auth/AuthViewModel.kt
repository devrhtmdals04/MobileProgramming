package com.mope.app.ui.screens.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.User
import com.mope.app.data.repository.*
import com.mope.app.domain.AuthInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class AuthStep { Phone, Code, Profile }

data class AuthFormState(
    val step: AuthStep = AuthStep.Phone,
    val phoneNumber: String = "",
    val code: String = "",
    val displayName: String = "",
    val verificationId: String? = null,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface AuthSessionUiState {
    data object Restoring : AuthSessionUiState
    data object SignedOut : AuthSessionUiState
    data class SignedIn(val user: User) : AuthSessionUiState
    data class Unavailable(val message: String) : AuthSessionUiState
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val mutableForm = MutableStateFlow(AuthFormState())
    val form = mutableForm.asStateFlow()
    private val mutableSession = MutableStateFlow<AuthSessionUiState>(AuthSessionUiState.Restoring)
    val session = mutableSession.asStateFlow()
    private var sessionJob: Job? = null
    private var requestJob: Job? = null
    private var submitJob: Job? = null
    private var generation = 0

    init { observeSession() }

    fun onPhoneNumberChange(value: String) { mutableForm.update { it.copy(phoneNumber = value, errorMessage = null) } }
    fun onCodeChange(value: String) { mutableForm.update { it.copy(code = value, errorMessage = null) } }
    fun onDisplayNameChange(value: String) { mutableForm.update { it.copy(displayName = value, errorMessage = null) } }

    fun requestCode(activity: Activity?) {
        if (mutableForm.value.isSubmitting || mutableForm.value.step == AuthStep.Profile) return
        requestJob?.cancel()
        val requestGeneration = ++generation
        mutableForm.update { it.copy(isSubmitting = true, errorMessage = null, code = "", verificationId = null) }
        requestJob = viewModelScope.launch {
            try {
                val phone = AuthInput.phoneNumber(mutableForm.value.phoneNumber)
                val completed = withTimeoutOrNull(75_000) {
                    repository.requestPhoneCode(phone, activity).collect { event ->
                        if (requestGeneration == generation) when (event) {
                            is PhoneCodeEvent.CodeSent -> mutableForm.update {
                                it.copy(step = AuthStep.Code, phoneNumber = phone, verificationId = event.verificationId, isSubmitting = false)
                            }
                            PhoneCodeEvent.AutomaticallyVerified -> mutableForm.update { it.copy(code = "", verificationId = null, isSubmitting = false) }
                        }
                    }
                    true
                }
                if (completed == null && mutableForm.value.verificationId == null) {
                    showFailure(AuthFailure(AuthFailureReason.NETWORK))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (requestGeneration == generation) showFailure(failure)
            } finally {
                if (requestGeneration == generation) mutableForm.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun changePhoneNumber() {
        if (mutableForm.value.isSubmitting) return
        generation++
        requestJob?.cancel()
        mutableForm.value = AuthFormState(phoneNumber = mutableForm.value.phoneNumber)
    }

    fun submit() {
        val current = mutableForm.value
        if (current.isSubmitting || current.step == AuthStep.Phone) return
        generation++
        requestJob?.cancel()
        mutableForm.update { it.copy(isSubmitting = true, errorMessage = null) }
        submitJob = viewModelScope.launch {
            try {
                when (current.step) {
                    AuthStep.Code -> repository.verifyPhoneCode(current.verificationId.orEmpty(), current.code)
                    AuthStep.Profile -> repository.completeProfile(current.displayName)
                    AuthStep.Phone -> Unit
                }
                mutableForm.update { it.copy(isSubmitting = false, code = "", errorMessage = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { showFailure(failure) }
        }
    }

    fun signOut() {
        generation++
        requestJob?.cancel()
        submitJob?.cancel()
        viewModelScope.launch {
            try {
                repository.signOut()
                mutableForm.value = AuthFormState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { showFailure(failure) }
        }
    }

    fun retrySession() { observeSession() }

    private fun showFailure(failure: Throwable) {
        mutableForm.update { it.copy(isSubmitting = false, errorMessage = AuthMessages.from((failure as? AuthFailure)?.reason ?: AuthFailureReason.UNKNOWN)) }
    }

    private fun observeSession() {
        sessionJob?.cancel()
        mutableSession.value = AuthSessionUiState.Restoring
        sessionJob = viewModelScope.launch {
            repository.session.catch { failure ->
                mutableSession.value = AuthSessionUiState.Unavailable(AuthMessages.from((failure as? AuthFailure)?.reason ?: AuthFailureReason.UNKNOWN))
            }.collect { state ->
                when (state) {
                    AuthSession.SignedOut -> mutableSession.value = AuthSessionUiState.SignedOut
                    AuthSession.ProfileRequired -> {
                        generation++
                        requestJob?.cancel()
                        mutableForm.update { it.copy(step = AuthStep.Profile, code = "", verificationId = null, isSubmitting = false) }
                        mutableSession.value = AuthSessionUiState.SignedOut
                    }
                    is AuthSession.SignedIn -> {
                        generation++
                        requestJob?.cancel()
                        mutableForm.value = AuthFormState()
                        mutableSession.value = AuthSessionUiState.SignedIn(state.user)
                    }
                }
            }
        }
    }
}

object AuthMessages {
    fun from(reason: AuthFailureReason): String = when (reason) {
        AuthFailureReason.INVALID_INPUT -> "전화번호·6자리 인증번호·이름을 다시 확인해 주세요."
        AuthFailureReason.INVALID_CREDENTIALS -> "인증번호가 올바르지 않아요. 다시 확인해 주세요."
        AuthFailureReason.CODE_EXPIRED -> "인증번호가 만료됐어요. 새 인증번호를 요청해 주세요."
        AuthFailureReason.TOO_MANY_REQUESTS -> "요청이 많아요. 잠시 후 다시 시도해 주세요."
        AuthFailureReason.NETWORK -> "네트워크 연결을 확인하고 다시 시도해 주세요."
        AuthFailureReason.PERMISSION_DENIED -> "인증을 사용할 수 없어요. 인증 설정을 확인해 주세요."
        AuthFailureReason.PROFILE_UNAVAILABLE -> "프로필을 저장하거나 불러오지 못했어요. 다시 시도해 주세요."
        AuthFailureReason.UNKNOWN -> "인증 중 오류가 발생했어요. 다시 시도해 주세요."
    }
}
