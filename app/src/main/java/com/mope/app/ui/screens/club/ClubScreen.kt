package com.mope.app.ui.screens.club

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalUriHandler
import com.mope.app.data.mock.MockData
import com.mope.app.ui.components.*
import com.mope.app.ui.theme.MopeTheme

@Composable
fun ClubScreen(state: LoadState<ClubDetail>, onBack: () -> Unit, onChat: (String) -> Unit, onApply: (String) -> Unit, onAdminRequest: ((String) -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    Page("동아리 소개") {
        TextButton(onClick = onBack) { Text("뒤로") }
        StateContent(state, onBack) { detail ->
            Text(detail.promotion?.title ?: detail.club.name, style = MaterialTheme.typography.headlineSmall)
            Text(detail.club.name + " · " + detail.club.category)
            Text(if (detail.club.sourceUrl != null) detail.club.description else detail.promotion?.body ?: detail.club.description)
            Text(detail.club.recruitment)
            detail.club.sourceUrl?.let { url ->
                Text("출처: 숭실대학교 동아리연합회 · 공식 공개 목록")
                OutlinedButton(onClick = { uriHandler.openUri(url) }) { Text("공식 동아리 소개 보기") }
                if (detail.club.adminId.isBlank() && onAdminRequest != null) {
                    OutlinedButton(onClick = { onAdminRequest(detail.club.id) }) { Text("이 동아리 관리 권한 신청") }
                }
            }
            val linked = detail.club.adminId.isNotBlank()
            Button(onClick = { onChat(detail.club.id) }, enabled = linked) {
                Text(if (linked) "관리자에게 문의" else "관리자 계정 연동 전")
            }
            OutlinedButton(onClick = { onApply(detail.club.id) }, enabled = linked) { Text("가입 신청") }
            if (!linked) Text("이 동아리는 아직 그루 운영자 계정과 연결되지 않았어요. 공식 소개에서 연락·가입 안내를 확인해 주세요.")
        }
    }
}
@Preview(showBackground = true)
@Composable
private fun ClubPreview() = MopeTheme { ClubScreen(LoadState.Content(ClubDetail(MockData.clubs.first())), {}, {}, {}) }
