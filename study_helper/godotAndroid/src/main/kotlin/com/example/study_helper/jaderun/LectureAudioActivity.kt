package com.example.study_helper.jaderun

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.study_helper.study.*

class LectureAudioActivity : ComponentActivity(), LecturePlatform {
    private val host = LectureHost(this)
    private var player: MediaPlayer? = null
    private var requestedTitle = ""
    private var savedNoteId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { if (it < 0) stopPlayback() }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        LectureAudioState.pending = false
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startRecording()
        else { LectureAudioState.message = "마이크 권한이 필요해요. 기기 설정 → 앱 → Study Helper → 권한에서 허용해 주세요."; LectureAudioState.publish() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (player == null) return
            LectureAudioState.playbackSeconds = (player?.currentPosition ?: 0) / 1000
            LectureAudioState.publish(); handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        requestedTitle = savedInstanceState?.getString("title").orEmpty()
        savedNoteId = savedInstanceState?.getString("savedNoteId")
        savedNoteId?.let { setResult(RESULT_OK, Intent().putExtra("savedNoteId", it)) }
        setContent { LectureRecordings(host) }
    }
    override fun onStart() {
        super.onStart()
        LectureAudioState.listener = { json ->
            host.updateState(json)
            if (host.state.deviceTranscribing || host.state.modelDownloading) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        LectureAudioState.refresh(this)
    }
    override fun onStop() {
        if (!isChangingConfigurations && (LectureAudioState.deviceTranscribing || LectureAudioState.modelDownloading)) DeviceWhisper.cancelWork()
        stopPlayback(); LectureAudioState.listener = null; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("title", requestedTitle); outState.putString("savedNoteId", savedNoteId); super.onSaveInstanceState(outState) }
    override fun audioCommand(action: String, id: String, title: String) {
        if (action == "cancelDeviceTranscription") { DeviceWhisper.cancelWork(); return }
        if (LectureAudioState.pending) return
        if (LectureAudioState.recording && action in listOf("start", "play", "delete", "rename", "transcribeDevice", "downloadDeviceModel", "openTranscript", "saveTranscript")) return
        try {
            when (action) {
                "refresh" -> LectureAudioState.refresh(this)
                "downloadDeviceModel" -> { stopPlayback(); DeviceWhisper.start(this, null) }
                "transcribeDevice" -> { stopPlayback(); DeviceWhisper.start(this, id) }
                "openTranscript" -> {
                    val row = LectureAudioState.entry(id) ?: return
                    LectureAudioState.editorId = id; LectureAudioState.editorTitle = row.getString("title")
                    LectureAudioState.editorText = row.optString("whisperTranscript", row.optString("transcript"))
                }
                "closeTranscript" -> { LectureAudioState.editorId = ""; LectureAudioState.editorText = "" }
                "saveTranscript" -> {
                    val row = LectureAudioState.entry(id)?.let { org.json.JSONObject(it.toString()) } ?: return
                    require(title.isNotBlank())
                    MarkdownNoteStore(this).save(id, row.getString("title").replace("\n", " ").replace("\r", " ").take(60), "## 강의 받아쓰기\n\n" + title)
                    row.put("transcript", title); row.remove("whisperTranscript")
                    LectureAudioState.write(this, row)
                    savedNoteId = id; setResult(RESULT_OK, Intent().putExtra("savedNoteId", id))
                    LectureAudioState.editorId = ""; LectureAudioState.editorText = ""
                    LectureAudioState.message = "받아쓰기를 노트 서재에 저장했어요. 다시 저장하면 같은 노트를 갱신합니다."
                    LectureAudioState.refresh(this)
                }
                "start" -> {
                    stopPlayback(); requestedTitle = title
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else {
                        LectureAudioState.pending = true
                        permission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
                "stop" -> startService(Intent(this, LectureAudioService::class.java).setAction(LectureAudioService.STOP))
                "play" -> {
                    stopPlayback()
                    check(LectureAudioState.entry(id) != null)
                    val focus = if (Build.VERSION.SDK_INT >= 26) {
                        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setOnAudioFocusChangeListener(focusListener, handler).build()
                        focusRequest = request; audioManager.requestAudioFocus(request)
                    } else audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    check(focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    val audio = MediaPlayer()
                    player = audio
                    audio.setDataSource(LectureAudioState.file(this, id, "m4a").path)
                    audio.setOnPreparedListener {
                        LectureAudioState.pending = false; LectureAudioState.playing = id
                        it.start(); handler.post(ticker)
                    }
                    audio.setOnCompletionListener { stopPlayback() }
                    audio.setOnErrorListener { _, _, _ -> stopPlayback(); LectureAudioState.message = "이 녹음을 재생하지 못했어요."; LectureAudioState.publish(); true }
                    LectureAudioState.pending = true; audio.prepareAsync()
                }
                "stopPlayback" -> stopPlayback()
                "backward" -> player?.let { it.seekTo((it.currentPosition - 15000).coerceAtLeast(0)) }
                "forward" -> player?.let { it.seekTo((it.currentPosition + 15000).coerceAtMost(it.duration)) }
                "rename" -> {
                    val row = LectureAudioState.entry(id) ?: return
                    require(title.isNotBlank())
                    LectureAudioState.write(this, org.json.JSONObject(row.toString()).put("title", title.take(120)))
                    LectureAudioState.refresh(this)
                }
                "delete" -> {
                    if (LectureAudioState.entry(id) == null) return
                    if (LectureAudioState.playing == id) stopPlayback()
                    check(LectureAudioState.file(this, id, "m4a").delete())
                    check(LectureAudioState.file(this, id, "json").delete())
                    LectureAudioState.refresh(this)
                }
                "close" -> finish()
            }
        } catch (_: Exception) {
            if (action == "play") stopPlayback()
            LectureAudioState.pending = false
            LectureAudioState.message = "요청을 처리하지 못했어요. 마이크 권한과 파일을 확인해 주세요."
        }
        LectureAudioState.publish()
    }
    private fun startRecording() {
        try {
            LectureAudioState.pending = true; LectureAudioState.message = ""
            val intent = Intent(this, LectureAudioService::class.java).setAction(LectureAudioService.START).putExtra("title", requestedTitle)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { LectureAudioState.pending = false; LectureAudioState.message = "앱 화면에서 녹음 시작을 다시 눌러 주세요." }
        LectureAudioState.publish()
    }
    private fun stopPlayback() {
        val hadPlayer = player != null
        player?.release(); player = null; handler.removeCallbacks(ticker)
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else audioManager.abandonAudioFocus(focusListener)
        focusRequest = null
        LectureAudioState.playing = ""; LectureAudioState.playbackSeconds = 0
        if (hadPlayer) LectureAudioState.pending = false
        LectureAudioState.publish()
    }
}
