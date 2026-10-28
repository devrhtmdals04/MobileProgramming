package com.mope.app.ui.screens.auth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.mope.app.ui.components.Page

@Composable
fun ProfileScreen(onExit: () -> Unit) {
    Page("내 프로필") {
        Text("Mock 방문자 · 실제 로그인 상태가 아닙니다.")
        Text("프로필과 로그아웃 연동: AUTH-02")
        Button(onClick = onExit) { Text("둘러보기 종료") }
    }
}
