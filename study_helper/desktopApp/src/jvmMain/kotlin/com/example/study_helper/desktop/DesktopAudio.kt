package com.example.study_helper.desktop

import com.example.study_helper.study.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.sound.sampled.*

/** Java Sound supplies cross-platform recording/playback; optional whisper-cli runs locally. */
class DesktopAudio(private val store: DesktopStore, private val notebook: NotebookHost, private val scope: CoroutineScope, private val onClose: () -> Unit) : LecturePlatform {
    val host = LectureHost(this)
    private val folder = File(store.root, "recordings").apply { mkdirs() }
    private var line: TargetDataLine? = null
    private var recordingJob: Job? = null
    private var clip: Clip? = null
    private var playing = ""
    private var message = ""
    private var pending = false
    private var started = 0L
    private var editor = ""
    private var process: Process? = null
    private var timer: Job? = null
    val isRecording get() = line != null
    private fun file(id: String, extension: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(folder, "$id.$extension")
    }
    private fun title(id: String) = file(id, "title").takeIf { it.exists() }?.readText() ?: "강의 녹음"
    private fun seconds(file: File): Int = runCatching { AudioSystem.getAudioFileFormat(file).let { (it.frameLength / it.format.frameRate).toInt() } }.getOrDefault(0)
    private fun update() {
        host.updateState(buildJsonObject {
            put("recording", isRecording); put("pending", pending); put("message", message)
            put("seconds", if (isRecording) (System.currentTimeMillis() - started) / 1000 else 0)
            put("playing", playing); put("playbackSeconds", (clip?.microsecondPosition ?: 0) / 1_000_000)
            put("deviceTranscriptionAvailable", true)
            put("deviceModelReady", store.preference("whisper_bin")?.let { File(it).isFile } == true && store.preference("whisper_model")?.let { File(it).isFile } == true)
            put("deviceTranscribing", process != null)
            put("editorId", editor)
            if (editor.isNotEmpty()) { put("editorTitle", title(editor)); put("editorText", file(editor, "txt").readText()) }
            putJsonArray("recordings") {
                folder.listFiles().orEmpty().filter { it.extension == "wav" && it.nameWithoutExtension != store.preference("active_recording") }
                    .sortedByDescending { it.lastModified() }.forEach { audio -> add(buildJsonObject {
                        val id = audio.nameWithoutExtension
                        put("id", id); put("title", title(id)); put("date", java.time.Instant.ofEpochMilli(audio.lastModified()).toString())
                        put("seconds", seconds(audio)); put("hasTranscript", file(id, "txt").exists())
                    }) }
            }
        }.toString())
    }
    private fun stopPlayback() { clip?.stop(); clip?.close(); clip = null; playing = "" }
    override fun audioCommand(action: String, id: String, title: String) {
        try {
            when (action) {
                "refresh" -> {
                    if (!isRecording) store.setPreference("active_recording", "")
                    if (timer == null) timer = scope.launch { while (isActive) { delay(1000); update() } }
                }
                "close" -> { onClose(); return }
                "start" -> {
                    check(!isRecording && !pending)
                    stopPlayback()
                    val format = AudioFormat(16000f, 16, 1, true, false)
                    val input = AudioSystem.getTargetDataLine(format)
                    input.open(format); input.start()
                    val newId = UUID.randomUUID().toString()
                    DesktopStore.atomicWrite(file(newId, "title"), title.ifBlank { "강의 녹음" })
                    store.setPreference("active_recording", newId)
                    line = input; started = System.currentTimeMillis(); message = ""
                    recordingJob = scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching {
                            AudioInputStream(input).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, file(newId, "wav")) }
                        } }
                        line = null; pending = false; store.setPreference("active_recording", "")
                        message = if (result.isSuccess) "녹음을 저장했어요." else "녹음 저장 실패: ${result.exceptionOrNull()?.message}"
                        update()
                    }
                }
                "stop" -> { pending = true; line?.stop(); line?.close(); line = null }
                "play" -> {
                    stopPlayback()
                    val player = AudioSystem.getClip()
                    AudioSystem.getAudioInputStream(file(id, "wav")).use { player.open(it) }
                    clip = player; playing = id; player.start()
                }
                "stopPlayback" -> stopPlayback()
                "forward", "backward" -> clip?.let { it.microsecondPosition = (it.microsecondPosition + if (action == "forward") 15_000_000 else -15_000_000).coerceIn(0, it.microsecondLength) }
                "rename" -> DesktopStore.atomicWrite(file(id, "title"), title)
                "delete" -> {
                    if (playing == id) stopPlayback()
                    for (extension in listOf("wav", "title", "txt")) java.nio.file.Files.deleteIfExists(file(id, extension).toPath())
                }
                "openTranscript" -> editor = id
                "closeTranscript" -> editor = ""
                "saveTranscript" -> {
                    val noteId = store.preference("recording_note_$id") ?: UUID.randomUUID().toString()
                    val error = notebook.saveNote(noteId, title(id).take(60), title)
                    check(error == null) { error.orEmpty() }
                    DesktopStore.atomicWrite(file(id, "txt"), title)
                    store.setPreference("recording_note_$id", noteId); editor = ""; message = "노트로 저장했어요."
                }
                "downloadDeviceModel" -> message = "PC 설정에서 Whisper 실행 파일과 모델 파일을 선택해 주세요."
                "transcribeDevice" -> {
                    val binary = store.preference("whisper_bin") ?: error("PC 설정에서 Whisper 실행 파일을 선택해 주세요.")
                    val model = store.preference("whisper_model") ?: error("PC 설정에서 Whisper 모델을 선택해 주세요.")
                    pending = true; message = "기기에서 음성을 변환하고 있어요."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching {
                            val output = File(folder, "$id-transcribing")
                            val child = ProcessBuilder(binary, "-m", model, "-f", file(id, "wav").absolutePath, "-l", "ko", "-otxt", "-of", output.absolutePath)
                                .redirectErrorStream(true).redirectOutput(File(folder, "whisper.log")).start()
                            process = child
                            check(child.waitFor() == 0) { "음성 변환이 중단됐어요."
                            }
                            val text = File(output.path + ".txt").readText().trim()
                            check(text.isNotEmpty()) { "인식한 음성이 없어요." }
                            text
                        } }
                        process = null; pending = false
                        result.onSuccess { DesktopStore.atomicWrite(file(id, "txt"), it); editor = id; message = "변환이 끝났어요." }
                            .onFailure { message = it.message.orEmpty() }
                        update()
                    }
                }
                "cancelDeviceTranscription" -> { process?.destroy(); message = "변환을 취소하고 있어요." }
            }
        } catch (error: Exception) { message = error.message ?: "오디오 장치를 사용할 수 없어요." }
        update()
    }
    fun close() { line?.stop(); line?.close(); process?.destroy(); stopPlayback(); timer?.cancel() }
}
