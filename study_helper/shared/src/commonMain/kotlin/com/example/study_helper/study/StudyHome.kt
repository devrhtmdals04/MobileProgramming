package com.example.study_helper.study

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.study_helper.core.QuizDocument

/** Notes are readable on their own; imported question files are a separate study collection. */
data class StudyRecap(val title: String, val answered: Int, val correct: Int, val total: Int, val review: List<String>, val reviewed: Int = 0, val continuous: Boolean = false)
data class StudyNoteSummary(val id: String, val title: String, val markdown: String = "")
data class StudyQuiz(val id: String, val document: QuizDocument)
data class StudyNotebook(
    val id: String? = null, val title: String = "", val markdown: String = "",
    val notes: List<StudyNoteSummary> = emptyList(), val ready: Boolean = false, val busy: Boolean = true,
    val error: String? = null, val notice: String? = null,
)

@Composable
fun StudyHome(
    notebook: StudyNotebook,
    quizzes: List<StudyQuiz>,
    importedQuizId: String?,
    recap: StudyRecap?,
    launching: Boolean,
    validateDocument: (String, String) -> String?,
    onSaveNotes: suspend (String?, String, String) -> String?,
    onImport: () -> Unit, onExport: () -> Unit, onSelectNote: (String) -> Unit,
    onImportQuiz: () -> Unit, onCopyPrompt: () -> Unit, onRetry: () -> Unit,
    onStartLearning: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onBackAction: ((() -> Unit)?) -> Unit,
    onOpenRecordings: (() -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf("notes") }
    var reading by rememberSaveable { mutableStateOf(false) }
    var selectedQuiz by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var promptHelp by rememberSaveable { mutableStateOf(false) }
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftNotes by rememberSaveable { mutableStateOf("") }
    val readerStates = rememberSaveableStateHolder()
    val enabled = notebook.ready && !notebook.busy && !launching
    val quiz = quizzes.firstOrNull { it.id == selectedQuiz }
    LaunchedEffect(importedQuizId) {
        if (importedQuizId != null) { tab = "quizzes"; selectedQuiz = importedQuizId; reading = false }
    }
    SideEffect {
        onBackAction(when {
            reading -> { { reading = false } }
            selectedQuiz != null -> { { selectedQuiz = null } }
            tab != "notes" -> { { tab = "notes" } }
            else -> null
        })
    }
    val edit: () -> Unit = {
        draftId = notebook.id; draftTitle = notebook.title; draftNotes = notebook.markdown; editing = true
    }
    val newNote: () -> Unit = {
        draftId = null; draftTitle = "새 노트"; draftNotes = ""; editing = true
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFC1ABFF), onPrimary = Color(0xFF2D204A), background = Color(0xFF1B1A20),
        surface = Color(0xFF232229), surfaceVariant = Color(0xFF2E2C36), onSurface = Color(0xFFE8E5EC),
        onBackground = Color(0xFFE8E5EC), onSurfaceVariant = Color(0xFFA9A4B4), outlineVariant = Color(0xFF3C3946),
    ) else lightColorScheme(
        primary = Color(0xFF7156B5), onPrimary = Color.White, background = Color(0xFFFAF9FC),
        surface = Color.White, surfaceVariant = Color(0xFFF0EDF6), onSurface = Color(0xFF292630),
        onBackground = Color(0xFF292630), onSurfaceVariant = Color(0xFF77717F), outlineVariant = Color(0xFFE7E3EC),
    )
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (notebook.busy || launching) LinearProgressIndicator(Modifier.fillMaxWidth())
                notebook.error?.let { Banner(it, error = true) }
                // Reader notices appear inline without covering the document or changing scroll position.
                notebook.notice?.let { Banner(it) }
                if (!notebook.ready) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (notebook.busy) Text("서재를 열고 있어요", color = colors.onSurfaceVariant)
                        else TextButton(onClick = onRetry) { Text("서재 다시 열기") }
                    }
                } else if (reading) {
                    ReaderToolbar(onBack = { reading = false }, onEdit = edit, onExport = onExport,
                        onPrompt = { promptHelp = true }, enabled = enabled)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        readerStates.SaveableStateProvider(notebook.id.orEmpty()) {
                            NoteReader(notebook.title, notebook.markdown, onOpenLink)
                        }
                    }
                } else if (quiz != null && tab == "quizzes") {
                    QuizDetail(quiz, launching, enabled, Modifier.weight(1f), onBack = { selectedQuiz = null },
                        onStart = { onStartLearning(quiz.id) },
                        onSource = notebook.notes.firstOrNull { it.id == quiz.document.sourceNoteId }?.let { source ->
                            { onSelectNote(source.id); selectedQuiz = null; tab = "notes"; reading = true }
                        })
                } else {
                    Box(Modifier.weight(1f)) {
                        if (tab == "notes") NoteLibrary(notebook, enabled, onImport, newNote) {
                            onSelectNote(it); reading = true
                        } else QuizLibrary(quizzes, recap, enabled, onImportQuiz) { selectedQuiz = it }
                    }
                    onOpenRecordings?.let { open ->
                        TextButton(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("강의 녹음 · 녹음 목록") }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("notes" to "노트 서재", "quizzes" to "문제 모음").forEach { (key, label) ->
                            TextButton(onClick = { tab = key }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColors(contentColor = if (tab == key) colors.primary else colors.onSurfaceVariant)) {
                                DocumentGlyph(key, Modifier.size(21.dp))
                                Spacer(Modifier.width(9.dp))
                                Text(label, fontWeight = if (tab == key) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        if (editing) NotesEditor(draftTitle, draftNotes, { draftTitle = it }, { draftNotes = it }, validateDocument,
            onClose = { editing = false }, onSave = {
                val error = onSaveNotes(draftId, draftTitle.trim(), draftNotes)
                if (error == null) { editing = false; tab = "notes"; reading = true }
                error
            })
        if (promptHelp) PromptHelp(notebook.title, onClose = { promptHelp = false }, onCopy = onCopyPrompt,
            onImport = { promptHelp = false; onImportQuiz() })
    }
}

@Composable
private fun NoteLibrary(notebook: StudyNotebook, enabled: Boolean, onImport: () -> Unit, onNew: () -> Unit, onRead: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, notebook.notes) {
        notebook.notes.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.markdown.contains(query.trim(), true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("STUDY HELPER", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나의 서재", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${notebook.notes.size}개의 노트", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("읽고, 정리하고, 오래 기억하기", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("제목이나 내용 검색", fontSize = 14.sp) }, shape = RoundedCornerShape(12.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("노트 가져오기") }
            Button(onClick = onNew, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("＋ 새 노트") }
        }
        Text(if (query.isBlank()) "모든 노트 · 최근 수정순" else "검색 결과 ${filtered.size}개",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (filtered.isEmpty()) item { Text("검색 결과가 없어요.", Modifier.padding(vertical = 30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(filtered, key = { it.id }) { note ->
                Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { onRead(note.id) }.padding(vertical = 19.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            DocumentGlyph("notes", Modifier.padding(12.dp).size(22.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(note.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val excerpt = remember(note.markdown) {
                                note.markdown.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ') }
                                    .firstOrNull { it.isNotBlank() }.orEmpty()
                                    .replace(Regex("!?\\[([^]]+)\\]\\([^)]*\\)"), "$1")
                                    .replace("**", "").replace("`", "").take(120)
                            }
                            Text(excerpt.ifBlank { "첫 문장을 적어 보세요" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Markdown  ·  ${note.markdown.length}자", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 11.sp)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ReaderToolbar(onBack: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onPrompt: () -> Unit, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  서재", fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit, enabled = enabled) { Text("편집") }
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled) { Text("더 보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("문제 생성 프롬프트") }, onClick = { menu = false; onPrompt() })
                    DropdownMenuItem(text = { Text("마크다운 내보내기") }, onClick = { menu = false; onExport() })
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun QuizLibrary(quizzes: List<StudyQuiz>, recap: StudyRecap?, enabled: Boolean, onImport: () -> Unit, onSelect: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("LEARN WITH YOUR NOTES", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("문제 모음", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("노트에서 만든 문제를 모아두고, 원하는 방식으로 복습하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
        }
        item { Button(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("문제 파일 가져오기 (.json)", Modifier.padding(6.dp)) } }
        if (quizzes.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("첫 문제 묶음을 만들어 보세요", fontWeight = FontWeight.SemiBold)
                    Text("1. 노트의 ‘더 보기’에서 프롬프트 복사\n2. 원하는 AI에 붙여넣어 문제 파일 생성\n3. JSON 파일을 가져와 내용 확인",
                        fontSize = 14.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(quizzes, key = { it.id }) { quiz ->
            Surface(onClick = { onSelect(quiz.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${quiz.document.questions.size}문제  ·  객관식", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text(quiz.document.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(quiz.document.sourceNoteTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text("문제 확인  →", fontSize = 13.sp, modifier = Modifier.align(Alignment.End))
                }
            }
        }
        if (recap != null) item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(18.dp))
            Text("최근 게임 복습", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(recap.title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text("${recap.answered}문제 풀이 · 첫 답 정답 ${recap.correct}개", Modifier.padding(vertical = 8.dp), fontSize = 18.sp)
            Text("해설 후 다시 확인 ${recap.reviewed}개", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!recap.continuous && recap.answered < recap.total) Text("아직 풀지 않은 문제 ${recap.total - recap.answered}개", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            recap.review.forEach { Text(it, Modifier.padding(top = 10.dp), fontSize = 14.sp, lineHeight = 23.sp) }
        }
    }
}

@Composable
private fun QuizDetail(quiz: StudyQuiz, launching: Boolean, enabled: Boolean, modifier: Modifier, onBack: () -> Unit, onStart: () -> Unit, onSource: (() -> Unit)?) {
    var reveal by rememberSaveable(quiz.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  문제 모음") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "정답 가리기" else "정답·해설 보기") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item {
                Text(quiz.document.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 35.sp)
                Text("${quiz.document.questions.size}문제  ·  형식 확인 완료", Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                Text("출처 노트: ${quiz.document.sourceNoteTitle}", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (onSource != null) TextButton(onClick = onSource) { Text("원본 노트 읽기 →") }
                Text("생성 당시 노트를 기준으로 만든 문제입니다. 학습 전에 내용과 정답을 확인해 주세요.",
                    Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(quiz.document.questions.size) { index ->
                val q = quiz.document.questions[index]
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("QUESTION ${(index + 1).toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(q.prompt, fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
                    q.choices.forEachIndexed { choice, text ->
                        Surface(color = if (reveal && choice == q.correctIndex) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Text("${choice + 1}.  $text", Modifier.fillMaxWidth().padding(14.dp), fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
                    if (reveal) {
                        Text("정답 ${q.correctIndex + 1}  ·  ${q.explanation}", fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.primary)
                        Text("노트 근거\n${q.sourceQuote}", fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("관문 복습 · 전체 문제를 섞어 계속 반복", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (launching) "복습을 준비하고 있어요…" else "이 문제로 게임 시작", Modifier.padding(5.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PromptHelp(title: String, onClose: () -> Unit, onCopy: () -> Unit, onImport: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                TextButton(onClick = onClose) { Text("‹  노트로 돌아가기") }
                Text("노트에서\n문제 만들기", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                Text(title, color = MaterialTheme.colorScheme.primary)
                Text("노트 내용과 문제 파일 형식을 담은 프롬프트를 준비했어요.", fontSize = 16.sp, lineHeight = 26.sp)
                Text("01  프롬프트 복사\n선택한 노트 전체가 함께 복사됩니다.\n\n02  원하는 AI에 붙여넣기\n생성된 결과를 .json 파일로 저장하세요.\n\n03  문제 파일 가져오기\n문항·보기·해설을 확인한 뒤 복습을 시작하세요.",
                    fontSize = 15.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onCopy(); copied = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (copied) "복사 완료 · 다시 복사" else "문제 생성 프롬프트 복사", Modifier.padding(8.dp))
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("만든 문제 파일 가져오기", Modifier.padding(8.dp)) }
                Text("앱은 AI에 자동으로 전송하지 않습니다. 붙여넣은 노트는 사용자가 선택한 AI 서비스에 전달됩니다.",
                    fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Banner(text: String, error: Boolean = false) {
    Text(text, Modifier.fillMaxWidth().background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 12.sp, lineHeight = 18.sp,
        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DocumentGlyph(kind: String, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = Stroke(width = size.width * .065f)
        drawRoundRect(color, Offset(size.width * .15f, size.height * .08f), Size(size.width * .7f, size.height * .84f), CornerRadius(size.width * .08f), style = stroke)
        for (line in 0..2) drawLine(color, Offset(size.width * .3f, size.height * (.34f + line * .17f)), Offset(size.width * (if (line == 2) .58f else .7f), size.height * (.34f + line * .17f)), strokeWidth = stroke.width)
        if (kind == "quizzes") drawCircle(color, size.width * .11f, Offset(size.width * .83f, size.height * .15f))
    }
}
