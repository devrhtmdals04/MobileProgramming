package com.example.study_helper.study

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

/** Native audio only; recordings never enter the Godot or question-generation bridge. */
interface LecturePlatform {
    fun audioCommand(action: String, id: String, title: String)
}

data class LectureRecording(val id: String, val title: String, val date: String, val seconds: Int, val hasTranscript: Boolean = false)
data class LectureAudioState(
    val recordings: List<LectureRecording> = emptyList(), val recording: Boolean = false,
    val pending: Boolean = false, val seconds: Int = 0, val playing: String = "",
    val playbackSeconds: Int = 0, val message: String = "",
    val transcript: String = "", val provisional: String = "", val speechStatus: String = "",
    val editorId: String = "", val editorTitle: String = "", val editorText: String = "",
    val localTranscriptionAvailable: Boolean = false, val localTranscribing: Boolean = false,
    val deviceTranscriptionAvailable: Boolean = false, val deviceModelReady: Boolean = false,
    val deviceTranscribing: Boolean = false, val modelDownloading: Boolean = false, val deviceProgress: Int = 0,
)

class LectureHost(private val platform: LecturePlatform) {
    var state by mutableStateOf(LectureAudioState())
        private set

    /** Main-thread snapshot from the platform recorder. Never stores audio in Compose state. */
    fun updateState(json: String) {
        try {
            val data = Json.parseToJsonElement(json).jsonObject
            fun text(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            fun flag(key: String) = data[key]?.jsonPrimitive?.booleanOrNull ?: false
            fun number(key: String) = (data[key]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val recordings = data["recordings"]?.jsonArray.orEmpty().map {
                val row = it.jsonObject
                LectureRecording(row.getValue("id").jsonPrimitive.content,
                    row.getValue("title").jsonPrimitive.content,
                    row.getValue("date").jsonPrimitive.content,
                    row.getValue("seconds").jsonPrimitive.int.coerceAtLeast(0), row["hasTranscript"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            state = LectureAudioState(recordings, flag("recording"), flag("pending"), number("seconds"),
                text("playing"), number("playbackSeconds"), text("message"),
                text("transcript"), text("provisional"), text("speechStatus"), text("editorId"), text("editorTitle"), text("editorText"),
                flag("localTranscriptionAvailable"), flag("localTranscribing"), flag("deviceTranscriptionAvailable"),
                flag("deviceModelReady"), flag("deviceTranscribing"), flag("modelDownloading"), number("deviceProgress").coerceAtMost(100))
        } catch (_: Exception) {
            state = state.copy(message = "녹음 상태를 읽지 못했어요. 녹음 화면을 다시 열어 주세요.")
        }
    }

    fun command(action: String, id: String = "", title: String = "") {
        if (state.pending && action !in listOf("close", "cancelLocalTranscription", "cancelDeviceTranscription")) return
        if (state.recording && action in listOf("start", "play", "delete", "rename", "openTranscript", "saveTranscript", "transcribeLocal", "transcribeDevice", "downloadDeviceModel")) return
        platform.audioCommand(action, id, if (action == "saveTranscript") title.trim() else title.trim().take(120))
    }
}

private fun audioTime(seconds: Int): String =
    "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
fun LectureRecordings(host: LectureHost) {
    val state = host.state
    var title by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<LectureRecording?>(null) }
    var renaming by remember { mutableStateOf<LectureRecording?>(null) }
    var editedTitle by rememberSaveable { mutableStateOf("") }
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { host.command("close") }, enabled = !state.pending) { Text("서재로") }
                    TextButton(onClick = { host.command("refresh") }, enabled = !state.pending) { Text("새로고침") }
                }
                Text("강의 녹음", style = MaterialTheme.typography.headlineMedium)
                Text("음성은 이 기기에 저장됩니다.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (state.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.deviceTranscriptionAvailable) {
                    Text(if (state.deviceModelReady) "오프라인 Whisper · 음성은 기기 안에서 처리됩니다." else "오프라인 변환 모델 · 최초 다운로드 약 190MB", style = MaterialTheme.typography.bodySmall)
                    if (!state.deviceModelReady && !state.modelDownloading) {
                        OutlinedButton(onClick = { host.command("downloadDeviceModel") }, enabled = !state.recording && !state.pending) { Text("오프라인 모델 다운로드") }
                    }
                    if (state.deviceTranscribing) Text("변환 진행 ${state.deviceProgress}% · 처음에는 모델 준비에 시간이 걸립니다.")
                    if (state.deviceTranscribing || state.modelDownloading) {
                        TextButton(onClick = { host.command("cancelDeviceTranscription") }) { Text("취소") }
                    }
                }
                if (state.localTranscribing) TextButton(onClick = { host.command("cancelLocalTranscription") }) { Text("변환 취소") }
                if (state.localTranscriptionAvailable) Text("Mac 변환: 같은 Wi-Fi의 Mac에서 처리하며 외부 AI 서비스로 전송하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                if (state.message.isNotBlank()) Text(state.message, modifier = Modifier.padding(vertical = 8.dp))
                if (state.recording) {
                    Text("● 녹음 중  ${audioTime(state.seconds)}", color = colors.error,
                        style = MaterialTheme.typography.headlineSmall)
                    Text("화면을 잠가도 계속 녹음합니다. 통화 등으로 중단되면 저장합니다.")
                    Button(onClick = { host.command("stop") }, modifier = Modifier.fillMaxWidth()) { Text("녹음 종료 · 저장") }
                } else {
                    OutlinedTextField(title, { title = it.take(120) }, label = { Text("강의 제목 (선택)") },
                        singleLine = true, enabled = !state.pending, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { host.command("start", title = title) }, enabled = !state.pending,
                        modifier = Modifier.fillMaxWidth()) { Text("녹음 시작") }
                }
                if (state.speechStatus.isNotBlank() && (state.recording || state.pending)) {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(state.speechStatus, style = MaterialTheme.typography.labelMedium)
                            val scroll = rememberScrollState()
                            LaunchedEffect(state.transcript, state.provisional) { scroll.scrollTo(scroll.maxValue) }
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(scroll)) {
                                    Text(state.transcript.ifEmpty { "말씀하시면 여기에 받아쓰기가 표시됩니다." })
                                    if (state.provisional.isNotBlank()) Text(state.provisional, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("저장된 녹음 · ${state.recordings.size}개", style = MaterialTheme.typography.titleMedium)
                if (state.recordings.isEmpty()) Text("강의를 녹음하면 여기에 표시됩니다.", Modifier.padding(vertical = 20.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.recordings, key = { it.id }) { recording ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(recording.title, style = MaterialTheme.typography.titleMedium)
                                Text("${recording.date} · ${audioTime(recording.seconds)}", style = MaterialTheme.typography.bodySmall)
                                if (state.playing == recording.id) {
                                    Text("재생 중 ${audioTime(state.playbackSeconds)} / ${audioTime(recording.seconds)}")
                                    Row {
                                        TextButton(onClick = { host.command("backward") }) { Text("15초 이전") }
                                        TextButton(onClick = { host.command("forward") }) { Text("15초 이후") }
                                    }
                                }
                                if (state.deviceTranscriptionAvailable) {
                                    TextButton(onClick = { host.command("transcribeDevice", recording.id) }, enabled = state.deviceModelReady && !state.recording && !state.pending) {
                                        Text("이 기기에서 텍스트로 변환")
                                    }
                                }
                                if (state.localTranscriptionAvailable) {
                                    TextButton(onClick = { host.command("transcribeLocal", recording.id) }, enabled = !state.recording && !state.pending) {
                                        Text("Mac에서 다시 변환 · Whisper")
                                    }
                                }
                                if (recording.hasTranscript) {
                                    TextButton(onClick = { host.command("openTranscript", recording.id) }, enabled = !state.recording && !state.pending) {
                                        Text("받아쓰기 보기 · 노트로 저장")
                                    }
                                }
                                Row {
                                    TextButton(onClick = { host.command(if (state.playing == recording.id) "stopPlayback" else "play", recording.id) },
                                        enabled = !state.recording && !state.pending) { Text(if (state.playing == recording.id) "재생 종료" else "재생") }
                                    TextButton(onClick = { renaming = recording; editedTitle = recording.title },
                                        enabled = !state.recording && !state.pending) { Text("이름 변경") }
                                    TextButton(onClick = { deleting = recording }, enabled = !state.recording && !state.pending) { Text("삭제") }
                                }
                            }
                        }
                    }
                }
            }
            if (state.editorId.isNotBlank()) {
                var text by rememberSaveable(state.editorId) { mutableStateOf(state.editorText) }
                Dialog(onDismissRequest = { host.command("closeTranscript") }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp)) {
                            Text(state.editorTitle, style = MaterialTheme.typography.titleLarge)
                            Text("잘못 인식한 내용을 고친 뒤 노트로 저장하세요. 다시 저장하면 같은 노트를 갱신합니다.")
                            if (state.message.isNotBlank()) Text(state.message, Modifier.padding(vertical = 8.dp))
                            OutlinedTextField(text, { text = it }, label = { Text("강의 받아쓰기") },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            Row {
                                TextButton(onClick = { host.command("closeTranscript") }) { Text("닫기") }
                                Button(onClick = { host.command("saveTranscript", state.editorId, text) }, enabled = text.isNotBlank() && !state.pending) {
                                    Text("노트로 저장")
                                }
                            }
                        }
                    }
                }
            }
            deleting?.let { recording ->
                AlertDialog(onDismissRequest = { deleting = null }, title = { Text("녹음을 삭제할까요?") },
                    text = { Text("‘${recording.title}’의 음성 파일이 기기에서 삭제됩니다.") },
                    confirmButton = { TextButton(onClick = { host.command("delete", recording.id); deleting = null }) { Text("삭제") } },
                    dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } })
            }
            renaming?.let { recording ->
                AlertDialog(onDismissRequest = { renaming = null }, title = { Text("강의 제목") },
                    text = { OutlinedTextField(editedTitle, { editedTitle = it.take(120) }, singleLine = true) },
                    confirmButton = { TextButton(onClick = { host.command("rename", recording.id, editedTitle); renaming = null },
                        enabled = editedTitle.isNotBlank()) { Text("저장") } },
                    dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } })
            }
        }
    }
}
