package com.mope.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
fun LiveChatScreen(state: ChatRoomUiState, actions: ChatRoomActions, onBack: () -> Unit, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("동아리 문의", style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onBack) { Text("뒤로") }
        if (state.connection in listOf(ChatConnection.RESTORING, ChatConnection.CONNECTING)) CircularProgressIndicator()
        state.error?.let { error ->
            Text(error.message)
            TextButton(onClick = if (error.requiresSignIn) onLogin else actions.onRetryConnection) {
                Text(if (error.requiresSignIn) "로그인" else "다시 연결")
            }
        }
        if (state.connection == ChatConnection.READY && state.timeline.isEmpty()) Text("첫 메시지를 보내 보세요.")
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.timeline.asReversed(), key = { it.id }) { item ->
                Column {
                    Text((if (item.isMine) "나 · " else "상대 · ") + item.text)
                    if (item.delivery == ChatDelivery.SENDING) Text("전송 중…")
                    if (item.delivery == ChatDelivery.FAILED) {
                        Text(item.error?.message ?: "전송하지 못했어요.")
                        TextButton(onClick = { actions.onRetryMessage(item.id) }) { Text("다시 보내기") }
                        TextButton(onClick = { actions.onDismissMessage(item.id) }) { Text("전송 취소") }
                    }
                }
            }
        }
        if (state.connection == ChatConnection.READY) {
            OutlinedTextField(state.draft, actions.onDraftChange, label = { Text("메시지") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
            state.inputError?.let { Text(it.message) }
            Button(onClick = actions.onSend, enabled = state.canSend) { Text("보내기") }
        }
    }
}
