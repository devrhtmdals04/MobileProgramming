package com.mope.app.ui.screens.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.ui.components.Page
import com.mope.app.ui.theme.MopeTheme

@Composable
fun AuthScreen(onExplore: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Page("MOPE · 모프") {
        Text("우리 대학 동아리의 시작")
        OutlinedTextField(email, { email = it }, label = { Text("이메일") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {}, enabled = false) { Text("로그인 · 연동 예정") }
        Text("회원가입과 인증은 Firebase 연결 후 제공됩니다.")
        OutlinedButton(onClick = onExplore) { Text("Mock 화면 둘러보기") }
    }
}
@Preview(showBackground = true)
@Composable
private fun AuthPreview() = MopeTheme { AuthScreen({}) }
