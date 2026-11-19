package com.mope.app.ui.screens.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.ui.components.Page
import com.mope.app.ui.theme.MopeTheme

@Composable
fun AuthScreen(
    form: AuthFormState,
    session: AuthSessionUiState,
    onPhoneNumberChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onRequestCode: () -> Unit,
    onChangePhoneNumber: () -> Unit,
    onSubmit: () -> Unit,
    onRetrySession: () -> Unit,
    onExplore: (() -> Unit)? = null,
    onCancelProfile: () -> Unit = {},
    emulatorMode: Boolean = false,
    authenticationEnabled: Boolean = true,
) {
    Page("GRU · 그루") {
        when (session) {
            AuthSessionUiState.Restoring -> { Text("로그인 상태를 확인하는 중이에요."); CircularProgressIndicator() }
            is AuthSessionUiState.Unavailable -> {
                Text(session.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetrySession) { Text("다시 시도") }
            }
            is AuthSessionUiState.SignedIn -> { Text("${session.user.displayName}님, 잠시만요."); CircularProgressIndicator() }
            AuthSessionUiState.SignedOut -> {
                Text("우리 대학 동아리의 시작")
                Text(when (form.step) {
                    AuthStep.Phone -> "전화번호로 시작하기"
                    AuthStep.Code -> "인증번호 확인"
                    AuthStep.Profile -> "처음 오셨네요"
                }, style = MaterialTheme.typography.titleLarge)
                when (form.step) {
                    AuthStep.Phone -> {
                        Text("전화번호 인증으로 가입하고 로그인해요.")
                        OutlinedTextField(value = form.phoneNumber, onValueChange = onPhoneNumberChange,
                            label = { Text("휴대전화 번호") }, placeholder = { Text("010-1234-5678") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            enabled = !form.isSubmitting, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("계속하면 인증을 위해 전화번호가 Google에 전송·저장됩니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    AuthStep.Code -> {
                        Text("${form.phoneNumber}로 요청한 6자리 인증번호를 입력해 주세요.")
                        OutlinedTextField(value = form.code, onValueChange = onCodeChange,
                            label = { Text("인증번호 6자리") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            enabled = !form.isSubmitting, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    AuthStep.Profile -> {
                        Text("전화번호 인증을 마쳤어요. 사용할 이름을 알려주세요.")
                        OutlinedTextField(value = form.displayName, onValueChange = onDisplayNameChange,
                            label = { Text("표시 이름") }, supportingText = { Text("1~30자") },
                            enabled = !form.isSubmitting, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (emulatorMode && form.step != AuthStep.Profile) Text("로컬 테스트: 실제 문자는 발송되지 않아요. 인증번호는 개발 터미널에서 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                form.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = if (form.step == AuthStep.Phone) onRequestCode else onSubmit,
                    enabled = authenticationEnabled && !form.isSubmitting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (form.isSubmitting) "처리 중…" else when (form.step) {
                        AuthStep.Phone -> "동의하고 인증번호 받기"
                        AuthStep.Code -> "인증하고 계속하기"
                        AuthStep.Profile -> "가입 완료"
                    })
                }
                if (form.step == AuthStep.Code) {
                    OutlinedButton(onClick = onRequestCode, enabled = !form.isSubmitting) { Text("인증번호 다시 받기") }
                    TextButton(onClick = onChangePhoneNumber, enabled = !form.isSubmitting) { Text("전화번호 변경") }
                }
                if (form.step == AuthStep.Profile) TextButton(onClick = onCancelProfile, enabled = !form.isSubmitting) { Text("다른 번호로 시작") }
                if (!authenticationEnabled) Text("전화번호 인증은 Firebase 연결 후 사용할 수 있어요.")
                if (onExplore != null && form.step != AuthStep.Profile) OutlinedButton(onClick = onExplore, enabled = !form.isSubmitting) { Text("동아리 둘러보기") }
            }
        }
    }
}

@Composable
fun AuthScreen(onExplore: () -> Unit) = AuthScreen(
    form = AuthFormState(), session = AuthSessionUiState.SignedOut,
    onPhoneNumberChange = {}, onCodeChange = {}, onDisplayNameChange = {},
    onRequestCode = {}, onChangePhoneNumber = {}, onSubmit = {}, onRetrySession = {},
    onExplore = onExplore, authenticationEnabled = false,
)

@Preview(showBackground = true)
@Composable
private fun AuthPreview() = MopeTheme { AuthScreen(onExplore = {}) }
