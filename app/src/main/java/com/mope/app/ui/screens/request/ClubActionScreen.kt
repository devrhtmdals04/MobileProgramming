package com.mope.app.ui.screens.request

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mope.app.data.model.Club
import com.mope.app.data.model.ClubRequest
import com.mope.app.data.model.RequestStatus
import com.mope.app.data.repository.*
import com.mope.app.ui.components.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Composable
fun ClubActionScreen(clubId: String?, publish: Boolean, uid: String?, directory: ClubRepository,
    requests: RequestRepository, onBack: () -> Unit, onLogin: () -> Unit) {
    val heading = when {
        clubId == null -> "내 가입 신청"
        publish -> "홍보글 작성"
        else -> "동아리 가입 신청"
    }
    Page(heading) {
        TextButton(onClick = onBack) { Text("뒤로") }
        if (uid == null) {
            Text("로그인하면 이용할 수 있어요.")
            Button(onClick = onLogin) { Text("로그인") }
        } else key(uid, clubId, publish) {
            var retry by remember { mutableIntStateOf(0) }
            var loadError by remember(retry) { mutableStateOf(false) }
            val clubs by remember(directory, retry) {
                directory.observeClubs().catch { if (clubId == null) emit(emptyList()) else loadError = true }
            }.collectAsStateWithLifecycle(initialValue = null)
            val history by remember(requests, retry) {
                if (publish) flowOf(emptyList()) else requests.observeMyApplications().catch { loadError = true }
            }.collectAsStateWithLifecycle(initialValue = null)
            if (loadError) {
                Text("정보를 불러오지 못했어요. 연결을 확인해 주세요.")
                Button(onClick = { retry++ }) { Text("다시 시도") }
            } else if ((clubId != null && clubs == null) || history == null) {
                CircularProgressIndicator()
            } else if (clubId == null) {
                if (history.orEmpty().isEmpty()) Text("아직 가입 신청이 없어요.")
                history.orEmpty().forEach { application ->
                    Text(clubs.orEmpty().find { it.id == application.clubId }?.name ?: application.clubId,
                        style = MaterialTheme.typography.titleMedium)
                    Text("신청 접수 · " + application.introduction)
                }
            } else {
                val club = clubs.orEmpty().find { it.id == clubId }
                val existing = history.orEmpty().find { it.clubId == clubId }
                when {
                    club == null -> Text("동아리를 찾을 수 없어요.")
                    existing != null && !publish -> { Text(club.name); Text("가입 신청이 접수됐어요."); Text(existing.introduction) }
                    publish && club.adminId != uid -> Text("이 동아리의 관리자만 홍보글을 작성할 수 있어요.")
                    else -> ClubActionForm(club, publish, requests, onBack)
                }
            }
        }
    }
}

@Composable
private fun ClubActionForm(club: Club, publish: Boolean, requests: RequestRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var postId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val limit = if (publish) 5000 else 1000
    Text(club.name)
    if (!done) {
        if (publish) OutlinedTextField(title, { title = it }, enabled = !busy,
            singleLine = true, label = { Text("홍보글 제목 (최대 100자)") }, isError = title.length > 100)
        OutlinedTextField(body, { body = it }, enabled = !busy, minLines = 3, isError = body.length > limit,
            label = { Text(if (publish) "홍보 내용" else "자기소개") }, supportingText = { Text("${body.length} / $limit") })
        Button(enabled = !busy, onClick = {
            if (busy) return@Button
            if (body.isBlank() || body.length > limit || publish && (title.isBlank() || title.length > 100)) {
                feedback = "필수 입력란과 글자 수를 확인해 주세요."
                return@Button
            }
            busy = true
            feedback = ""
            scope.launch {
                try {
                    if (publish) {
                        val id = postId ?: requests.newRequestId().also { postId = it }
                        requests.publishPromotion(club.id, title, body, id)
                    } else requests.applyToClub(club.id, body)
                    done = true
                } catch (e: CancellationException) { throw e }
                catch (e: RequestFailure) {
                    feedback = when (e.reason) {
                        RequestFailureReason.CONFLICT -> if (publish) "같은 요청으로 등록된 글이 있어요. 홈에서 확인해 주세요." else "이미 신청했어요. 내 가입 신청에서 확인해 주세요."
                        RequestFailureReason.PERMISSION_DENIED -> "권한이 없어 제출할 수 없어요. 로그인 계정을 확인해 주세요."
                        RequestFailureReason.NOT_FOUND -> "등록된 동아리를 찾을 수 없어요."
                        else -> "제출을 확인하지 못했어요. 연결을 확인하고 다시 시도해 주세요."
                    }
                } finally { busy = false }
            }
        }) { Text(if (busy) "제출 중…" else if (publish) "홍보글 등록" else "가입 신청 제출") }
    }
    if (done || feedback.isNotEmpty()) Text(if (done) {
        if (publish) "홍보글을 등록했어요." else "가입 신청이 접수됐어요."
    } else feedback, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (done) TextButton(onClick = onBack) { Text("동아리로 돌아가기") }
}

@Composable
fun PendingClubRequests(requests: ClubRequestRepository) {
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember(retry) { mutableStateOf(false) }
    val pending by remember(requests, retry) {
        requests.observePendingRequests().catch { failed = true }
    }.collectAsStateWithLifecycle(initialValue = null)
    var selected by remember { mutableStateOf<ClubRequest?>(null) }
    var approve by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("동아리 개설 요청 검토", style = MaterialTheme.typography.titleLarge)
    when {
        failed -> TextButton(onClick = { retry++ }) { Text("대기 요청 다시 불러오기") }
        pending == null -> CircularProgressIndicator()
        pending.orEmpty().isEmpty() -> Text("검토할 개설 요청이 없어요.")
        else -> pending.orEmpty().forEach { request ->
            Text(request.name, style = MaterialTheme.typography.titleMedium)
            Text(request.purpose)
            Button(onClick = { selected = request; approve = true }, enabled = !busy) { Text("개설 요청 승인") }
            OutlinedButton(onClick = { selected = request; approve = false }, enabled = !busy) { Text("개설 요청 반려") }
        }
    }
    if (notice.isNotEmpty()) Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    selected?.let { request ->
        val action = if (approve) "승인" else "반려"
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("${request.name} 개설 요청을 ${action}할까요?") },
            text = { Text("처리 결과는 신청자의 내역에 표시되며 다시 변경할 수 없어요.") },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("취소") } },
            confirmButton = { Button(onClick = {
                val status = if (approve) RequestStatus.APPROVED else RequestStatus.REJECTED
                selected = null
                busy = true
                scope.launch {
                    try {
                        requests.reviewRequest(request.id, status)
                        notice = "${request.name} 개설 요청을 처리했어요."
                    } catch (e: RequestFailure) {
                        notice = if (e.reason == RequestFailureReason.CONFLICT) "이미 처리된 요청이에요." else "처리하지 못했어요. 연결과 운영 권한을 확인해 주세요."
                    } finally { busy = false }
                }
            }) { Text(action) } })
    }
}
