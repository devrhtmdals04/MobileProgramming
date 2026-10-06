package com.example.study_helper.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.study_helper.core.QuizFiles
import kotlinx.coroutines.launch

@Composable
fun PdfStudyScreen(host: PdfStudyHost, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(host.key.isBlank()) }
    var questions by remember { mutableStateOf(false) }
    val locked = host.busy || host.saving || host.picking
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onClose() }, enabled = !locked) { Text("‹ 서재") }
                    TextButton(onClick = { settings = !settings }, enabled = !locked) { Text("API 설정") }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PDF 학습 노트", style = MaterialTheme.typography.headlineSmall)
                    if (settings) {
                        OutlinedTextField(host.key, { host.key = it }, label = { Text("OpenAI API 키") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
                        Text("키는 앱 실행 중에만 보관합니다. 요약·문제 생성 시 선택한 PDF가 OpenAI로 전송됩니다.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(host.model, { host.model = it }, label = { Text("모델") }, singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { host.pick() }, enabled = !locked) { Text(if (host.picking) "읽는 중…" else "PDF 선택") }
                        Text(host.filename.ifEmpty { "12MB 이하 · 표와 그림 포함" }, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (host.ready) {
                        OutlinedTextField(host.title, { host.title = it.take(60) }, label = { Text("노트 제목") }, singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { questions = false; edit = false; host.summarize() }, enabled = !locked && host.key.isNotBlank()) { Text(if (host.summaryComplete) "요약 다시 만들기" else "요약 만들기") }
                            if (host.busy) OutlinedButton(onClick = host::cancel) { Text("중지") }
                            if (host.summaryComplete) TextButton(onClick = { scope.launch { host.saveSummary() } }, enabled = !locked) { Text("노트 저장") }
                        }
                    }
                    if (host.status.isNotEmpty()) Text(host.status, style = MaterialTheme.typography.bodySmall)
                    host.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (host.summaryComplete) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { questions = false; edit = false }) { Text("요약 읽기") }
                            TextButton(onClick = { questions = false; edit = true }, enabled = !locked) { Text("수정") }
                            TextButton(onClick = { questions = true; edit = false }, enabled = !locked) { Text("문제 만들기") }
                        }
                    }
                }
                if (locked) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider(Modifier.padding(top = 8.dp))
                if (questions && host.summaryComplete) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("PDF 원본으로 문제 만들기", style = MaterialTheme.typography.titleLarge)
                        Text("현재 요약을 저장한 뒤, 원본 PDF 전체에서 출제합니다.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 15, 20).forEach { n ->
                            FilterChip(selected = host.count == n, onClick = { host.count = n }, label = { Text("${n}개") }, enabled = !locked)
                        } }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("쉬움", "보통", "어려움").forEach { level ->
                            FilterChip(selected = host.difficulty == level, onClick = { host.difficulty = level }, label = { Text(level) }, enabled = !locked)
                        } }
                        Row { Checkbox(host.written, { host.written = it }, enabled = !locked); Text("서술형 포함", Modifier.padding(top = 12.dp)) }
                        Button(onClick = { scope.launch { host.generateQuiz() } }, enabled = !locked && host.key.isNotBlank()) { Text("문제 생성") }
                        host.quizJson?.let { json ->
                            val quiz = remember(json) { QuizFiles.read(json) }
                            quiz.questions.forEachIndexed { index, q ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${index + 1}. ${q.prompt}", style = MaterialTheme.typography.titleMedium)
                                    q.choices.forEachIndexed { i, choice -> Text("${i + 1}. $choice") }
                                    Text("정답: ${q.modelAnswer ?: q.choices[q.correctIndex]}")
                                    Text(q.explanation)
                                    Text("근거: ${q.sourceQuote}", style = MaterialTheme.typography.bodySmall)
                                } }
                            }
                            Button(onClick = { scope.launch { host.saveQuestions() } }, enabled = !locked && !host.quizSaved) { Text(if (host.quizSaved) "저장 완료" else "문제 모음에 저장") }
                        }
                    }
                } else if (edit && host.summaryComplete) {
                    OutlinedTextField(host.summary, { host.summary = it }, enabled = !locked, modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), label = { Text("요약 마크다운") })
                } else if (host.summary.isNotEmpty()) {
                    Box(Modifier.weight(1f)) { NoteReader(host.title, host.summary, {}) }
                } else {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("강의자료를 읽기 좋은 노트로", style = MaterialTheme.typography.titleLarge)
                        Text("한 줄 요약 → 핵심 개념 → 주제별 정리 → 시험 포인트 → 용어 표")
                        Text("요약이 생성되는 대로 여기에 표시됩니다. 완성된 노트는 서재에서 읽고 편집할 수 있어요.")
                    }
                }
            }
        }
    }
}
