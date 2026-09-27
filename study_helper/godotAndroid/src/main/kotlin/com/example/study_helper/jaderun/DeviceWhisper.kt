package com.example.study_helper.jaderun

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Shared engine and model with iOS. Network access is confined to model installation. */
internal object DeviceWhisper {
    const val MODEL = "ggml-small-q5_1.bin"
    private const val HASH = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean()
    private val nativeLock = Any()
    private var nativeJob = 0L
    @Volatile private var connection: HttpURLConnection? = null
    private external fun create(): Long
    private external fun cancel(job: Long)
    private external fun progress(job: Long): Int
    private external fun run(job: Long, model: String, pcm: String, output: String): Int
    private external fun destroy(job: Long)
    private fun model(context: Context) = File(context.noBackupFilesDir, "whisper/$MODEL")
    fun ready(context: Context) = model(context).isFile
    private fun checkpoint() { if (cancelled.get()) throw InterruptedIOException("변환을 취소했어요.") }
    fun cancelWork() {
        cancelled.set(true)
        connection?.disconnect()
        synchronized(nativeLock) { if (nativeJob != 0L) cancel(nativeJob) }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!LectureAudioState.deviceTranscribing) return
            LectureAudioState.deviceProgress = synchronized(nativeLock) { if (nativeJob == 0L) 0 else progress(nativeJob) }
            LectureAudioState.publish(); main.postDelayed(this, 500)
        }
    }
    fun start(context: Context, id: String?) {
        if (LectureAudioState.pending || LectureAudioState.recording) return
        val app = context.applicationContext
        val row = id?.let { LectureAudioState.entry(it)?.let { value -> JSONObject(value.toString()) } }
        if (id != null && row == null) return
        cancelled.set(false)
        LectureAudioState.pending = true
        LectureAudioState.modelDownloading = id == null
        LectureAudioState.deviceTranscribing = id != null
        LectureAudioState.deviceProgress = 0
        LectureAudioState.message = if (id == null) "오프라인 모델을 다운로드하고 있어요. 약 190MB이며 처음 한 번만 필요합니다." else "이 기기에서 변환하고 있어요. 앱 화면을 유지해 주세요. 음성을 외부로 전송하지 않습니다."
        LectureAudioState.publish(); main.post(ticker)
        executor.execute {
            var text: String? = null
            var failure: String? = null
            try {
                if (id == null) install(app)
                else {
                    check(ready(app)) { "먼저 오프라인 모델을 다운로드해 주세요." }
                    check(row!!.optInt("seconds") <= 7200) { "기기 내 변환은 2시간 이내 녹음을 지원합니다." }
                    System.loadLibrary("studywhisper")
                    val directory = File(app.cacheDir, "whisper-${UUID.randomUUID()}").also { check(it.mkdirs()) }
                    try {
                        val pcm = File(directory, "audio.f32")
                        decode(LectureAudioState.file(app, id, "m4a"), pcm)
                        checkpoint()
                        val job = create()
                        synchronized(nativeLock) { nativeJob = job; if (cancelled.get()) cancel(job) }
                        val output = File(directory, "text.txt")
                        val result = try { run(job, model(app).path, pcm.path, output.path) }
                        finally { synchronized(nativeLock) { nativeJob = 0; destroy(job) } }
                        checkpoint()
                        check(result == 0) { "기기에서 변환하지 못했어요. 다른 앱을 닫고 다시 시도해 주세요." }
                        text = output.readText().trim()
                        check(text.isNotEmpty()) { "인식된 음성이 없어요. 기존 받아쓰기는 유지됩니다." }
                    } finally { directory.deleteRecursively() }
                }
            } catch (error: Exception) { failure = if (cancelled.get()) "요청을 취소했어요. 기존 녹음과 받아쓰기는 유지됩니다." else error.message ?: "변환에 실패했어요." }
            catch (_: LinkageError) { failure = "이 기기의 Whisper 엔진을 불러오지 못했어요." }
            main.post {
                try {
                    if (cancelled.get()) LectureAudioState.message = "요청을 취소했어요. 기존 녹음과 받아쓰기는 유지됩니다."
                    else if (failure != null) LectureAudioState.message = failure
                    else if (text != null && row != null) {
                        row.put("whisperTranscript", text)
                        LectureAudioState.write(app, row)
                        LectureAudioState.editorId = id!!; LectureAudioState.editorTitle = row.getString("title")
                        LectureAudioState.editorText = text
                        LectureAudioState.message = "기기 내 변환 완료. 내용을 확인하고 노트로 저장하세요."
                    } else LectureAudioState.message = "모델 준비 완료. 이제 인터넷 없이 변환할 수 있어요."
                } catch (_: Exception) { LectureAudioState.message = "받아쓰기를 저장하지 못했어요. 저장 공간을 확인해 주세요." }
                finally {
                    LectureAudioState.pending = false; LectureAudioState.deviceTranscribing = false; LectureAudioState.modelDownloading = false
                    main.removeCallbacks(ticker); LectureAudioState.refresh(app)
                }
            }
        }
    }
    private fun install(context: Context) {
        if (ready(context)) return
        val destination = model(context)
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val temporary = File(destination.parentFile, "model.part")
        try {
            val request = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL").openConnection() as HttpURLConnection
            connection = request; request.connectTimeout = 30000; request.readTimeout = 30000
            check(request.responseCode == 200) { "모델 다운로드 서버 오류입니다. 잠시 뒤 다시 시도하세요." }
            val hash = MessageDigest.getInstance("SHA-256")
            request.inputStream.use { input -> temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(1024 * 1024); var total = 0L
                while (true) {
                    checkpoint(); val count = input.read(buffer); if (count < 0) break
                    total += count; check(total <= 220L * 1024 * 1024) { "모델 파일 크기가 올바르지 않습니다." }
                    hash.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            check(hash.digest().joinToString("") { "%02x".format(it) } == HASH) { "모델 검증에 실패했어요. 다시 다운로드해 주세요." }
            checkpoint(); check(temporary.renameTo(destination)) { "모델을 저장하지 못했어요." }
        } finally { connection?.disconnect(); connection = null; temporary.delete() }
    }
    private fun decode(input: File, output: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) check(format.getLong(MediaFormat.KEY_DURATION) <= 7200_000_000L) { "2시간 이내 녹음을 선택해 주세요." }
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var ended = false; var done = false
            var lastActivity = android.os.SystemClock.elapsedRealtime()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var sourceIndex = 0L; var nextOutput = 0.0; var previous = 0f; var written = 0L
            val info = MediaCodec.BufferInfo()
            output.outputStream().buffered().use { stream ->
                fun writeFloat(value: Float) {
                    val bits = value.toRawBits()
                    stream.write(bits and 255); stream.write((bits ushr 8) and 255)
                    stream.write((bits ushr 16) and 255); stream.write((bits ushr 24) and 255)
                    written++; check(written <= 16000L * 7200) { "2시간 이내 녹음을 선택해 주세요." }
                }
                while (!done) {
                    checkpoint()
                    check(android.os.SystemClock.elapsedRealtime() - lastActivity < 60000) { "음성 디코딩이 지연되었습니다. 다시 시도해 주세요." }
                    if (!ended) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            lastActivity = android.os.SystemClock.elapsedRealtime()
                            val buffer = decoder.getInputBuffer(index)!!; buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended = true }
                            else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decoded = decoder.outputFormat
                        rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        check(rate > 0 && channels in 1..8 && encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                    } else if (index >= 0) {
                        lastActivity = android.os.SystemClock.elapsedRealtime()
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        while (buffer.remaining() >= channels * width) {
                            var sample = 0f
                            repeat(channels) { sample += if (width == 4) buffer.float else buffer.short / 32768f }
                            sample /= channels
                            while (nextOutput <= sourceIndex) {
                                val fraction = (nextOutput - (sourceIndex - 1)).coerceIn(0.0, 1.0).toFloat()
                                writeFloat(if (sourceIndex == 0L) sample else previous + (sample - previous) * fraction)
                                nextOutput += rate / 16000.0
                            }
                            previous = sample; sourceIndex++
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally { codec?.release(); extractor.release() }
    }
}
