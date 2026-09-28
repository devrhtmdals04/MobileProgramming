package com.example.study_helper.sync

import androidx.compose.runtime.*
import com.example.study_helper.core.MarkdownNotes
import com.example.study_helper.core.QuizFiles
import com.example.study_helper.study.NotebookPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Native implementations keep OAuth credentials out of document storage and callback on the UI thread.
 * HTTP response: {status: number, body: string}; auth: {accessToken: string} or {error: string}.
 */
interface DrivePlatform {
    fun authorizeDrive(completion: (String) -> Unit)
    fun driveRequest(request: String, completion: (String) -> Unit)
    fun disconnectDrive()
}

data class DriveEntry(val id: String, val name: String, val folder: Boolean, val path: String = "")
data class DriveState(
    val busy: Boolean = false, val connected: Boolean = false, val message: String = "",
    val folderId: String = "", val folderName: String = "", val entries: List<DriveEntry> = emptyList(),
    val browsingId: String = "root", val browsingName: String = "내 드라이브",
)

/** A three-way decision, deliberately preserving deletions until an explicit removal workflow exists. */
enum class SyncDecision { SAME, DOWNLOAD, UPLOAD, CONFLICT }
fun syncDecision(base: String?, local: String?, remote: String): SyncDecision = when {
    local == remote -> SyncDecision.SAME
    local == null || local == base -> SyncDecision.DOWNLOAD
    base != null && remote == base -> SyncDecision.UPLOAD
    else -> SyncDecision.CONFLICT
}

internal fun urlEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-._~") append(n.toChar())
        else { append('%'); append("0123456789ABCDEF"[n shr 4]); append("0123456789ABCDEF"[n and 15]) }
    }
}

private const val API = "https://www.googleapis.com/drive/v2/files"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v2/files"
private const val FOLDER = "application/vnd.google-apps.folder"
private const val FIELDS = "id,title,mimeType,etag,properties,fileSize,labels"
private val validID = Regex("[a-zA-Z0-9_-]{1,200}")
private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

/** All local writes and state changes run on the caller's main dispatcher. Network is asynchronous.
 * Metadata ETags (Drive v2) guard every overwrite. Baselines are committed only after durable writes.
 */
class DriveSync(private val local: NotebookPlatform, private val platform: DrivePlatform) {
    val mirror = (platform as? FolderPlatform)?.let { FolderMirror(local,it) }
    var state by mutableStateOf(DriveState(
        folderId = local.preference("drive_folder").orEmpty(), folderName = local.preference("drive_folder_name").orEmpty()))
        private set
    private val lock = Mutex()
    private var token = ""
    private var account = ""
    private val history = mutableListOf<Pair<String, String>>()

    private suspend fun callback(start: ((String) -> Unit) -> Unit): JsonObject = withTimeout(180_000) {
        val text = suspendCancellableCoroutine { continuation ->
            start { if (continuation.isActive) continuation.resume(it) }
        }
        Json.parseToJsonElement(text).jsonObject.also {
            if (it.string("error").isNotBlank()) error(it.string("error"))
        }
    }

    private suspend fun authenticate() {
        token = callback(platform::authorizeDrive).string("accessToken")
        check(token.isNotBlank()) { "Google 로그인에 실패했어요." }
        val about = jsonRequest("GET", "https://www.googleapis.com/drive/v2/about?fields=user(permissionId,emailAddress)")
        account = about.getValue("user").jsonObject.string("permissionId")
        check(account.isNotBlank()) { "Google 계정을 확인하지 못했어요." }
        val previous = local.preference("drive_account")
        if (!previous.isNullOrBlank() && previous != account) {
            mirror?.reset()
            state = state.copy(folderId = "", folderName = "", entries = emptyList())
            local.setPreference("drive_folder", "")
            local.setPreference("drive_folder_name", "")
        }
        local.setPreference("drive_account", account)
        state = state.copy(connected = true)
    }

    private suspend fun request(method: String, url: String, body: String = "", type: String = "application/json", etag: String? = null): String {
        val response = callback { done -> platform.driveRequest(buildJsonObject {
            put("method", method); put("url", url); put("body", body)
            putJsonObject("headers") {
                put("Authorization", "Bearer $token"); put("Content-Type", type)
                etag?.let { put("If-Match", it) }
            }
        }.toString(), done) }
        val status = response["status"]?.jsonPrimitive?.intOrNull ?: 0
        if (status == 401) { token = ""; state = state.copy(connected = false) }
        check(status in 200..299) {
            when (status) {
                401 -> "Google 로그인이 만료됐어요. 다시 연결해 주세요."
                403 -> "Drive 접근 권한 또는 사용 한도를 확인해 주세요."
                404 -> "Drive 파일이나 폴더가 없거나 접근할 수 없어요."
                412 -> "동기화 중 다른 기기에서 수정했어요. 원본은 덮어쓰지 않았습니다. 다시 동기화해 주세요."
                429 -> "요청이 많아요. 잠시 후 다시 동기화해 주세요."
                else -> "Drive 요청을 완료하지 못했어요 (HTTP $status). 다시 시도해 주세요."
            }
        }
        return response.string("body")
    }

    private suspend fun jsonRequest(method: String, url: String, body: String = "") =
        Json.parseToJsonElement(request(method, url, body)).jsonObject

    private suspend fun perform(action: suspend () -> Unit) {
        if (!lock.tryLock()) return
        state = state.copy(busy = true, message = "처리 중…")
        try { action() }
        catch (cancelled: CancellationException) { state = state.copy(message = "중단됐어요. 저장된 자료는 유지됩니다."); throw cancelled }
        catch (error: Exception) { state = state.copy(message = error.message ?: "동기화하지 못했어요.") }
        finally { state = state.copy(busy = false); lock.unlock() }
    }

    suspend fun connect() = perform {
        authenticate(); history.clear(); browseInternal("root", "내 드라이브")
        state = state.copy(message = "연결됐어요. 동기화할 폴더를 선택해 주세요.")
    }
    suspend fun folderAction(action: String, path: String = "") = perform {
        val mirror = mirror ?: return@perform
        when(action) {
            "choose" -> {mirror.choose();state=state.copy(message="")}
            "open" -> {mirror.open(path);state=state.copy(message="")}
            "status" -> {mirror.restore();state=state.copy(message="")}
            "notes" -> {
                authenticate()
                require(state.folderId.isNotEmpty()) { "먼저 공유 폴더를 선택해 주세요." }
                mirror.includeExistingNotes=true
                mirror.run(account,state.folderId,token,true) { method,url,body,etag -> request(method,url,body,etag=etag) }
                state=state.copy(message=mirror.state.result)
            }
            "recordings" -> {
                authenticate()
                require(state.folderId.isNotEmpty()) { "먼저 공유 폴더를 선택해 주세요." }
                mirror.command("prepare", mapOf("key" to "${account}_${state.folderId}"))
                mirror.command("collectRecordings")
                mirror.run(account,state.folderId,token,true) { method,url,body,etag -> request(method,url,body,etag=etag) }
                state=state.copy(message=mirror.state.result)
            }
        }
    }
    suspend fun syncFolder(preview: Boolean = false) = perform {
        authenticate()
        val mirror=mirror ?: error("폴더 동기화를 지원하지 않는 버전입니다.")
        mirror.run(account,state.folderId,token,!preview) { method,url,body,etag -> request(method,url,body,etag=etag) }
        state=state.copy(message=mirror.state.result)
    }

    fun disconnect() {
        if (state.busy) return
        platform.disconnectDrive(); token = ""; history.clear()
        mirror?.reset()
        state = state.copy(connected = false, entries = emptyList(), message = "로그아웃했어요. 기기에 저장된 자료는 유지됩니다.")
    }

    private suspend fun list(parent: String): List<JsonObject> {
        require(validID.matches(parent))
        val result = mutableListOf<JsonObject>()
        var page = ""
        val seen = mutableSetOf<String>()
        do {
            val url = "$API?maxResults=1000&q=" + urlEncode("'$parent' in parents and trashed = false") +
                "&fields=" + urlEncode("nextPageToken,items($FIELDS)") + if (page.isEmpty()) "" else "&pageToken=${urlEncode(page)}"
            val data = jsonRequest("GET", url)
            result += data["items"]?.jsonArray.orEmpty().map { it.jsonObject }
            check(result.size <= 10_000) { "폴더의 파일이 너무 많아요. 더 작은 폴더를 선택해 주세요." }
            page = data.string("nextPageToken")
            check(page.isEmpty() || seen.add(page)) { "Drive 목록을 완전히 읽지 못했어요." }
        } while (page.isNotEmpty())
        return result
    }

    private suspend fun browseInternal(id: String, name: String) {
        val rows = list(id).map { DriveEntry(it.string("id"), it.string("title"), it.string("mimeType") == FOLDER) }
        state = state.copy(entries = rows.sortedWith(compareByDescending<DriveEntry> { it.folder }.thenBy { it.name }), browsingId = id, browsingName = name)
    }
    suspend fun browse(entry: DriveEntry) = perform {
        check(entry.folder); history += state.browsingId to state.browsingName
        browseInternal(entry.id, entry.name); state = state.copy(message = "")
    }
    suspend fun up() = perform {
        val parent = history.removeLastOrNull() ?: ("root" to "내 드라이브")
        browseInternal(parent.first, parent.second); state = state.copy(message = "")
    }
    fun selectCurrentFolder() {
        if (state.busy || state.browsingId == "root") return
        mirror?.reset()
        local.setPreference("drive_folder", state.browsingId)
        local.setPreference("drive_folder_name", state.browsingName)
        state = state.copy(folderId = state.browsingId, folderName = state.browsingName, message = if(mirror!=null) "공유 폴더를 선택했어요. ‘지금 동기화’로 자료를 주고받으세요." else "폴더를 연결했어요. ‘지금 동기화’를 누르면 기기의 노트·문제도 업로드됩니다.")
    }
    suspend fun createFolder(name: String) = perform {
        require(name.isNotBlank() && name.length <= 100) { "폴더 이름을 입력해 주세요." }
        jsonRequest("POST", API, buildJsonObject {
            put("title", name.trim()); put("mimeType", FOLDER)
            putJsonArray("parents") { add(buildJsonObject { put("id", state.browsingId) }) }
        }.toString())
        browseInternal(state.browsingId, state.browsingName); state = state.copy(message = "폴더를 만들었어요.")
    }

    private fun documents(kind: String): Map<String, String> {
        val data = Json.parseToJsonElement(local.readFiles(kind)).jsonObject
        check(data["error"] == null) { data.string("error") }
        return data.getValue("files").jsonArray.associate { it.jsonObject.let { row -> row.string("id") to row.string("content") } }
    }
    private fun canonical(kind: String, text: String, name: String): String = when (kind) {
        "notes" -> MarkdownNotes.read(text, name.substringBeforeLast('.')).let { MarkdownNotes.write(it.title, it.body) }
        else -> QuizFiles.write(QuizFiles.read(text))
    }
    private fun save(kind: String, id: String, text: String) {
        local.writeFile(kind, id, text)?.let { error(it) }
    }
    private fun title(kind: String, text: String) = if (kind == "notes") MarkdownNotes.read(text).title else QuizFiles.read(text).title
    private fun baselineKey() = "drive_baseline_${account}_${state.folderId}"
    private fun persist(bases: Map<String, JsonObject>) = local.setPreference(baselineKey(), JsonObject(bases).toString())
    private fun baseline(id: String, kind: String, content: String) = buildJsonObject { put("id", id); put("kind", kind); put("content", content) }

    private suspend fun upload(id: String, kind: String, text: String, suffix: String = ""): JsonObject {
        val name = title(kind, text).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70) + suffix + "--$id." + if (kind == "notes") "md" else "quiz.json"
        val metadata = buildJsonObject {
            put("title", name); put("mimeType", if (kind == "notes") "text/markdown" else "application/json")
            putJsonArray("parents") { add(buildJsonObject { put("id", state.folderId) }) }
            putJsonArray("properties") { add(buildJsonObject { put("key", "studyId"); put("value", id); put("visibility", "PUBLIC") }) }
        }
        val boundary = "study_${local.newId()}"
        val payload = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$text\r\n--$boundary--\r\n"
        return Json.parseToJsonElement(request("POST", "$UPLOAD?uploadType=multipart&fields=${urlEncode(FIELDS)}", payload, "multipart/related; boundary=$boundary")).jsonObject
    }

    suspend fun sync() = perform {
        authenticate()
        require(validID.matches(state.folderId)) { "먼저 동기화할 폴더를 선택해 주세요." }
        val folder = jsonRequest("GET", "$API/${state.folderId}?fields=mimeType,labels")
        check(folder.string("mimeType") == FOLDER && folder["labels"]?.jsonObject?.get("trashed")?.jsonPrimitive?.booleanOrNull != true) { "동기화할 폴더를 다시 선택해 주세요." }
        val bases = local.preference(baselineKey())?.let { text ->
            Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonObject }.toMutableMap()
        } ?: mutableMapOf()
        val remote = mutableListOf<Pair<JsonObject, String>>()
        val visited = mutableSetOf<String>()
        suspend fun walk(id: String, path: String, depth: Int) {
            check(depth <= 20 && visited.size < 1000) { "폴더 구조가 너무 커요. 과목 폴더를 선택해 주세요." }
            if (!visited.add(id)) return
            for (file in list(id)) {
                val name = file.string("title")
                if (file.string("mimeType") == FOLDER) walk(file.string("id"), "$path$name/", depth + 1)
                else if (name.endsWith(".md", true) || name.endsWith(".json", true)) remote += file to "$path$name"
            }
            check(remote.size <= 10_000) { "동기화 대상 파일이 너무 많아요." }
        }
        walk(state.folderId, "", 0) // Complete enumeration before making any changes.
        var downloaded = 0; var uploaded = 0; var conflicts = 0; var skipped = 0
        val claimed = mutableSetOf<String>()
        for ((listed, path) in remote) {
            val remoteID = listed.string("id")
            require(validID.matches(remoteID))
            val kind = if (listed.string("title").endsWith(".md", true)) "notes" else "question-sets"
            if ((listed.string("fileSize").toLongOrNull() ?: 0) > 400_400) { skipped++; continue }
            val meta = jsonRequest("GET", "$API/$remoteID?fields=${urlEncode(FIELDS)}")
            val etag = meta.string("etag")
            check(etag.isNotEmpty()) { "파일 버전을 확인하지 못했어요: $path" }
            val raw = request("GET", "$API/$remoteID?alt=media", etag = etag)
            val content = runCatching { canonical(kind, raw, meta.string("title")) }.getOrElse { skipped++; continue }
            val base = bases[remoteID]
            val suggested = meta["properties"]?.jsonArray?.firstOrNull { it.jsonObject.string("key") == "studyId" }?.jsonObject?.string("value")
            var id = base?.string("id") ?: suggested?.takeIf { uuid.matches(it) } ?: local.newId()
            // Duplicate properties (e.g. a Drive copy) must not alias two remote documents.
            if (!claimed.add("$kind/$id")) { id = local.newId(); claimed.add("$kind/$id") }
            val current = documents(kind)[id]?.let { canonical(kind, it, meta.string("title")) }
            when (syncDecision(base?.string("content"), current, content)) {
                SyncDecision.SAME -> Unit
                SyncDecision.DOWNLOAD -> { save(kind, id, content); downloaded++ }
                SyncDecision.UPLOAD -> {
                    request("PUT", "$UPLOAD/$remoteID?uploadType=media", current!!, "text/plain; charset=UTF-8", etag)
                    bases[remoteID] = baseline(id, kind, current); persist(bases); uploaded++; continue
                }
                SyncDecision.CONFLICT -> {
                    // Save local edits as a separate document BEFORE replacing the local original.
                    val copyID = local.newId()
                    val copy = if (kind == "notes") MarkdownNotes.read(current!!).let {
                        MarkdownNotes.write((it.title.take(48) + " · 충돌 사본"), it.body)
                    } else current!!
                    save(kind, copyID, copy)
                    save(kind, id, content); conflicts++; downloaded++
                }
            }
            bases[remoteID] = baseline(id, kind, content); persist(bases)
        }
        // Trashed/moved remote files keep their mapping: do not resurrect them or erase local copies.
        for (kind in listOf("notes", "question-sets")) {
            val mapped = bases.values.filter { it.string("kind") == kind }.map { it.string("id") }.toSet()
            for ((id, raw) in documents(kind)) {
                if (id in mapped) continue
                val text = canonical(kind, raw, id)
                val created = upload(id, kind, text)
                bases[created.string("id")] = baseline(id, kind, text); persist(bases); uploaded++
            }
        }
        val missing = bases.keys.count { id -> remote.none { it.first.string("id") == id } } - uploaded
        state = state.copy(message = "동기화 완료 · 받기 $downloaded · 보내기 $uploaded · 충돌 사본 $conflicts · 지원하지 않는 파일 $skipped" +
            if (missing > 0) " · 원격에서 사라진 자료는 기기에 보관합니다." else "")
    }
}
