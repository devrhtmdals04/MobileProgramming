package com.mope.app.ui.screens.chat

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.ui.components.*
import com.mope.app.ui.theme.MopeTheme

@Composable
fun ChatListScreen() {
    Page("내 대화") { Text("아직 대화가 없어요. 동아리 상세에서 관리자에게 문의할 수 있어요."); Text("실시간 대화 연결 예정 · CHAT-01") }
}
@Composable
fun ChatScreen(clubId: String, onBack: () -> Unit) {
    Page("관리자 문의") {
        TextButton(onClick = onBack) { Text("뒤로") }
        Text("문의 대상 동아리: $clubId")
        Text("채팅방 생성과 메시지 송수신은 아직 연결되지 않았습니다.")
        OutlinedTextField("", {}, enabled = false, label = { Text("Firebase 연동 후 메시지 입력") })
    }
}
@Preview(showBackground = true)
@Composable
private fun ChatPreview() = MopeTheme { ChatScreen("photo", {}) }
