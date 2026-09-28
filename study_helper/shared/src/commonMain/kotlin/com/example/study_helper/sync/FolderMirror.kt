package com.example.study_helper.sync

import androidx.compose.runtime.*
import com.example.study_helper.study.NotebookPlatform
import com.example.study_helper.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Native folder operations and streamed transfers. No tokens or binary data enter preferences. */
interface FolderPlatform {
    fun folderCommand(request: String, completion: (String) -> Unit)
}

data class FolderChange(val path: String, val action: String, val detail: String)
data class MirrorState(val changes: List<FolderChange> = emptyList(), val files: List<String> = emptyList(),
    val label: String = "", val desktop: Boolean = false, val result: String = "", val finished: Boolean = false,
    val failed: Boolean = false)

enum class FileDecision { SAME, SEND, RECEIVE, DELETE_LOCAL, DELETE_REMOTE, CONFLICT }
fun fileDecision(base: String?, local: String?, remote: String?): FileDecision = when {
    local == remote -> FileDecision.SAME
    base == null -> when { local == null -> FileDecision.RECEIVE; remote == null -> FileDecision.SEND; else -> FileDecision.CONFLICT }
    local == base -> if (remote == null) FileDecision.DELETE_LOCAL else FileDecision.RECEIVE
    remote == base -> if (local == null) FileDecision.DELETE_REMOTE else FileDecision.SEND
    else -> FileDecision.CONFLICT
}

private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private const val FILES = "https://www.googleapis.com/drive/v2/files"
private const val MIME_FOLDER = "application/vnd.google-apps.folder"
private const val META = "id,title,mimeType,etag,md5Checksum,fileSize,labels"
val sharedExtensions = setOf("md", "json", "pdf", "png", "jpg", "jpeg", "svg", "gif", "webp", "m4a", "wav", "mp3", "aac", "flac", "txt")
fun validFolderPath(path: String): Boolean = path.isNotEmpty() && path.length <= 2000 &&
    path.split('/').all { it.isNotBlank() && !it.startsWith('.') && it.none { c -> c in "\\:*?\"<>|" || c.code < 32 } }

/** A folder, not the entire app library, is the unit of sync. Baselines record hashes only. */
class FolderMirror(private val local: NotebookPlatform, private val native: FolderPlatform) {
    var includeExistingNotes = false
    var state by mutableStateOf(MirrorState()); private set
    fun reset() { includeExistingNotes=false; state=MirrorState(label=state.label,desktop=state.desktop) }
    private fun historyKey(account:String,folder:String)="folder_last_${account}_${folder}_${if(state.desktop) state.label else "mobile"}"
    suspend fun command(action: String, fields: Map<String, String> = emptyMap()): JsonObject = withTimeout(600_000) {
        val response = suspendCancellableCoroutine<String> { continuation ->
            native.folderCommand(buildJsonObject { put("action", action); fields.forEach { (k,v) -> put(k,v) } }.toString()) {
                if (continuation.isActive) continuation.resume(it)
            }
        }
        Json.parseToJsonElement(response).jsonObject.also { check(it.s("error").isEmpty()) { it.s("error") } }
    }
    suspend fun status() {
        val result = command("status")
        state = state.copy(label = result.s("label"), desktop = result["desktop"]?.jsonPrimitive?.booleanOrNull == true)
    }
    suspend fun restore() {
        val account=local.preference("drive_account").orEmpty()
        val folder=local.preference("drive_folder").orEmpty()
        if(account.isNotEmpty() && folder.isNotEmpty()) {
            runCatching { command("prepare",mapOf("key" to "${account}_$folder")) }
            status()
            val saved=local.preference(historyKey(account,folder))
            if(saved!=null) runCatching {
                val data=Json.parseToJsonElement(saved).jsonObject
                state=state.copy(result=data.s("result"),finished=data["finished"]?.jsonPrimitive?.booleanOrNull ?: true,
                    failed=data["failed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    files=data.getValue("files").jsonArray.map {it.jsonPrimitive.content},
                    changes=data.getValue("changes").jsonArray.map {it.jsonObject.let {row -> FolderChange(row.s("path"),row.s("action"),row.s("detail"))}})
            }
        }
        status()
    }
    suspend fun choose() { command("choose"); reset(); restore() }
    suspend fun open(path: String = "") { command("open", mapOf("path" to path)) }
    fun openNote(path:String):Boolean {
        val account=local.preference("drive_account").orEmpty()
        val folder=local.preference("drive_folder").orEmpty()
        val scope="folder_mirror_${account}_${folder}_${if(state.desktop) state.label else "mobile"}"
        val bindings=local.preference("${scope}_notes")?.let {Json.parseToJsonElement(it).jsonObject} ?: return false
        val note=bindings[path]?.jsonObject ?: return false
        if(note.s("kind")!="notes") return false
        local.setPreference("selected_note",note.s("id")); return true
    }

    private fun docs(kind: String): Map<String,String> {
        val result = Json.parseToJsonElement(local.readFiles(kind)).jsonObject
        check(result.s("error").isEmpty()) { result.s("error") }
        return result.getValue("files").jsonArray.associate { it.jsonObject.s("id") to it.jsonObject.s("content") }
    }
    private fun canonical(kind: String, text: String, name: String): String = if (kind == "notes")
        MarkdownNotes.read(text, name.substringBeforeLast('.')).let { MarkdownNotes.write(it.title,it.body) }
        else QuizFiles.write(QuizFiles.read(text))

    suspend fun run(account: String, folder: String, token: String, apply: Boolean,
        api: suspend (String, String, String, String?) -> String) {
        state=state.copy(result="폴더와 변경사항을 확인 중…",finished=false,failed=false)
        try { runInternal(account,folder,token,apply,api) }
        catch(error: Exception) {
            val step=state.result
            state=state.copy(failed=true,finished=false,result="동기화 중단\n$step\n"+
                (if(error is CancellationException) "작업이 중단되었습니다." else error.message.orEmpty())+
                "\n완료된 파일은 유지됩니다. 다시 동기화하면 이어서 처리합니다.")
            if(state.label.isNotEmpty()) saveHistory(account,folder)
            throw error
        }
    }

    private suspend fun runInternal(account: String, folder: String, token: String, apply: Boolean,
        api: suspend (String, String, String, String?) -> String) {
        check(Regex("[A-Za-z0-9_-]+").matches(folder)) { "먼저 Drive 공유 폴더를 선택해 주세요." }
        command("prepare", mapOf("key" to "${account}_$folder"))
        status()
        check(state.label.isNotEmpty()) { "PC에서 올릴 공부 폴더를 선택해 주세요." }
        val scope = "folder_mirror_${account}_${folder}_${if(state.desktop) state.label else "mobile"}"
        val baseKey = "${scope}_hashes"
        val noteKey = "${scope}_notes"
        val bases = local.preference(baseKey)?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
        val bindings = local.preference(noteKey)?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
        fun saveBases() = local.setPreference(baseKey, JsonObject(bases).toString())
        fun saveBindings() = local.setPreference(noteKey, JsonObject(bindings).toString())
        val notes = mapOf("notes" to docs("notes"), "question-sets" to docs("question-sets"))
        // Adopt the old desktop folder map once; keep the user's edited library documents and IDs.
        val migratedKey="${scope}_legacy_adopted"
        if(state.desktop && local.preference(migratedKey)!="true") {
            val legacy=local.preference("folder_map_${state.label}")?.let {Json.parseToJsonElement(it).jsonObject}.orEmpty()
            for((oldPath,value) in legacy) {
                val path=oldPath.replace('\\','/'); val entry=value.jsonObject
                val kind=entry.s("kind"); val id=entry.s("id")
                if(!validFolderPath(path) || path in bindings || notes[kind]?.containsKey(id)!=true) continue
                val current=command("text",mapOf("path" to path))
                if(current.s("hash").isEmpty()) continue
                val raw=current.s("text")
                val same=runCatching {canonical(kind,raw,path)==entry.s("content")}.getOrDefault(false)
                bindings[path]=buildJsonObject {
                    put("id",id);put("kind",kind);put("content",entry.s("content"));put("raw",if(same) raw else entry.s("content"))
                }
            }
            saveBindings();local.setPreference(migratedKey,"true")
        }
        // Remember pre-existing unrelated library documents; future creations (including recordings' notes) join this folder.
        val knownKey = "${scope}_known"
        val known = local.preference(knownKey)?.let { Json.parseToJsonElement(it).jsonArray.map { v -> v.jsonPrimitive.content }.toMutableSet() }
            ?: notes.flatMap { (kind, items) -> items.keys.map { "$kind/$it" } }.toMutableSet()
        if(includeExistingNotes) {
            known.clear()
        }
        bindings.values.forEach { val b=it.jsonObject; known.add("${b.s("kind")}/${b.s("id")}") }
        for ((path, value) in bindings.toMap()) {
            val bind = value.jsonObject; val kind = bind.s("kind"); val id = bind.s("id")
            val current = notes[kind]?.get(id) ?: continue
            val text = canonical(kind,current,path)
            if (text != bind.s("content")) {
                val disk = command("text", mapOf("path" to path))
                if (disk.s("text") == bind.s("raw")) {
                    command("writeText", mapOf("path" to path,"text" to text,"expected" to disk.s("hash")))
                } else {
                    // Keep both versions when app edits collide with an external edit/deletion.
                    val copy = "모바일/노트/충돌-${local.newId()}." + if(kind=="notes") "md" else "quiz.json"
                    command("writeText", mapOf("path" to copy,"text" to text,"expected" to ""))
                }
                bindings[path] = JsonObject(bind + ("content" to JsonPrimitive(text))); saveBindings()
            }
        }
        for ((kind,items) in notes) for ((id,text) in items) {
            if (!known.add("$kind/$id")) continue
            val title = if(kind=="notes") MarkdownNotes.read(text).title else QuizFiles.read(text).title
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"),"_").take(50)
            val path = "모바일/" + (if(kind=="notes") "노트" else "문제") + "/$safe--$id." + if(kind=="notes") "md" else "quiz.json"
            val existing=command("text",mapOf("path" to path))
            if(existing.s("text")!=text || existing.s("hash").isEmpty()) {
                command("writeText",mapOf("path" to path,"text" to text,"expected" to ""))
            }
            bindings[path] = buildJsonObject { put("id",id); put("kind",kind); put("content",text); put("raw",text) }; saveBindings()
        }
        local.setPreference(knownKey, JsonArray(known.map(::JsonPrimitive)).toString())
        includeExistingNotes=false
        suspend fun scan() = command("scan").getValue("files").jsonArray.associate { it.jsonObject.s("path") to it.jsonObject }
        val disk = scan()
        val remote = mutableMapOf<String,JsonObject>()
        val folders = mutableMapOf("" to folder)
        val visited = mutableSetOf<String>()
        suspend fun walk(id: String, prefix: String, depth: Int) {
            check(depth <= 20 && visited.add(id) && visited.size <= 1000) { "Drive 폴더 구조를 확인해 주세요." }
            var page = ""; val pages = mutableSetOf<String>()
            do {
                val url = "$FILES?maxResults=1000&q="+urlEncode("'$id' in parents and trashed = false")+"&fields="+urlEncode("nextPageToken,items($META)")+(if(page.isEmpty()) "" else "&pageToken=${urlEncode(page)}")
                val response = Json.parseToJsonElement(api("GET",url,"",null)).jsonObject
                for (item in response["items"]?.jsonArray.orEmpty()) {
                    val row=item.jsonObject; val name=row.s("title"); val path=prefix+name
                    check(validFolderPath(path)) { "공유할 수 없는 파일 이름입니다: $path" }
                    if(row.s("mimeType")==MIME_FOLDER) {
                        check(!folders.containsKey(path)) { "같은 이름의 Drive 폴더가 있습니다: $path" }
                        folders[path]=row.s("id"); walk(row.s("id"),"$path/",depth+1)
                    } else if(name.substringAfterLast('.').lowercase() in sharedExtensions) {
                        check(!remote.containsKey(path)) { "같은 이름의 Drive 파일이 있습니다: $path" }
                        check(row.s("md5Checksum").isNotEmpty() && row.s("etag").isNotEmpty()) { "파일 버전을 확인하지 못했어요: $path" }
                        remote[path]=row
                    }
                    check(remote.size<=10000) { "파일이 너무 많습니다." }
                }
                page=response.s("nextPageToken"); check(page.isEmpty() || pages.add(page)) { "Drive 목록이 완전하지 않습니다." }
            } while(page.isNotEmpty())
        }
        val folderMeta=Json.parseToJsonElement(api("GET","$FILES/$folder?fields=mimeType,labels","",null)).jsonObject
        check(folderMeta.s("mimeType")==MIME_FOLDER && folderMeta["labels"]?.jsonObject?.get("trashed")?.jsonPrimitive?.booleanOrNull!=true) { "공유 폴더가 삭제되었거나 이동했습니다. 다시 선택해 주세요." }
        walk(folder,"",0)
        val all=(disk.keys+remote.keys+bases.keys).sorted()
        check(all.map { it.lowercase() }.distinct().size==all.size) { "대소문자만 다른 파일 이름은 먼저 정리해 주세요." }
        val decisions=all.associateWith { path -> fileDecision(bases[path]?.jsonObject?.s("hash"),disk[path]?.s("hash"),remote[path]?.s("md5Checksum")) }
        val changes=all.mapNotNull { path ->
            val decision=decisions.getValue(path)
            if(decision==FileDecision.SAME) null else FolderChange(path,when(decision) {
                FileDecision.SEND -> if(remote[path]==null) "추가" else "수정"
                FileDecision.RECEIVE -> if(disk[path]==null) "추가" else "수정"
                FileDecision.DELETE_LOCAL,FileDecision.DELETE_REMOTE -> "삭제"
                else -> "충돌"
            },when(decision) { FileDecision.SEND -> "이 기기 → Drive"; FileDecision.RECEIVE -> "Drive → 이 기기"
                FileDecision.DELETE_LOCAL -> "이 기기에서 보관함으로 이동"; FileDecision.DELETE_REMOTE -> "Drive 휴지통으로 이동"; else -> "양쪽 변경 · 원본 유지" })
        }
        state=state.copy(changes=changes,files=(disk.keys+remote.keys).sorted(),finished=false,result=if(changes.isEmpty()) "최신 상태입니다." else "${changes.size}개 변경사항")
        if(!apply) return
        suspend fun parent(path:String):String {
            val parts=path.split('/').dropLast(1); var key=""; var id=folder
            for(part in parts) {
                key=if(key.isEmpty()) part else "$key/$part"
                id=folders[key] ?: Json.parseToJsonElement(api("POST",FILES,buildJsonObject {
                    put("title",part); put("mimeType",MIME_FOLDER); putJsonArray("parents") { add(buildJsonObject { put("id",id) }) }
                }.toString(),null)).jsonObject.s("id").also { folders[key]=it }
            }
            return id
        }
        var completed=0
        for(path in all) {
            state=state.copy(result="동기화 처리 ${completed}개 완료 · $path")
            val localHash=disk[path]?.s("hash"); val row=remote[path]
            val remoteHash=row?.s("md5Checksum"); val etag=row?.s("etag")
            val decision=decisions.getValue(path)
            if(decision==FileDecision.CONFLICT) continue
            var hash=localHash ?: remoteHash
            when(decision) {
                FileDecision.SEND -> {
                    val parentId=parent(path)
                    val metadata=buildJsonObject { put("title",path.substringAfterLast('/')); putJsonArray("parents") { add(buildJsonObject { put("id",parentId) }) } }
                    val result=command("upload",mapOf("path" to path,"token" to token,"expected" to localHash.orEmpty(),"id" to row?.s("id").orEmpty(),"etag" to etag.orEmpty(),"metadata" to metadata.toString()))
                    check(result.s("md5Checksum")==localHash) { "전송 중 파일이 변경됐어요: $path" }
                    hash=localHash; completed++
                }
                FileDecision.RECEIVE -> {
                    command("download",mapOf("path" to path,"token" to token,"expected" to localHash.orEmpty(),"id" to row!!.s("id"),"etag" to etag.orEmpty(),"hash" to remoteHash.orEmpty()))
                    hash=remoteHash; completed++
                }
                FileDecision.DELETE_LOCAL -> { command("archive",mapOf("path" to path,"expected" to localHash.orEmpty())); hash=null; completed++ }
                FileDecision.DELETE_REMOTE -> {
                    command("assert",mapOf("path" to path,"expected" to ""))
                    api("POST","$FILES/${row!!.s("id")}/trash","",etag); hash=null; completed++
                }
                else -> Unit
            }
            if(hash==null) bases.remove(path) else bases[path]=buildJsonObject { put("hash",hash) }
            saveBases()
        }
        // Refresh the app library from the folder; binary files remain available via the folder browser.
        val finalFiles=scan()
        for((path,row) in finalFiles) {
            if(decisions[path]==FileDecision.CONFLICT || path.substringAfterLast('.').lowercase() !in setOf("md","json") || row.s("size").toLong()>400400) continue
            val kind=if(path.endsWith(".md",true)) "notes" else "question-sets"
            val raw=command("text",mapOf("path" to path)).s("text")
            val text=runCatching { canonical(kind,raw,path.substringAfterLast('/')) }.getOrNull() ?: continue
            val claimed=bindings.values.map {it.jsonObject.s("id")}.toSet()
            val matching=notes[kind]?.entries?.firstOrNull {it.key !in claimed && runCatching {canonical(kind,it.value,path)==text}.getOrDefault(false)}?.key
            val id=bindings[path]?.jsonObject?.s("id") ?: matching ?: local.newId()
            local.writeFile(kind,id,text)?.let { error(it) }
            bindings[path]=buildJsonObject { put("id",id); put("kind",kind); put("content",text); put("raw",raw) }
            known.add("$kind/$id"); saveBindings()
        }
        for(path in bindings.keys.toList()) if(path !in finalFiles && decisions[path]!=FileDecision.CONFLICT) {
            val bind=bindings.getValue(path).jsonObject
            command("archiveNote",mapOf("kind" to bind.s("kind"),"id" to bind.s("id")))
            bindings.remove(path); saveBindings()
        }
        local.setPreference(knownKey,JsonArray(known.map(::JsonPrimitive)).toString())
        val conflicts=decisions.values.count { it==FileDecision.CONFLICT }
        state=state.copy(files=finalFiles.keys.sorted(),finished=true,result="${completed}개 반영 완료"+if(conflicts>0) " · 충돌 ${conflicts}개는 양쪽 원본을 유지했습니다." else " · 최신 상태입니다.")
        saveHistory(account,folder)
    }
    private fun saveHistory(account:String,folder:String) {
        local.setPreference(historyKey(account,folder),buildJsonObject {
            put("result",state.result)
            put("finished",state.finished); put("failed",state.failed)
            putJsonArray("files") {state.files.forEach {add(JsonPrimitive(it))}}
            putJsonArray("changes") {state.changes.forEach {change -> add(buildJsonObject {put("path",change.path);put("action",change.action);put("detail",change.detail)})}}
        }.toString())
    }
}
