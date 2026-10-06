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
import com.example.study_helper.core.QuestionType
import com.example.study_helper.core.QuizDocument
import com.example.study_helper.core.NumericQuestions

/** Notes are readable on their own; imported question files are a separate study collection. */
data class StudyRecap(val title: String, val answered: Int, val correct: Int, val total: Int, val review: List<String>, val reviewed: Int = 0, val continuous: Boolean = false)
data class StudyNoteSummary(val id: String, val title: String, val markdown: String = "", val path: String = "")
data class StudyQuiz(val id: String, val document: QuizDocument, val path: String = "")
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
    onOpenSync: (() -> Unit)? = null,
    onOpenPdf: (() -> Unit)? = null,
    varyNumbers: Boolean = true,
    onVaryNumbersChange: (Boolean) -> Unit = {},
) {
    var libraryFolder by rememberSaveable { mutableStateOf("") }
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
        if (importedQuizId != null) { selectedQuiz = importedQuizId; reading = false }
    }
    SideEffect {
        onBackAction(when {
            reading -> { { reading = false } }
            selectedQuiz != null -> { { selectedQuiz = null } }
            libraryFolder.isNotEmpty() -> { { libraryFolder = libraryFolder.substringBeforeLast('/', "") } }
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
                    quizzes.firstOrNull { it.document.sourceNoteId == notebook.id }?.let { related ->
                        TextButton(onClick = { selectedQuiz = related.id; reading = false }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth()) { Text("이 수업 복습하기 · ${related.document.questions.size}문항") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        readerStates.SaveableStateProvider(notebook.id.orEmpty()) {
                            NoteReader(notebook.title, notebook.markdown, onOpenLink)
                        }
                    }
                } else if (quiz != null) {
                    QuizDetail(quiz, launching, enabled, Modifier.weight(1f), varyNumbers,onVaryNumbersChange,onBack = { selectedQuiz = null },
                        onStart = { onStartLearning(quiz.id) },
                        onSource = (notebook.notes.firstOrNull { it.id == quiz.document.sourceNoteId }
                            ?: notebook.notes.singleOrNull { it.title == quiz.document.sourceNoteTitle })?.let { source ->
                            { onSelectNote(source.id); selectedQuiz = null; reading = true }
                        })
                } else {
                    Box(Modifier.weight(1f)) {
                        NoteLibrary(notebook, quizzes, recap, enabled, onImport, newNote,
                            folder = libraryFolder, onFolder = { libraryFolder = it },
                            onImportQuiz = onImportQuiz, onQuiz = { selectedQuiz = it },
                            onRead = { onSelectNote(it); reading = true },
                            onOpenPdf = onOpenPdf, onOpenSync = onOpenSync, onOpenRecordings = onOpenRecordings)
                    }


                }
            }
        }
        if (editing) NotesEditor(draftTitle, draftNotes, validateDocument,
            onClose = { editing = false }, onSave = { title, notes ->
                val error = onSaveNotes(draftId, title.trim(), notes)
                if (error == null) { editing = false; reading = true }
                error
            })
        if (promptHelp) PromptHelp(notebook.title, onClose = { promptHelp = false }, onCopy = onCopyPrompt,
            onImport = { promptHelp = false; onImportQuiz() })
    }
}

@Composable
private fun NoteLibrary(notebook: StudyNotebook, quizzes: List<StudyQuiz>, recap: StudyRecap?, enabled: Boolean,
    onImport: () -> Unit, onNew: () -> Unit, folder: String, onFolder: (String) -> Unit,
    onImportQuiz: () -> Unit, onQuiz: (String) -> Unit, onRead: (String) -> Unit,
    onOpenPdf: (() -> Unit)?, onOpenSync: (() -> Unit)?, onOpenRecordings: (() -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val materials = remember(notebook.notes, quizzes) { studyLibrary(notebook.notes, quizzes) }
    val filtered = materials.filter {
        (folder.isEmpty() || it.subject == folder) &&
            listOf(it.title, it.subject, it.unit).any { text -> text.contains(query.trim(), true) }
    }
    val subjects = filtered.map { it.subject }.distinct().sortedWith(compareBy({ it == "개인 노트" }, { it }))
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (folder.isNotEmpty()) TextButton(onClick = { onFolder(""); query = "" }) { Text("‹ 과목") }
            Text(if (folder.isEmpty()) "내 과목" else folder, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton(onClick = { menu = true }) { Text("메뉴") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("검색") }, onClick = { menu = false; showSearch = !showSearch; query = "" })
                    onOpenSync?.let { action -> DropdownMenuItem(text = { Text("동기화 · 원본 파일") }, enabled = enabled, onClick = { menu = false; action() }) }
                    DropdownMenuItem(text = { Text("새 노트") }, enabled = enabled, onClick = { menu = false; onNew() })
                    DropdownMenuItem(text = { Text("노트 가져오기") }, enabled = enabled, onClick = { menu = false; onImport() })
                    DropdownMenuItem(text = { Text("문제 가져오기") }, enabled = enabled, onClick = { menu = false; onImportQuiz() })
                    onOpenPdf?.let { action -> DropdownMenuItem(text = { Text("PDF 요약 · 문제 생성") }, enabled = enabled, onClick = { menu = false; action() }) }
                    onOpenRecordings?.let { action -> DropdownMenuItem(text = { Text("강의 녹음") }, enabled = enabled, onClick = { menu = false; action() }) }
                }
            }
        }
        if (showSearch) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true, placeholder = { Text("과목·제목 검색") }, shape = RoundedCornerShape(12.dp))
        // Reset scrolling when changing courses; each course retains its own position on return from a reader.
        key(folder, query) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (folder.isEmpty() && query.isBlank()) {
                    items(subjects, key = { "subject:$it" }) { subject ->
                        Surface(onClick = { onFolder(subject) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(subject, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    val groups = filtered.groupBy { it.subject to it.unit }
                    groups.forEach { (location, rows) ->
                        item(key = "unit:${location.first}/${location.second}") {
                            Text(if (folder.isEmpty()) "${location.first} · ${location.second}" else location.second,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
                        }
                        items(rows, key = { it.key }) { material ->
                            Surface(onClick = { if (material.quiz) onQuiz(material.id) else onRead(material.id) },
                                enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(if (material.quiz) "문제 · ${material.questions}문항" else if (material.summary) "요약" else "노트",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(material.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) item {
                    Text(if (query.isNotBlank()) "검색 결과가 없어요." else "메뉴에서 동기화하거나 노트를 추가해 주세요.",
                        modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (folder.isEmpty() && query.isBlank() && recap != null) item {
                    Text("최근 복습 · ${recap.title}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                }
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
private fun QuizDetail(quiz: StudyQuiz, launching: Boolean, enabled: Boolean, modifier: Modifier,
    varyNumbers:Boolean,onVaryNumbersChange:(Boolean)->Unit,onBack: () -> Unit, onStart: () -> Unit, onSource: (() -> Unit)?) {
    var reveal by rememberSaveable(quiz.id) { mutableStateOf(false) }
    var examples by remember(quiz.id, quiz.document) {mutableStateOf(quiz.document.questions)}
    val playableCount = quiz.document.questions.count { it.type != QuestionType.WRITTEN }
    val variantCount=quiz.document.questions.count {it.numberVariant!=null}
    val displayed=if(varyNumbers) examples else quiz.document.questions
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  서재") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "정답 가리기" else "정답·해설 보기") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item {
                Text(quiz.subjectName(), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(quiz.document.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 35.sp)
                Text("${quiz.document.questions.size}문제  ·  ${quiz.document.typeSummary()}", Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                Text("출처 노트: ${quiz.document.sourceNoteTitle}", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (onSource != null) TextButton(onClick = onSource) { Text("원본 노트 읽기 →") }
                Text("생성 당시 노트를 기준으로 만든 문제입니다. 학습 전에 내용과 정답을 확인해 주세요.",
                    Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(variantCount>0) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Switch(checked=varyNumbers,onCheckedChange=onVaryNumbersChange,enabled=enabled)
                        Text("계산 $variantCount 문항의 숫자 바꾸기",Modifier.padding(start=10.dp))
                    }
                    Text("게임에서 다시 출제할 때 숫자·정답·해설을 함께 바꿉니다. 오답을 다시 풀 때는 같은 숫자를 유지합니다.",fontSize=12.sp)
                    TextButton(onClick={examples=examples.map {NumericQuestions.generate(it,previousPrompt=it.prompt)};reveal=false},enabled=enabled && varyNumbers) {Text("숫자 바꿔 미리보기")}
                    Text("아래는 예시이며 게임에서는 새 값을 사용합니다.",fontSize=12.sp)
                }
            }
            items(quiz.document.questions.size) { index ->
                val q = displayed[index]
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${q.type.label} · ${(index + 1).toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(q.prompt, fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
                    if (q.type == QuestionType.WRITTEN) {
                        var draft by rememberSaveable(quiz.id, q.id, q.prompt) { mutableStateOf("") }
                        OutlinedTextField(value = draft, onValueChange = { draft = it.take(5000) },
                            label = { Text("내 답안") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        Text("연습용 답안입니다. 별도로 저장되지 않으며 자동 채점하지 않습니다.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    q.choices.forEachIndexed { choice, text ->
                        Surface(color = if (reveal && choice == q.correctIndex) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Text("${choice + 1}.  $text", Modifier.fillMaxWidth().padding(14.dp), fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
                    if (reveal) {
                        if (q.type == QuestionType.WRITTEN) {
                            Text("모범 답안\n${q.modelAnswer}", fontSize = 14.sp, lineHeight = 23.sp)
                            Text("채점 기준\n" + q.rubric.joinToString("\n") { "• $it" }, fontSize = 14.sp, lineHeight = 23.sp)
                        }
                        Text(if (q.type == QuestionType.WRITTEN) q.explanation else "정답 ${q.correctIndex + 1}  ·  ${q.explanation}", fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.primary)
                        Text("노트 근거\n${q.sourceQuote}", fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("관문 복습 · 개념·계산 ${playableCount}문제 반복 / 서술형은 이 화면에서 답안 비교", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onStart, enabled = enabled && playableCount > 0, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (launching) "복습을 준비하고 있어요…" else if (playableCount == 0) "서술형은 위에서 연습해 주세요" else "개념·계산 문제로 게임 시작", Modifier.padding(5.dp), fontWeight = FontWeight.SemiBold)
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

private fun QuizDocument.typeSummary(): String = QuestionType.entries.mapNotNull { type ->
    questions.count { it.type == type }.takeIf { it > 0 }?.let { "${type.label} $it" }
}.joinToString(" · ")
