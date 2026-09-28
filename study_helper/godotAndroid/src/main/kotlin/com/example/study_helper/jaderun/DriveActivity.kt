package com.example.study_helper.jaderun

import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.study_helper.study.NotebookPlatform
import com.example.study_helper.sync.*
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Google Play services owns Android credential storage and refresh. */
class DriveActivity : ComponentActivity(), NotebookPlatform, DrivePlatform, FolderPlatform {
    private val folderFiles by lazy { FolderFiles(filesDir,false) { null } }
    override fun folderCommand(request: String, completion: (String) -> Unit) {
        lifecycleScope.launch {
            val result=runCatching {
                val input=kotlinx.serialization.json.Json.parseToJsonElement(request) as kotlinx.serialization.json.JsonObject
                if(JSONObject(request).getString("action")=="open") {
                    val file=folderFiles.resolve(JSONObject(request).optString("path"))
                    val uri=androidx.core.content.FileProvider.getUriForFile(this@DriveActivity,"$packageName.sharedfiles",file)
                    val mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    "{}"
                } else {
                    check(!LectureAudioState.recording && !LectureAudioState.pending) { "녹음을 먼저 저장해 주세요." }
                    withContext(Dispatchers.IO) { folderFiles.execute(input).toString() }
                }
            }.getOrElse { error(it.message ?: "폴더 작업에 실패했어요.") }
            completion(result)
        }
    }
    private val preferences by lazy { getSharedPreferences("study_drive", MODE_PRIVATE) }
    private var completion: ((String) -> Unit)? = null
    private var accessToken: String? = null
    private val authorization by lazy { Identity.getAuthorizationClient(this) }
    private lateinit var sync: DriveSync
    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val callback = completion ?: return@registerForActivityResult
        completion = null
        runCatching { authorization.getAuthorizationResultFromIntent(result.data).accessToken }
            .onSuccess { token -> accessToken = token; callback(if (token != null) JSONObject().put("accessToken", token).toString() else error("Google 접근 권한을 허용해 주세요.")) }
            .onFailure { callback(error("Google 연결이 취소됐거나 설정이 올바르지 않아요.")) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        sync = DriveSync(this, this)
        setContent { DriveSyncScreen(sync, onClose = { setResult(RESULT_OK); finish() }, onChanged = {}) }
    }
    private fun error(text: String) = JSONObject().put("error", text).toString()
    override fun authorizeDrive(completion: (String) -> Unit) {
        if (this.completion != null) { completion(error("Google 로그인이 진행 중이에요.")); return }
        this.completion = completion
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive"))).build()
        authorization.authorize(request).addOnSuccessListener { result ->
            if (result.hasResolution()) launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
            else {
                this.completion = null
                accessToken = result.accessToken
                completion(accessToken?.let { JSONObject().put("accessToken", it).toString() } ?: error("Google 접근 권한을 허용해 주세요."))
            }
        }.addOnFailureListener {
            this.completion = null
            completion(error("Google 연결 실패. Google Cloud의 Android OAuth 패키지명·서명 SHA-1·테스트 사용자 및 Play 서비스를 확인해 주세요."))
        }
    }
    override fun disconnectDrive() {
        accessToken?.let { authorization.clearToken(ClearTokenRequest.builder().setToken(it).build()) }
        accessToken = null
    }
    override fun driveRequest(request: String, completion: (String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val data = JSONObject(request)
                val url = URL(data.getString("url"))
                require(url.protocol == "https" && url.host == "www.googleapis.com")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000; connection.readTimeout = 45_000
                    connection.requestMethod = data.getString("method")
                    val headers = data.getJSONObject("headers")
                    headers.keys().forEach { key -> connection.setRequestProperty(key, headers.getString(key)) }
                    val body = data.getString("body")
                    if (body.isNotEmpty()) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 4_000_000) { "Drive 응답이 너무 커요." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: ByteArray(0)
                    if (status == 401) accessToken?.let { authorization.clearToken(ClearTokenRequest.builder().setToken(it).build()) }
                    JSONObject().put("status", status).put("body", bytes.toString(Charsets.UTF_8)).toString()
                } finally { connection.disconnect() }
            }.getOrElse { error(it.message ?: "네트워크 연결을 확인해 주세요.") } }
            completion(result)
        }
    }
    override fun readFiles(kind: String): String = runCatching {
        val folder = directory(kind)
        val extension = if (kind == "notes") ".md" else ".json"
        val ids = folder.listFiles().orEmpty().filter { it.name.removeSuffix(".bak").endsWith(extension) }
            .map { it.name.removeSuffix(".bak").removeSuffix(extension) }.distinct()
        JSONObject().put("files", JSONArray().apply { ids.forEach { id ->
            put(JSONObject().put("id", id).put("content", AtomicFile(document(kind, id)).readFully().toString(Charsets.UTF_8)))
        } }).toString()
    }.getOrElse { error(it.message ?: "기기 파일을 읽지 못했어요.") }
    private fun directory(kind: String): File {
        require(kind in listOf("notes", "question-sets"))
        return File(filesDir, kind).apply { check(isDirectory || mkdirs()) }
    }
    private fun document(kind: String, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory(kind), id + if (kind == "notes") ".md" else ".json")
    }
    override fun writeFile(kind: String, id: String, content: String): String? = runCatching {
        val file = AtomicFile(document(kind, id)); val stream = file.startWrite()
        try { stream.write(content.toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }.exceptionOrNull()?.message
    override fun newId() = UUID.randomUUID().toString()
    override fun preference(key: String) = preferences.getString(key, null)
    override fun setPreference(key: String, value: String) { check(preferences.edit().putString(key, value).commit()) { "동기화 상태를 저장하지 못했어요." } }
    override fun pickDocument(kind: String) = Unit
    override fun exportDocument(filename: String, content: String) = Unit
    override fun copyText(text: String) = Unit
    override fun openLink(url: String) = Unit
    override fun startGame() = Unit
}
