package com.mope.app.ui.screens.club

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.data.mock.MockData
import com.mope.app.ui.components.*
import com.mope.app.ui.theme.MopeTheme

@Composable
fun ClubScreen(state: LoadState<ClubDetail>, onBack: () -> Unit, onChat: (String) -> Unit, onApply: (String) -> Unit) {
    Page("동아리 소개") {
        TextButton(onClick = onBack) { Text("뒤로") }
        StateContent(state, onBack) { detail ->
            Text(detail.promotion?.title ?: detail.club.name, style = MaterialTheme.typography.headlineSmall)
            Text(detail.club.name + " · " + detail.club.category)
            Text(detail.promotion?.body ?: detail.club.description)
            Text(detail.club.recruitment)
            Button(onClick = { onChat(detail.club.id) }) { Text("관리자에게 문의") }
            OutlinedButton(onClick = { onApply(detail.club.id) }) { Text("가입 신청") }
        }
    }
}
@Preview(showBackground = true)
@Composable
private fun ClubPreview() = MopeTheme { ClubScreen(LoadState.Content(ClubDetail(MockData.clubs.first())), {}, {}, {}) }
