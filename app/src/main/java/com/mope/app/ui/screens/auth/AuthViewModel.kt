package com.mope.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.User
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import com.mope.app.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthFormMode { SignIn, SignUp }

data class AuthFormState(
    val mode: AuthFormMode = AuthFormMode.SignIn,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
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
    private var submitJob: Job? = null

    init {
        observeSession()
    }

    fun setMode(mode: AuthFormMode) {
        mutableForm.update {
            it.copy(mode = mode, errorMessage = null, password = "", displayName = if (mode == AuthFormMode.SignIn) "" else it.displayName)
        }
    }

    fun onEmailChange(value: String) {
        mutableForm.update { it.copy(email = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        mutableForm.update { it.copy(password = value, errorMessage = null) }
    }

    fun onDisplayNameChange(value: String) {
        mutableForm.update { it.copy(displayName = value, errorMessage = null) }
    }

    fun submit() {
        val current = mutableForm.value
        if (current.isSubmitting) return
        submitJob?.cancel()
        mutableForm.update { it.copy(isSubmitting = true, errorMessage = null) }
        submitJob = viewModelScope.launch {
            try {
                when (current.mode) {
                    AuthFormMode.SignIn -> repository.signIn(current.email, current.password)
                    AuthFormMode.SignUp -> repository.signUp(current.email, current.password, current.displayName)
                }
                mutableForm.update { it.copy(isSubmitting = false, password = "", errorMessage = null) }
            } catch (cancelled: CancellationException) {
                mutableForm.update { it.copy(isSubmitting = false) }
                throw cancelled
            } catch (failure: AuthFailure) {
                mutableForm.update {
                    it.copy(isSubmitting = false, errorMessage = AuthMessages.from(failure.reason))
                }
            } catch (_: Throwable) {
                mutableForm.update {
                    it.copy(isSubmitting = false, errorMessage = AuthMessages.from(AuthFailureReason.UNKNOWN))
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                repository.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AuthFailure) {
                mutableForm.update { it.copy(errorMessage = AuthMessages.from(failure.reason)) }
            } catch (_: Throwable) {
                mutableForm.update {
                    it.copy(errorMessage = AuthMessages.from(AuthFailureReason.UNKNOWN))
                }
            }
        }
    }

    fun retrySession() {
        observeSession()
    }

    private fun observeSession() {
        sessionJob?.cancel()
        mutableSession.value = AuthSessionUiState.Restoring
        sessionJob = viewModelScope.launch {
            repository.currentUser
                .catch { error ->
                    val message = when (error) {
                        is AuthFailure -> AuthMessages.from(error.reason)
                        else -> AuthMessages.from(AuthFailureReason.UNKNOWN)
                    }
                    mutableSession.value = AuthSessionUiState.Unavailable(message)
                }
                .collect { user ->
                    mutableSession.value = if (user == null) {
                        AuthSessionUiState.SignedOut
                    } else {
                        AuthSessionUiState.SignedIn(user)
                    }
                }
        }
    }
}

object AuthMessages {
    fun from(reason: AuthFailureReason): String = when (reason) {
        AuthFailureReason.INVALID_INPUT -> "입력 내용을 다시 확인해 주세요."
        AuthFailureReason.INVALID_CREDENTIALS -> "이메일 또는 비밀번호가 올바르지 않아요."
        AuthFailureReason.ACCOUNT_ALREADY_EXISTS -> "이미 가입된 이메일이에요."
        AuthFailureReason.NETWORK -> "네트워크 연결을 확인해 주세요."
        AuthFailureReason.PERMISSION_DENIED -> "이 작업을 수행할 권한이 없어요."
        AuthFailureReason.PROFILE_UNAVAILABLE -> "프로필을 불러오지 못했어요. 다시 시도해 주세요."
        AuthFailureReason.UNKNOWN -> "알 수 없는 오류가 발생했어요."
    }
}
