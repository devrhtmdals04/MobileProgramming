package com.mope.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mope.app.data.model.Club
import com.mope.app.data.model.RequestStatus
import com.mope.app.domain.ClubAdminInput
import com.mope.app.ui.components.LoadState
import com.mope.app.ui.components.Page
import com.mope.app.ui.components.StateContent

@Composable
fun ClubAdminScreen(
    state: ClubAdminState,
    targetClubId: String?,
    onApply: (String, String) -> Unit,
    onReview: (String, Boolean, String, String, Boolean) -> Unit,
    onSave: (String, String, String) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val clubs = (state.clubs as? LoadState.Content)?.value.orEmpty()
    Page(if (targetClubId == null) "내 동아리 관리" else "동아리 관리 권한 신청") {
        TextButton(onClick = onBack) { Text("뒤로") }
        if (state.userId == null) {
            Text(state.error ?: "로그인한 회원만 관리 권한을 신청할 수 있어요.")
            Button(onClick = onLogin) { Text("로그인") }
            OutlinedButton(onClick = onReload) { Text("다시 확인") }
        } else {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it) }
            OutlinedButton(onClick = onReload, enabled = !state.busy) { Text("새로고침") }
            if (targetClubId != null) {
                StateContent(state.clubs, onReload) { entries ->
                    val club = entries.find { it.id == targetClubId }
                    if (club == null) Text("공식 목록에서 이 동아리를 찾지 못했어요.") else {
                        Text(club.name, style = MaterialTheme.typography.titleLarge)
                        Text("대표자 여부는 운영자가 학교·동아리연합회에 공개된 공식 채널로 직접 확인해요. 전화번호 로그인만으로 관리 권한이 생기지 않습니다.")
                        OutlinedButton(onClick = { uri.openUri(ClubAdminInput.sourceUrl(club.id)) }) { Text("공식 동아리 소개 확인") }
                        if (club.adminId.isNotBlank()) Text("이미 관리자가 연결됐어요. 권한 이전은 운영자에게 문의해 주세요.")
                        else {
                            var reason by rememberSaveable(state.userId, club.id) { mutableStateOf("") }
                            OutlinedTextField(reason, { reason = it }, label = { Text("직책 및 관리 권한 신청 사유") },
                                supportingText = { Text("${reason.length}/1000") }, minLines = 3,
                                enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { onApply(club.id, reason) }, enabled = !state.busy && reason.trim().length in 1..1000) {
                                Text(if (state.busy) "처리 중…" else "관리 권한 신청")
                            }
                        }
                    }
                }
            } else {
                Text("관리 중인 동아리", style = MaterialTheme.typography.titleLarge)
                StateContent(state.clubs, onReload) { entries ->
                    val owned = entries.filter { it.adminId == state.userId }
                    if (owned.isEmpty()) Text("아직 연결된 동아리가 없어요. 탐색 → 동아리 소개에서 관리 권한을 신청해 주세요.")
                    owned.forEach { club -> ClubEditor(club, state.busy) { description, recruitment -> onSave(club.id, description, recruitment) } }
                }
            }
            Text("내 관리 권한 신청", style = MaterialTheme.typography.titleLarge)
            StateContent(state.applications, onReload) { entries ->
                entries.filter { targetClubId == null || it.clubId == targetClubId }.forEach { entry ->
                    Text(entry.clubName + " · " + when (entry.status) {
                        RequestStatus.PENDING -> "공식 채널 확인 대기"
                        RequestStatus.APPROVED -> "승인됨"
                        RequestStatus.REJECTED -> "반려됨 · 사유를 확인하고 다시 신청할 수 있어요"
                    })
                    Text(entry.reason)
                    if (entry.reviewNote.isNotBlank()) Text("운영자 안내: ${entry.reviewNote}")
                    HorizontalDivider()
                }
            }
            if (state.isOperator && targetClubId == null) {
                Text("그루 운영자 · 관리 권한 검토", style = MaterialTheme.typography.titleLarge)
                Text("신청자가 제시한 연락처를 그대로 신뢰하지 말고, 공식 소개에 등록된 채널을 직접 확인해 주세요.")
                StateContent(state.pending, onReload) { entries ->
                    entries.forEach { entry ->
                        val official = clubs.find { it.id == entry.clubId }
                        var channel by rememberSaveable(state.userId, entry.id) { mutableStateOf("") }
                        var note by rememberSaveable(state.userId, entry.id) { mutableStateOf("") }
                        var confirmed by rememberSaveable(state.userId, entry.id) { mutableStateOf(false) }
                        Text(official?.name ?: "공식 목록 확인 필요: ${entry.clubId}", style = MaterialTheme.typography.titleMedium)
                        Text("신청 계정: ${entry.applicantId}")
                        Text("신청 사유: ${entry.reason}")
                        OutlinedButton(onClick = { uri.openUri(ClubAdminInput.sourceUrl(entry.clubId)) }) { Text("공식 소개에서 연락 채널 확인") }
                        OutlinedTextField(channel, { channel = it }, label = { Text("직접 확인한 공식 채널 HTTPS 주소") },
                            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(note, { note = it }, label = { Text("확인 기록 또는 반려 사유") },
                            placeholder = { Text("예: 공식 SNS 계정의 회신으로 대표자 확인") },
                            enabled = !state.busy, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(confirmed, { confirmed = it }, enabled = !state.busy)
                            Text("공식 채널을 통해 신청자의 관리 권한을 직접 확인했습니다.")
                        }
                        val canReview = !state.busy && entry.applicantId != state.userId && note.trim().length in 1..1000
                        val validChannel = runCatching { ClubAdminInput.channel(channel) }.isSuccess
                        Button(onClick = { onReview(entry.id, true, channel, note, confirmed) },
                            enabled = canReview && official != null && confirmed && validChannel) { Text("확인 완료 · 승인") }
                        OutlinedButton(onClick = { onReview(entry.id, false, "", note, false) }, enabled = canReview) { Text("반려") }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ClubEditor(club: Club, busy: Boolean, onSave: (String, String) -> Unit) {
    var description by rememberSaveable(club.id, club.adminId) { mutableStateOf(club.description) }
    var recruitment by rememberSaveable(club.id, club.adminId) { mutableStateOf(club.recruitment) }
    Text(club.name, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(description, { description = it }, label = { Text("동아리 소개 · 최대 2000자") },
        minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(recruitment, { recruitment = it }, label = { Text("모집 안내 · 최대 1000자") },
        minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
    Button(onClick = { onSave(description, recruitment) }, enabled = !busy && description.trim().length in 1..2000 && recruitment.trim().length <= 1000) { Text("소개·모집 안내 저장") }
    HorizontalDivider()
}
