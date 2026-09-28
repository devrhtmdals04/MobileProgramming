package com.example.study_helper.study

import androidx.compose.runtime.*
import com.example.study_helper.core.MarkdownNotes
import com.example.study_helper.core.QuizFiles
import com.example.study_helper.core.StudyService
import kotlinx.serialization.json.*
import com.example.study_helper.sync.*

/** Platform code supplies storage and system UI; learning data and grading stay in Kotlin. */
interface NotebookPlatform {
    /** JSON: {"files":[{"id":"UUID","content":"UTF-8 text"}]} or {"error":"message"}. */
    fun readFiles(kind: String): String
    fun writeFile(kind: String, id: String, content: String): String?
    fun newId(): String
    fun preference(key: String): String?
    fun setPreference(key: String, value: String)
    fun pickDocument(kind: String)
    fun exportDocument(filename: String, content: String)
    fun copyText(text: String)
    fun openLink(url: String)
    fun openRecordings() {}
    fun startGame()
}

/** Single main-thread owner, including calls from the native engine transport. */
class NotebookHost(private val platform: NotebookPlatform) {
    private val driveSync = (platform as? DrivePlatform)?.let { DriveSync(platform, it) }
    var sharing by mutableStateOf(false)
    val syncBusy: Boolean get() = driveSync?.state?.busy == true
    var notebook by mutableStateOf(StudyNotebook())
        private set
    var quizzes by mutableStateOf<List<StudyQuiz>>(emptyList())
        private set
    var recap by mutableStateOf<StudyRecap?>(null)
        private set
    var launching by mutableStateOf(false)
        private set
    private var importedQuizId by mutableStateOf<String?>(null)
    private var service = StudyService()
    private var gameDocument: JsonElement? = null
    private var gameTitle = ""
    private var sessionId = ""
    private var finalSummary: JsonObject? = null

    init { reload() }

    fun reload() { operation { load(platform.preference("selected_note")); loadRecap() } }

    private fun files(kind: String): List<Pair<String, String>> {
        val result = Json.parseToJsonElement(platform.readFiles(kind)).jsonObject
        result["error"]?.let { error(it.jsonPrimitive.content) }
        return result.getValue("files").jsonArray.map {
            val file = it.jsonObject
            file.getValue("id").jsonPrimitive.content to file.getValue("content").jsonPrimitive.content
        }
    }

    private fun write(kind: String, id: String, content: String) {
        platform.writeFile(kind, id, content)?.let { error(it) }
    }

    private fun load(selectedId: String?) {
        var documents = files("notes")
        if (documents.isEmpty()) {
            val id = platform.newId()
            val text = MarkdownNotes.write("Study Helper 사용법", """
                ## 나만의 학습 서재

                마크다운 파일을 가져오거나 **새 노트**에 공부한 내용을 적어 보세요.
                노트는 게임 없이도 읽고 편집할 수 있습니다.

                ## 문제로 복습하기

                1. 노트의 **더 보기 → 문제 생성 프롬프트**를 복사합니다.
                2. 원하는 AI에 붙여넣고 응답을 JSON 파일로 저장합니다.
                3. **문제 모음**에서 파일을 가져와 정답과 근거를 확인합니다.
                4. 준비되면 **이 문제로 게임 시작**을 선택합니다.

                > 노트와 문제는 이 기기에 저장됩니다. 앱이 AI에 자동 전송하지 않습니다.
            """.trimIndent())
            write("notes", id, text)
            documents = listOf(id to text)
        }
        val notes = documents.map { (id, content) ->
            val note = MarkdownNotes.read(content)
            StudyNoteSummary(id, note.title, note.body)
        }
        val selected = notes.firstOrNull { it.id == selectedId } ?: notes.first()
        val questionFiles = files("question-sets").map { (id, content) -> StudyQuiz(id, QuizFiles.read(content)) }
        notebook = StudyNotebook(selected.id, selected.title, selected.markdown, notes, ready = true, busy = false)
        quizzes = questionFiles
        platform.setPreference("selected_note", selected.id)
    }

    fun selectNote(id: String) { operation { load(id) } }

    fun saveNote(id: String?, title: String, body: String): String? = operation("노트를 기기에 저장했어요.") {
        val noteId = id ?: platform.newId()
        write("notes", noteId, MarkdownNotes.write(title, body))
        load(noteId)
    }

    fun importDocument(kind: String, filename: String, content: String): String? {
        val result = operation("파일을 기기에 가져왔어요.") {
            when (kind) {
                "notes" -> {
                    val note = MarkdownNotes.read(content, filename.substringBeforeLast('.', filename))
                    val id = platform.newId()
                    write("notes", id, MarkdownNotes.write(note.title, note.body))
                    load(id)
                }
                "question-sets" -> {
                    val quiz = QuizFiles.read(content)
                    val canonical = QuizFiles.write(quiz)
                    val existing = files(kind).firstOrNull { QuizFiles.write(QuizFiles.read(it.second)) == canonical }
                    val id = existing?.first ?: platform.newId()
                    if (existing == null) write(kind, id, canonical)
                    load(notebook.id)
                    importedQuizId = id
                }
                else -> error("지원하지 않는 문서 종류입니다.")
            }
        }
        return result
    }

    fun reportError(message: String) { notebook = notebook.copy(error = message, busy = false) }

    private fun operation(notice: String? = null, action: () -> Unit): String? {
        notebook = notebook.copy(busy = true, error = null, notice = null)
        return try {
            action()
            notebook = notebook.copy(busy = false, notice = notice)
            null
        } catch (error: Exception) {
            val message = error.message ?: "파일을 처리하지 못했어요. 다시 시도해 주세요."
            reportError(message)
            message
        }
    }

    fun startLearning(id: String) {
        if (launching || notebook.busy || !notebook.ready) return
        val error = operation {
            val file = files("question-sets").firstOrNull { it.first == id } ?: error("문제 파일을 찾지 못했어요.")
            val quiz = QuizFiles.read(file.second)
            gameDocument = Json.parseToJsonElement(QuizFiles.write(quiz))
            gameTitle = quiz.title
            sessionId = ""
            finalSummary = null
            service = StudyService()
        }
        if (error == null) {
            launching = true
            platform.startGame()
        }
    }

    /** The full question set never enters Godot's mailbox. Only public session data does. */
    fun exchange(request: String): String {
        val forwarded = runCatching {
            val json = Json.parseToJsonElement(request).jsonObject
            if (json["type"]?.jsonPrimitive?.content != "begin") request else JsonObject(json + (
                "body" to buildJsonObject {
                    put("questionSet", gameDocument ?: buildJsonObject {})
                    put("continuous", json["body"]?.jsonObject?.get("continuous")?.jsonPrimitive?.booleanOrNull == true)
                }
            )).toString()
        }.getOrDefault(request)
        val response = service.exchange(forwarded)
        val json = Json.parseToJsonElement(response).jsonObject
        when (json["type"]?.jsonPrimitive?.content) {
            "session" -> { sessionId = json.getValue("body").jsonObject.getValue("sessionId").jsonPrimitive.content; finalSummary = null }
            "summary" -> finalSummary = json.getValue("body").jsonObject
        }
        return response
    }

    fun finishGame() {
        if (sessionId.isNotEmpty() && finalSummary == null) exchange(buildJsonObject {
            put("version", 1); put("requestId", "native-end-$sessionId"); put("type", "end")
            putJsonObject("body") { put("sessionId", sessionId) }
        }.toString())
        finalSummary?.let { summary ->
            val record = buildJsonObject { put("title", gameTitle); put("summary", summary) }.toString()
            platform.setPreference("recent_result", record)
            loadRecap()
        }
        launching = false
        gameDocument = null
    }

    fun gameFailed(message: String) { finishGame(); reportError(message) }

    private fun loadRecap() {
        recap = runCatching {
            val record = platform.preference("recent_result") ?: return@runCatching null
            val json = Json.parseToJsonElement(record).jsonObject
            val summary = json.getValue("summary").jsonObject
            StudyRecap(json.getValue("title").jsonPrimitive.content,
                summary.getValue("answeredCount").jsonPrimitive.int, summary.getValue("correctCount").jsonPrimitive.int,
                summary.getValue("questionCount").jsonPrimitive.int,
                summary.getValue("answers").jsonArray.map { it.jsonObject }.filter { !it.getValue("correct").jsonPrimitive.boolean }
                    .map { it.getValue("explanation").jsonPrimitive.content },
                reviewed = summary["reviewedCount"]?.jsonPrimitive?.intOrNull ?: 0,
                continuous = summary["continuous"]?.jsonPrimitive?.booleanOrNull == true)
        }.getOrNull()
    }

    @Composable
    fun Content() {
        if (sharing && driveSync != null) {
            DriveSyncScreen(driveSync, onClose = { sharing = false }, onChanged = ::reload)
            return
        }
        StudyHome(notebook, quizzes, importedQuizId, recap, launching,
            validateDocument = { title, body -> runCatching { MarkdownNotes.validate(title, body) }.exceptionOrNull()?.message },
            onSaveNotes = { id, title, body -> saveNote(id, title, body) },
            onImport = { platform.pickDocument("notes") },
            onExport = { platform.exportDocument(notebook.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md", MarkdownNotes.write(notebook.title, notebook.markdown)) },
            onSelectNote = ::selectNote,
            onImportQuiz = { importedQuizId = null; platform.pickDocument("question-sets") },
            onCopyPrompt = {
                platform.copyText(QuizFiles.prompt(notebook.id.orEmpty(), notebook.title, notebook.markdown))
                notebook = notebook.copy(notice = "노트 내용과 문제 생성 프롬프트를 복사했어요.")
            },
            onRetry = ::reload, onStartLearning = ::startLearning, onOpenLink = platform::openLink, onBackAction = {}, onOpenRecordings = platform::openRecordings,
            onOpenSync = driveSync?.let { { sharing = true } },
        )
    }
}
