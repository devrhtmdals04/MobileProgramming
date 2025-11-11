package com.mope.app.ui.screens.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.ui.components.Page
import com.mope.app.ui.theme.MopeTheme

@Composable
fun AuthScreen(
    form: AuthFormState,
    session: AuthSessionUiState,
    onModeChange: (AuthFormMode) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetrySession: () -> Unit,
    onExplore: (() -> Unit)? = null,
) {
    when (session) {
        AuthSessionUiState.Restoring -> Page("GRU · 그루") {
            Text("로그인 상태를 확인하는 중이에요.")
            CircularProgressIndicator()
        }
        is AuthSessionUiState.Unavailable -> Page("GRU · 그루") {
            Text(session.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetrySession) { Text("다시 시도") }
            if (onExplore != null) {
                OutlinedButton(onClick = onExplore) { Text("Mock 화면 둘러보기") }
            }
        }
        is AuthSessionUiState.SignedIn -> Page("GRU · 그루") {
            Text("${session.user.displayName}님, 잠시만요.")
            CircularProgressIndicator()
        }
        AuthSessionUiState.SignedOut -> AuthFormContent(
            form = form,
            onModeChange = onModeChange,
            onEmailChange = onEmailChange,
            onPasswordChange = onPasswordChange,
            onDisplayNameChange = onDisplayNameChange,
            onSubmit = onSubmit,
            onExplore = onExplore,
        )
    }
}

@Composable
fun AuthScreen(
    onExplore: () -> Unit,
) {
    AuthFormContent(
        form = AuthFormState(),
        onModeChange = {},
        onEmailChange = {},
        onPasswordChange = {},
        onDisplayNameChange = {},
        onSubmit = {},
        onExplore = onExplore,
        submitEnabled = false,
        footer = "회원가입과 인증은 Firebase 연결 후 제공됩니다.",
    )
}

@Composable
private fun AuthFormContent(
    form: AuthFormState,
    onModeChange: (AuthFormMode) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onExplore: (() -> Unit)?,
    submitEnabled: Boolean = !form.isSubmitting,
    footer: String? = null,
) {
    val isSignUp = form.mode == AuthFormMode.SignUp
    Page("GRU · 그루") {
        Text("우리 대학 동아리의 시작")
        Text(
            if (isSignUp) "회원가입" else "로그인",
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = form.email,
            onValueChange = onEmailChange,
            label = { Text("이메일") },
            singleLine = true,
            enabled = !form.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isSignUp) {
            OutlinedTextField(
                value = form.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("표시 이름") },
                singleLine = true,
                enabled = !form.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text("비밀번호") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !form.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
        form.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    form.isSubmitting -> "처리 중…"
                    isSignUp -> "회원가입"
                    else -> "로그인"
                },
            )
        }
        TextButton(
            onClick = {
                onModeChange(if (isSignUp) AuthFormMode.SignIn else AuthFormMode.SignUp)
            },
            enabled = !form.isSubmitting,
        ) {
            Text(if (isSignUp) "이미 계정이 있어요 · 로그인" else "계정이 없어요 · 회원가입")
        }
        footer?.let { Text(it) }
        if (onExplore != null) {
            OutlinedButton(onClick = onExplore) { Text("Mock 화면 둘러보기") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthPreview() = MopeTheme {
    AuthScreen(
        form = AuthFormState(),
        session = AuthSessionUiState.SignedOut,
        onModeChange = {},
        onEmailChange = {},
        onPasswordChange = {},
        onDisplayNameChange = {},
        onSubmit = {},
        onRetrySession = {},
        onExplore = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun AuthSignUpPreview() = MopeTheme {
    AuthScreen(
        form = AuthFormState(mode = AuthFormMode.SignUp, email = "student@university.ac.kr"),
        session = AuthSessionUiState.SignedOut,
        onModeChange = {},
        onEmailChange = {},
        onPasswordChange = {},
        onDisplayNameChange = {},
        onSubmit = {},
        onRetrySession = {},
    )
}
