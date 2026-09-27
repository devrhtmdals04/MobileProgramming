package com.example.study_helper.jaderun

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import com.example.study_helper.core.MarkdownNotes
import com.example.study_helper.core.StudyService
import com.example.study_helper.core.QuizFiles
import com.example.study_helper.study.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Owns local documents and file pickers. Question files remain in Kotlin; Godot receives public question data. */
class StudyHomeActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("study_home", MODE_PRIVATE) }
    private val store by lazy { MarkdownNoteStore(this) }
    private val quizStore by lazy { QuizFileStore(this) }
    private var quizzes by mutableStateOf<List<StudyQuiz>>(emptyList())
    private var importedQuizId by mutableStateOf<String?>(null)
    // A rotation may create the next Activity while the previous IO write is completing.
    private companion object { val documentLock = Mutex() }
    private var notebook by mutableStateOf(StudyNotebook())
    private var recap by mutableStateOf<StudyRecap?>(null)
    private var launching by mutableStateOf(false)
    private var exportNoteId: String? = null
    private val navigationBack = object : OnBackPressedCallback(false) {
        var action: (() -> Unit)? = null
        override fun handleOnBackPressed() { action?.invoke() }
    }

    private val gameLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        launching = false
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(StudyGameContract.RESULT)?.let { record ->
                parseRecap(record)?.let {
                    preferences.edit().putString("recent_result", record).apply()
                    recap = it
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            documentOperation("마크다운을 새 노트로 가져왔어요.") {
                val (filename, text) = readDocument(uri)
                val imported = MarkdownNotes.read(text, filename.substringBeforeLast('.', filename))
                val saved = store.save(null, imported.title, imported.body)
                loadNotebook(saved.id)
            }
        }
    }

    private val quizImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            var importedId: String? = null
            val error = documentOperation("문제 파일의 형식을 확인하고 저장했어요.") {
                val (_, text) = readDocument(uri)
                importedId = quizStore.import(text).id
                loadNotebook(preferences.getString("selected_note", null))
            }
            if (error == null) {
                refreshQuizzes()
                importedQuizId = importedId
            }
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val id = exportNoteId
        exportNoteId = null
        if (uri != null && id != null) lifecycleScope.launch {
            documentOperation("마크다운 파일을 내보냈어요.") {
                val note = store.read(id)
                val content = MarkdownNotes.write(note.title, note.body)
                (contentResolver.openOutputStream(uri, "wt") ?: throw IOException("파일을 열지 못했어요."))
                    .use { it.write(content.toByteArray(Charsets.UTF_8)) }
                loadNotebook(preferences.getString("selected_note", null))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, navigationBack)
        recap = preferences.getString("recent_result", null)?.let(::parseRecap)
        launching = savedInstanceState?.getBoolean("launching") ?: false
        exportNoteId = savedInstanceState?.getString("export_note_id")
        loadInitialNotes()
        setContent {
            StudyHome(
                notebook = notebook, recap = recap, launching = launching,
                quizzes = quizzes, importedQuizId = importedQuizId,
                onBackAction = { action -> navigationBack.action = action; navigationBack.isEnabled = action != null },
                onOpenLink = { link ->
                    val uri = link.toUri()
                    if (uri.scheme in listOf("https", "http", "mailto")) launchPicker { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    else notebook = notebook.copy(notice = "첨부 파일과 노트 간 링크 연결은 아직 지원하지 않아요.")
                },
                onImportQuiz = {
                    importedQuizId = null
                    launchPicker { quizImportLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                },
                onCopyPrompt = {
                    val prompt = QuizFiles.prompt(notebook.id.orEmpty(), notebook.title, notebook.markdown)
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("문제 생성 프롬프트", prompt))
                    notebook = notebook.copy(notice = "노트 내용과 문제 생성 프롬프트를 복사했어요.", error = null)
                },
                validateDocument = { title, body -> runCatching { MarkdownNotes.validate(title, body) }.exceptionOrNull()?.message },
                onSaveNotes = ::saveNote,
                onImport = { launchPicker { importLauncher.launch(arrayOf("text/*", "application/octet-stream", "application/x-markdown")) } },
                onExport = {
                    exportNoteId = notebook.id
                    val filename = notebook.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md"
                    launchPicker { exportLauncher.launch(filename) }
                },
                onSelectNote = { id -> lifecycleScope.launch { documentOperation { loadNotebook(id) } } },
                onRetry = ::loadInitialNotes,
                onStartLearning = ::startLearning,
            )
        }
    }

    private fun loadInitialNotes() {
        lifecycleScope.launch {
            documentOperation {
                // One-time migration: retain the old preferences as a recovery source.
                if (store.list().isEmpty()) {
                    val oldBody = preferences.getString("notes", null)
                    store.save(null, preferences.getString("title", "식물의 생명 활동 · 예제")!!,
                        oldBody ?: "## 학습 내용\n\n식물의 생명 활동을 정리한 예제 노트입니다.\n\n## 복습 개념\n" +
                            StudyService().defaultNotes().lines().joinToString("\n") { "- $it" })
                }
                loadNotebook(preferences.getString("selected_note", null))
            }
            refreshQuizzes()
        }
    }

    private suspend fun refreshQuizzes() = documentLock.withLock {
        try {
            quizzes = withContext(Dispatchers.IO) { quizStore.list().map { StudyQuiz(it.id, it.document) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notebook = notebook.copy(error = "저장된 문제 목록을 읽지 못했어요.") }
    }

    private suspend fun saveNote(id: String?, title: String, body: String): String? =
        documentOperation("노트를 기기에 저장했어요.") {
            val saved = store.save(id, title, body)
            loadNotebook(saved.id)
        }

    /** Serializes disk work; failures keep the current document and unsaved editor draft. */
    private suspend fun documentOperation(notice: String? = null, action: () -> StudyNotebook): String? = documentLock.withLock {
        notebook = notebook.copy(busy = true, error = null, notice = null)
        try {
            notebook = withContext(Dispatchers.IO) { action() }.copy(busy = false, notice = notice)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = when (error) {
                is IllegalArgumentException -> error.message ?: "노트 형식을 확인해 주세요."
                is java.nio.charset.CharacterCodingException -> "UTF-8로 저장된 텍스트 파일을 선택해 주세요."
                is SecurityException -> "선택한 파일에 접근할 수 없어요. 파일을 다시 선택해 주세요."
                else -> "파일을 처리하지 못했어요. 저장 공간과 파일을 확인하고 다시 시도해 주세요."
            }
            notebook = notebook.copy(busy = false, error = message)
            message
        }
    }

    private fun loadNotebook(selectedId: String?): StudyNotebook {
        val all = store.list()
        val selected = all.firstOrNull { it.id == selectedId } ?: all.first()
        preferences.edit().putString("selected_note", selected.id).apply()
        return StudyNotebook(selected.id, selected.title, selected.body,
            all.map { StudyNoteSummary(it.id, it.title, it.body) }, ready = true, busy = false)
    }

    private fun readDocument(uri: Uri): Pair<String, String> {
        val filename = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "가져온 노트"
        val bytes = (contentResolver.openInputStream(uri) ?: throw IOException("파일을 열지 못했어요.")).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 400_400) { "파일이 너무 커요. 100,000자 이하의 파일을 선택해 주세요." }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return filename to text
    }

    private fun launchPicker(action: () -> Unit) {
        try { action() } catch (_: android.content.ActivityNotFoundException) {
            notebook = notebook.copy(error = "이 기기에서 파일 선택 화면을 열 수 없어요.")
        }
    }

    private fun startLearning(quizId: String) {
        if (launching || notebook.busy || !notebook.ready) return
        launching = true
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { quizStore.read(quizId) }
                val payload = QuizFiles.write(saved.document)
                notebook = notebook.copy(error = null)
                gameLauncher.launch(Intent(this@StudyHomeActivity, JadeRunActivity::class.java)
                    .putExtra(StudyGameContract.TITLE, saved.document.title)
                    .putExtra(StudyGameContract.QUESTION_SET, payload))
            } catch (cancelled: CancellationException) { launching = false; throw cancelled }
            catch (_: Exception) {
                launching = false
                notebook = notebook.copy(error = "문제 파일로 게임을 열지 못했어요. 파일을 다시 확인해 주세요.")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launching", launching)
        outState.putString("export_note_id", exportNoteId)
        super.onSaveInstanceState(outState)
    }

    private fun parseRecap(record: String): StudyRecap? = runCatching {
        val json = JSONObject(record)
        val summary = json.getJSONObject("summary")
        val answers = summary.getJSONArray("answers")
        StudyRecap(json.getString("title"), summary.getInt("answeredCount"),
            summary.getInt("correctCount"), summary.getInt("questionCount"),
            (0 until answers.length()).map { answers.getJSONObject(it) }
                .filter { !it.getBoolean("correct") }.map { it.getString("explanation") },
            reviewed = summary.optInt("reviewedCount", 0), continuous = summary.optBoolean("continuous", false))
    }.getOrNull()
}

internal object StudyGameContract {
    const val TITLE = "study_title"
    const val QUESTION_SET = "study_question_set"
    const val RESULT = "study_result"
}
