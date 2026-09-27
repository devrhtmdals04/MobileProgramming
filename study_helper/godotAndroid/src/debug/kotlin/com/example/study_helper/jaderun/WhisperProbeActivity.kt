package com.example.study_helper.jaderun

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Debug-only fixture probe; never opens the microphone or touches the user's notes. */
class WhisperProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val report = File(filesDir, "whisper-device-report.json")
        val fixture = File(filesDir, "whisper-probe.m4a")
        val root = File(cacheDir, "probe-${UUID.randomUUID()}").also { it.mkdirs() }
        val isolated = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
            override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
        }
        val id = UUID.randomUUID().toString()
        val started = System.nanoTime()
        try {
            fixture.copyTo(LectureAudioState.file(isolated, id, "m4a"))
            LectureAudioState.write(isolated, JSONObject().put("id", id).put("title", "디지털시스템입문 · 오프라인 검사")
                .put("date", "2026-09-27").put("seconds", 12).put("transcript", "기존 받아쓰기"))
            LectureAudioState.refresh(isolated)
            DeviceWhisper.start(isolated, id)
            val handler = Handler(Looper.getMainLooper())
            val check = object : Runnable {
                override fun run() {
                    if (LectureAudioState.pending) { handler.postDelayed(this, 500); return }
                    try {
                        val text = LectureAudioState.editorText
                        check(text.contains("디지털")) { LectureAudioState.message }
                        val entry = LectureAudioState.entry(id)!!
                        check(entry.getString("transcript") == "기존 받아쓰기")
                        check(entry.getString("whisperTranscript") == text)
                        val store = MarkdownNoteStore(isolated)
                        store.save(id, entry.getString("title"), text)
                        check(store.read(id).body.contains("디지털"))
                        report.writeText(JSONObject().put("checks", 5).put("failures", JSONArray()).put("text", text)
                            .put("model", "whisper-small-q5_1").put("networkUsedForInference", false).put("microphoneUsed", false)
                            .put("elapsedSeconds", (System.nanoTime() - started) / 1e9).toString())
                    } catch (error: Exception) { report.writeText(JSONObject().put("error", error.toString()).toString()) }
                    finally { root.deleteRecursively(); finish() }
                }
            }
            handler.post(check)
        } catch (error: Exception) { report.writeText(JSONObject().put("error", error.toString()).toString()); root.deleteRecursively(); finish() }
    }
}
