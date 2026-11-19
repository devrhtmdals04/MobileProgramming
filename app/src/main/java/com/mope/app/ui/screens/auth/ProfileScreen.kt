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
    onHistory: (() -> Unit)? = null,
    onManageClubs: (() -> Unit)? = null,
) {
    Page("내 프로필") {
        if (user == null) {
            Text("방문자로 둘러보는 중이에요.")
            Text("로그인하면 내 신청과 대화를 확인할 수 있어요.")
            if (onExitMock != null) {
                Button(onClick = onExitMock) { Text("둘러보기 종료") }
            }
        } else {
            Text(user.displayName)
            Text(roleLabel(user.role))
            onHistory?.let { OutlinedButton(onClick = it) { Text("내 가입 신청") } }
            onManageClubs?.let { Button(onClick = it) { Text("내 동아리 관리 · 관리 권한 신청 내역") } }
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
