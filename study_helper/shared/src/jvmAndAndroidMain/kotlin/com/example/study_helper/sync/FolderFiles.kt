package com.example.study_helper.sync

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.json.*

/** Bounded-memory transfers; hashes and conditional writes prevent overwriting concurrent edits. */
class FolderFiles(private val appRoot: File, private val desktop: Boolean,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val selected: () -> File?) {
    private var key = ""
    private fun JsonObject.s(k:String)=this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun root():File = (if(desktop) selected() else key.takeIf { it.isNotEmpty() }?.let { File(appRoot,"shared-folders/$it") })
        ?: error("먼저 공부 폴더를 선택해 주세요.")
    private fun file(path:String):File {
        require(validFolderPath(path)) { "올바르지 않은 파일 경로입니다." }
        val root=root().canonicalFile
        var current=root
        for(part in path.split('/')) {
            current=File(current,part)
            require(!Files.isSymbolicLink(current.toPath())) { "심볼릭 링크는 공유하지 않습니다." }
        }
        require(current.canonicalPath.startsWith(root.path+File.separator))
        return current
    }
    fun hash(file:File):String = file.inputStream().use { input ->
        val digest=MessageDigest.getInstance("MD5"); val buffer=ByteArray(65536)
        while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun checkExpected(file:File, expected:String) {
        check((if(file.isFile) hash(file) else "")==expected) { "파일이 다른 곳에서 변경됐어요: ${file.name}. 다시 확인해 주세요." }
    }
    private fun backup(file:File) {
        if(file.exists()) {
            val destination=File(appRoot,"folder-history/${UUID.randomUUID()}/${file.name}")
            destination.parentFile.mkdirs(); file.copyTo(destination)
        }
    }
    private fun replace(temp:File,target:File,expected:String) {
        checkExpected(target,expected); backup(target)
        try { Files.move(temp.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING) }
        catch(_:java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(),target.toPath(),REPLACE_EXISTING) }
    }
    private fun connection(url:String,input:JsonObject,method:String,metadataCondition:Boolean=true):HttpURLConnection {
        val result=openConnection(URL(url))
        result.instanceFollowRedirects=false; result.connectTimeout=20000; result.readTimeout=180000
        result.requestMethod=method; result.setRequestProperty("Authorization","Bearer ${input.s("token")}")
        if(metadataCondition && input.s("etag").isNotEmpty()) result.setRequestProperty("If-Match",input.s("etag"))
        return result
    }
    private fun checkResponse(c:HttpURLConnection) {
        check(c.responseCode in 200..299) { if(c.responseCode==412) "Drive에서 파일이 변경됐어요. 다시 확인해 주세요." else "Drive 전송 실패 (HTTP ${c.responseCode})" }
    }
    fun execute(input:JsonObject):JsonObject {
        val action=input.s("action")
        if(action=="prepare") {
            require(Regex("[A-Za-z0-9_-]+").matches(input.s("key")))
            key=input.s("key")
            if(!desktop) root().mkdirs()
        }
        if(action in listOf("prepare","status")) return buildJsonObject {
            put("desktop",desktop); put("label",runCatching { root().canonicalPath }.getOrDefault(""))
        }
        val root=root(); require(root.isDirectory && root.canRead() && !Files.isSymbolicLink(root.toPath())) { "공부 폴더를 읽을 수 없어요." }
        return when(action) {
            "assert" -> { checkExpected(file(input.s("path")),input.s("expected")); buildJsonObject {} }
            "scan" -> buildJsonObject {
                val paths=mutableSetOf<String>(); var count=0
                putJsonArray("files") {
                    root.walkTopDown().onFail { _,error -> throw error }.onEnter {
                        require(it.relativeTo(root).invariantSeparatorsPath.split('/').size<=21) { "폴더가 너무 깊습니다." }
                        !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath())
                    }.filter {
                        it.isFile && !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith('.') && it.extension.lowercase() in sharedExtensions
                    }.forEach { f ->
                        val path=Normalizer.normalize(f.relativeTo(root).invariantSeparatorsPath,Normalizer.Form.NFC)
                        require(validFolderPath(path) && paths.add(path.lowercase())) { "중복되거나 지원하지 않는 경로: $path" }
                        check(++count<=10000) { "파일이 너무 많습니다." }
                        add(buildJsonObject { put("path",path); put("hash",hash(f)); put("size",f.length()) })
                    }
                }
            }
            "text" -> {
                val f=file(input.s("path")); require(!f.exists() || f.length()<=400400)
                buildJsonObject { put("text",if(f.exists()) f.readText() else ""); put("hash",if(f.exists()) hash(f) else "") }
            }
            "writeText" -> {
                val f=file(input.s("path")); f.parentFile.mkdirs()
                val temp=File.createTempFile(".sync-",".tmp",f.parentFile)
                try { temp.writeText(input.s("text")); replace(temp,f,input.s("expected")) } finally { temp.delete() }
                buildJsonObject {}
            }
            "archive" -> {
                val f=file(input.s("path")); checkExpected(f,input.s("expected")); backup(f)
                check(!f.exists() || f.delete()); buildJsonObject {}
            }
            "archiveNote" -> {
                val kind=input.s("kind"); val id=input.s("id")
                require(kind in listOf("notes","question-sets") && UUID.fromString(id).toString()==id)
                val f=File(appRoot,"$kind/$id."+if(kind=="notes") "md" else "json")
                backup(f); check(!f.exists() || f.delete()); buildJsonObject {}
            }
            "upload" -> {
                val f=file(input.s("path")); checkExpected(f,input.s("expected"))
                val id=input.s("id"); require(id.isEmpty() || Regex("[A-Za-z0-9_-]+").matches(id))
                val url="https://www.googleapis.com/upload/drive/v2/files"+(if(id.isEmpty()) "" else "/$id")+"?uploadType=resumable&fields=id,md5Checksum"
                val init=connection(url,input,if(id.isEmpty()) "POST" else "PUT")
                val session=try {
                    init.doOutput=true; init.setRequestProperty("Content-Type","application/json; charset=UTF-8")
                    init.setRequestProperty("X-Upload-Content-Type","application/octet-stream")
                    init.setRequestProperty("X-Upload-Content-Length",f.length().toString())
                    init.outputStream.use {it.write((if(id.isEmpty()) input.s("metadata") else "{}").toByteArray())}
                    checkResponse(init)
                    init.getHeaderField("Location") ?: error("Drive 업로드 세션이 없습니다.")
                } finally {init.disconnect()}
                val sessionUrl=URL(session)
                require(sessionUrl.protocol=="https" && sessionUrl.host=="www.googleapis.com")
                val c=connection(session,input,"PUT")
                try {
                    c.doOutput=true; c.setFixedLengthStreamingMode(f.length())
                    c.setRequestProperty("Content-Type","application/octet-stream")
                    c.outputStream.use { output ->
                        f.inputStream().use { it.copyTo(output,65536) }
                    }
                    checkResponse(c)
                    Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
                } finally { c.disconnect() }
            }
            "download" -> {
                val f=file(input.s("path")); checkExpected(f,input.s("expected")); f.parentFile.mkdirs()
                val id=input.s("id"); require(Regex("[A-Za-z0-9_-]+").matches(id))
                val temp=File.createTempFile(".sync-",".tmp",f.parentFile)
                // Metadata and media are different HTTP representations. Validate the downloaded
                // bytes against the listed checksum before replacing any local file instead.
                val c=connection("https://www.googleapis.com/drive/v2/files/$id?alt=media",input,"GET",metadataCondition=false)
                try {
                    checkResponse(c)
                    c.inputStream.use { stream -> temp.outputStream().use { stream.copyTo(it,65536) } }
                    check(hash(temp)==input.s("hash")) { "다운로드한 파일의 내용이 변경됐어요." }
                    replace(temp,f,input.s("expected")); buildJsonObject {}
                } finally { c.disconnect(); temp.delete() }
            }
            "collectRecordings" -> {
                val recordings=File(appRoot,"recordings"); var copied=0
                for(source in recordings.listFiles().orEmpty().filter { it.extension in listOf("m4a","wav") }) {
                    val title=File(recordings,"${source.nameWithoutExtension}.title").takeIf {it.exists()}?.readText()
                        ?: File(recordings,"${source.nameWithoutExtension}.json").takeIf {it.exists()}?.let {runCatching {Json.parseToJsonElement(it.readText()).jsonObject.s("title")}.getOrNull()}
                    val name=title?.takeIf {it.isNotBlank()}?.replace(Regex("[\\\\/:*?\"<>|]"),"_")?.take(60)?.let {"$it--${source.name}"} ?: source.name
                    val path="녹음/$name"
                    val marker=File(appRoot,"recording-exports/$key/${source.name}.hash")
                    val fingerprint=hash(source)
                    if(marker.takeIf { it.exists() }?.readText()==fingerprint) continue
                    val target=file(path)
                    check(!target.exists() || hash(target)==fingerprint) { "같은 녹음 이름이 존재합니다: $path" }
                    target.parentFile.mkdirs(); if(!target.exists()) source.copyTo(target)
                    marker.parentFile.mkdirs(); marker.writeText(fingerprint); copied++
                }
                buildJsonObject { put("copied",copied) }
            }
            else -> error("지원하지 않는 폴더 작업입니다: $action")
        }
    }
    fun resolve(path:String)=if(path.isEmpty()) root() else file(path)
}
