package com.example.study_helper.jaderun

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Single main-thread audio owner shared by the activity and foreground service. */
internal object LectureAudioState {
    var recording = false
    var pending = false
    var seconds = 0
    var message = ""
    var playing = ""
    var playbackSeconds = 0
    var activeId = ""
    var rows = JSONArray()
    var listener: ((String) -> Unit)? = null
    fun directory(context: Context) = File(context.filesDir, "recordings").also {
        check(it.isDirectory || it.mkdirs()) { "녹음 폴더를 만들지 못했어요." }
    }
    fun file(context: Context, id: String, extension: String): File {
        require(UUID.fromString(id).toString() == id) { "올바르지 않은 녹음 ID입니다." }
        return File(directory(context), "$id.$extension")
    }
    fun write(context: Context, entry: JSONObject) {
        val file = AtomicFile(file(context, entry.getString("id"), "json"))
        val stream = file.startWrite()
        try { stream.write(entry.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun refresh(context: Context) {
        try {
            val entries = directory(context).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { metadata ->
                runCatching {
                    val row = JSONObject(metadata.readText())
                    val id = row.getString("id")
                    val audio = file(context, id, "m4a")
                    if (!audio.exists() || id == activeId) return@runCatching null
                    if (row.optInt("seconds") == 0) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(audio.path)
                            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                            row.put("seconds", (ms + 999) / 1000); write(context, row)
                        } finally { retriever.release() }
                    }
                    row
                }.getOrNull()
            }.sortedByDescending { it.getString("date") }
            rows = JSONArray(entries)
        } catch (_: Exception) { message = "녹음 목록을 읽지 못했어요." }
        publish()
    }
    fun entry(id: String): JSONObject? = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    fun publish() {
        listener?.invoke(JSONObject().put("recordings", rows).put("recording", recording).put("pending", pending)
            .put("seconds", seconds).put("playing", playing).put("playbackSeconds", playbackSeconds).put("message", message).toString())
    }
}

class LectureAudioService : Service() {
    private var recorder: MediaRecorder? = null
    private var entry: JSONObject? = null
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0 && recorder != null) finish("다른 오디오 사용으로 녹음이 중단되어 저장했어요.")
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            LectureAudioState.seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            LectureAudioState.publish()
            handler.postDelayed(this, 500)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        if (recorder != null) return START_NOT_STICKY
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        try {
            showNotification()
            begin(intent.getStringExtra("title").orEmpty())
        } catch (error: Exception) {
            finish("녹음을 시작하지 못했어요. 마이크 권한과 저장 공간을 확인해 주세요.")
        }
        return START_NOT_STICKY // Never silently restart recording after process death.
    }
    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "강의 녹음", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LectureAudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, LectureAudioService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.ic_study).setContentTitle("강의 녹음 중")
            .setContentText("녹음 화면으로 돌아가거나 종료하여 저장하세요.").setContentIntent(open)
            .setOngoing(true).setUsesChronometer(true).setWhen(System.currentTimeMillis())
            .addAction(Notification.Action.Builder(null, "종료 · 저장", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(702, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(702, notification)
    }
    private fun begin(title: String) {
        LectureAudioState.message = ""
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, handler).build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        check(focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val id = UUID.randomUUID().toString()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
        val row = JSONObject().put("id", id).put("title", title.trim().take(120).ifEmpty { "강의 $date" }).put("date", date).put("seconds", 0)
        LectureAudioState.write(this, row)
        val audio = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = audio; entry = row
        audio.setAudioSource(MediaRecorder.AudioSource.MIC)
        audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        audio.setAudioSamplingRate(44100); audio.setAudioChannels(1); audio.setAudioEncodingBitRate(64000)
        audio.setOutputFile(LectureAudioState.file(this, id, "m4a").path)
        audio.setMaxDuration(6 * 60 * 60 * 1000)
        audio.setOnErrorListener { _, _, _ -> handler.post { finish("녹음 중 오류가 발생했어요. 저장된 파일을 확인해 주세요.") } }
        audio.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                handler.post { finish("녹음 한도에 도달해 저장했어요. 필요하면 새 녹음을 시작해 주세요.") }
        }
        audio.prepare(); audio.start()
        startedAt = SystemClock.elapsedRealtime()
        LectureAudioState.activeId = id; LectureAudioState.recording = true; LectureAudioState.pending = false
        LectureAudioState.seconds = 0
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyHelper:LectureAudio").also { it.acquire(6 * 60 * 60 * 1000L + 10000) }
        handler.post(ticker)
    }
    private fun finish(reason: String? = null) {
        handler.removeCallbacks(ticker)
        val audio = recorder; recorder = null
        var saved = false
        if (audio != null) {
            audio.setOnErrorListener(null); audio.setOnInfoListener(null)
            try {
                audio.stop()
                entry?.let { row ->
                    row.put("seconds", ((SystemClock.elapsedRealtime() - startedAt + 999) / 1000).coerceAtLeast(1))
                    LectureAudioState.write(this, row); saved = true
                }
            } catch (_: Exception) { /* Retain partial files for recovery; never silently delete audio. */ }
            finally { audio.release() }
        }
        entry = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else audioManager.abandonAudioFocus(focusListener)
        focusRequest = null
        LectureAudioState.recording = false; LectureAudioState.pending = false; LectureAudioState.activeId = ""
        LectureAudioState.message = if (audio != null && !saved) "녹음을 저장하지 못했어요. 너무 짧은 녹음이거나 저장 공간이 부족할 수 있어요." else reason ?: "녹음을 저장했어요."
        LectureAudioState.refresh(this)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        if (recorder != null) finish("녹음 서비스가 종료되어 저장했어요.")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    companion object {
        const val START = "study.audio.START"
        const val STOP = "study.audio.STOP"
        private const val CHANNEL = "lecture-recording"
    }
}
