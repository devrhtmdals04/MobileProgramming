package com.mope.app.ui.screens.request

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.ui.components.Page
import com.mope.app.ui.theme.MopeTheme

@Composable
fun RequestScreen(clubId: String? = null, onBack: (() -> Unit)? = null) {
    var name by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }
    Page(if (clubId == null) "동아리 개설 요청" else "동아리 가입 신청") {
        if (onBack != null) TextButton(onClick = onBack) { Text("뒤로") }
        if (clubId == null) OutlinedTextField(name, { name = it }, label = { Text("동아리 이름") })
        else Text("신청 대상: $clubId")
        OutlinedTextField(purpose, { purpose = it }, label = { Text(if (clubId == null) "개설 목적 및 활동 계획" else "자기소개") }, minLines = 3)
        Text("작성 화면 미리보기입니다. 입력 내용은 서버에 저장되지 않습니다.")
        Button(onClick = {}, enabled = false) { Text("제출 · 연동 예정") }
        if (clubId == null) Text("내 요청 내역 · 저장 기능 연결 후 표시됩니다.")
    }
}
@Preview(showBackground = true)
@Composable
private fun RequestPreview() = MopeTheme { RequestScreen() }
