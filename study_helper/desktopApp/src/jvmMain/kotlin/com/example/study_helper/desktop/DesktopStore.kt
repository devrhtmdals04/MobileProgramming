package com.example.study_helper.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Application-owned documents. A linked directory is imported explicitly, never silently moved. */
class DesktopStore(val root: File) {
    init { check(root.isDirectory || root.mkdirs()) { "저장 폴더를 만들 수 없어요: $root" } }
    private val preferenceFile = File(root, "preferences.json")
    private val preferences = if (preferenceFile.exists()) Json.parseToJsonElement(preferenceFile.readText()).jsonObject.toMutableMap() else mutableMapOf()
    @Synchronized fun preference(key: String) = preferences[key]?.jsonPrimitive?.contentOrNull
    @Synchronized fun setPreference(key: String, value: String) {
        val next = preferences.toMutableMap().apply { put(key, JsonPrimitive(value)) }
        atomicWrite(preferenceFile, JsonObject(next).toString())
        preferences.clear(); preferences.putAll(next)
    }
    private fun directory(kind: String): File {
        require(kind in listOf("notes", "question-sets"))
        return File(root, kind).apply { check(isDirectory || mkdirs()) }
    }
    private fun file(kind: String, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory(kind), id + if (kind == "notes") ".md" else ".json")
    }
    @Synchronized fun readFiles(kind: String): String = runCatching {
        buildJsonObject { putJsonArray("files") {
            directory(kind).listFiles().orEmpty().filter { it.isFile && it.extension == if (kind == "notes") "md" else "json" }
                .sortedByDescending { it.lastModified() }.forEach { source ->
                    val id = source.nameWithoutExtension
                    file(kind, id)
                    add(buildJsonObject { put("id", id); put("content", readText(source)) })
                }
        } }.toString()
    }.getOrElse { buildJsonObject { put("error", it.message ?: "자료를 읽지 못했어요.") }.toString() }
    @Synchronized fun writeFile(kind: String, id: String, content: String): String? = runCatching { atomicWrite(file(kind, id), content) }.exceptionOrNull()?.message
    companion object {
        fun atomicWrite(target: File, content: String) {
            target.parentFile.mkdirs()
            val temp = Files.createTempFile(target.parentFile.toPath(), ".study-", ".tmp")
            try {
                Files.writeString(temp, content)
                try { Files.move(temp, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target.toPath(), REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temp) }
        }
        fun readText(file: File): String {
            val data = file.inputStream().use { it.readNBytes(400_401) }
            require(data.size <= 400_400) { "파일이 너무 커요: ${file.name}" }
            return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
        }
    }
}
