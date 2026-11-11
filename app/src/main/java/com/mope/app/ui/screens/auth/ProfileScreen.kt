package com.mope.app.ui.screens.auth

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.data.model.User
import com.mope.app.data.model.UserRole
import com.mope.app.ui.components.Page
import com.mope.app.ui.theme.MopeTheme

@Composable
fun ProfileScreen(
    user: User?,
    onSignOut: () -> Unit,
    onExitMock: (() -> Unit)? = null,
) {
    Page("내 프로필") {
        if (user == null) {
            Text("Mock 방문자 · 실제 로그인 상태가 아닙니다.")
            Text("프로필과 로그아웃은 인증 연결 후 사용할 수 있어요.")
            if (onExitMock != null) {
                Button(onClick = onExitMock) { Text("둘러보기 종료") }
            }
        } else {
            Text(user.displayName)
            Text(roleLabel(user.role))
            OutlinedButton(onClick = onSignOut) { Text("로그아웃") }
        }
    }
}

private fun roleLabel(role: UserRole): String = when (role) {
    UserRole.STUDENT -> "역할 · 학생"
    UserRole.CLUB_ADMIN -> "역할 · 동아리 관리자"
}

@Preview(showBackground = true)
@Composable
private fun ProfilePreview() = MopeTheme {
    ProfileScreen(user = User("u1", "민성"), onSignOut = {})
}
