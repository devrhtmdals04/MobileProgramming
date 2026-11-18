package com.mope.app.ui.screens.request

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mope.app.data.model.RequestStatus
import com.mope.app.ui.components.LoadState
import com.mope.app.ui.components.Page

@Composable
fun ClubRequestScreen(
    state: ClubRequestState,
    onName: (String) -> Unit,
    onPurpose: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetryHistory: () -> Unit,
    onLogin: () -> Unit,
) {
    Page("동아리 개설 요청") {
        Text("그루 운영자에게 새 동아리 등록을 요청해요. 학교·동아리연합회의 공식 등록 신청은 별도로 진행해야 합니다.")
        if (state.userId == null) {
            Text("로그인하면 개설 요청을 제출하고 내 요청 내역을 확인할 수 있어요.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onLogin) { Text("로그인하기") }
        } else {
            OutlinedTextField(value = state.name, onValueChange = onName, label = { Text("동아리 이름") },
                supportingText = { Text("${state.name.length}/50") }, singleLine = true,
                enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.purpose, onValueChange = onPurpose, label = { Text("개설 목적 및 활동 계획") },
                supportingText = { Text("${state.purpose.length}/2000") }, minLines = 3,
                enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.submitted) Text("개설 요청이 접수됐어요. 아래에서 처리 상태를 확인해 주세요.")
            Button(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isSubmitting) "제출 중…" else "개설 요청 제출")
            }
            Text("내 요청 내역", style = MaterialTheme.typography.titleLarge)
            when (val history = state.history) {
                LoadState.Loading -> CircularProgressIndicator()
                LoadState.Empty -> Text("아직 제출한 개설 요청이 없어요.")
                is LoadState.Error -> {
                    Text(history.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetryHistory) { Text("내역 다시 불러오기") }
                }
                is LoadState.Content -> history.value.forEach { request ->
                    Text(request.name, style = MaterialTheme.typography.titleMedium)
                    Text(when (request.status) {
                        RequestStatus.PENDING -> "검토 대기"
                        RequestStatus.APPROVED -> "승인됨"
                        RequestStatus.REJECTED -> "반려됨"
                    })
                    Text(request.purpose)
                    HorizontalDivider()
                }
            }
        }
    }
}
