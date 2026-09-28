package com.example.study_helper.desktop

import com.example.study_helper.core.*
import com.example.study_helper.sync.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Explicit PC folder sync; relative names never come from the network. Deleted sources stay in cache. */
class FolderLink(private val store: DesktopStore) {
    fun sync(folder: File): String {
        require(folder.isDirectory) { "연결된 폴더를 찾지 못했어요." }
        val root = folder.canonicalFile
        require(!root.toPath().startsWith(store.root.canonicalFile.toPath()) && !store.root.canonicalFile.toPath().startsWith(root.toPath())) { "앱 내부 저장소를 공부 폴더로 선택할 수 없어요." }
        val key = "folder_map_${root.path}"
        val mapping = store.preference(key)?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
        fun persist() = store.setPreference(key, JsonObject(mapping).toString())
        fun docs(kind: String): Map<String, String> {
            val data = Json.parseToJsonElement(store.readFiles(kind)).jsonObject
            check(data["error"] == null) { data["error"].toString() }
            return data.getValue("files").jsonArray.associate { it.jsonObject.let { row -> row.getValue("id").jsonPrimitive.content to row.getValue("content").jsonPrimitive.content } }
        }
        fun canonical(kind: String, content: String, name: String): String = if (kind == "notes") MarkdownNotes.read(content, name).let { MarkdownNotes.write(it.title, it.body) } else QuizFiles.write(QuizFiles.read(content))
        fun save(kind: String, id: String, content: String) { store.writeFile(kind, id, content)?.let { error(it) } }
        fun base(id: String, kind: String, content: String) = buildJsonObject { put("id", id); put("kind", kind); put("content", content) }
        var read = 0; var wrote = 0; var conflicts = 0; var skipped = 0
        val sources = Files.walk(root.toPath(), 20).use { stream -> stream.filter {
            Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                (it.toString().endsWith(".md", true) || it.toString().endsWith(".json", true)) &&
                root.toPath().relativize(it).none { part -> part.toString().startsWith('.') }
        }.limit(10_001).toList() }
        check(sources.size <= 10_000) { "파일이 너무 많아요. 과목 폴더를 선택해 주세요." }
        for (source in sources) {
            val file = source.toFile()
            val path = root.toPath().relativize(source).toString()
            val kind = if (file.extension.lowercase() == "md") "notes" else "question-sets"
            val raw = runCatching { DesktopStore.readText(file) }.getOrElse { skipped++; continue }
            val remote = runCatching { canonical(kind, raw, file.nameWithoutExtension) }.getOrElse { skipped++; continue }
            val previous = mapping[path]?.jsonObject
            val id = previous?.get("id")?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            val local = docs(kind)[id]?.let { canonical(kind, it, file.nameWithoutExtension) }
            var content = remote
            when (syncDecision(previous?.get("content")?.jsonPrimitive?.content, local, remote)) {
                SyncDecision.SAME -> Unit
                SyncDecision.DOWNLOAD -> { save(kind, id, remote); read++ }
                SyncDecision.UPLOAD -> {
                    // Refuse a changed source; retain a backup before replacing an external file.
                    check(DesktopStore.readText(file) == raw) { "다른 프로그램에서 수정했어요: $path. 다시 동기화해 주세요." }
                    val backup = File(store.root, "folder-backups/${UUID.randomUUID()}/${file.name}")
                    DesktopStore.atomicWrite(backup, raw)
                    DesktopStore.atomicWrite(file, local!!); content = local; wrote++
                }
                SyncDecision.CONFLICT -> {
                    val copy = if (kind == "notes") MarkdownNotes.read(local!!).let { MarkdownNotes.write(it.title.take(48) + " · 충돌 사본", it.body) } else local!!
                    save(kind, UUID.randomUUID().toString(), copy)
                    save(kind, id, remote); conflicts++; read++
                }
            }
            mapping[path] = base(id, kind, content); persist()
        }
        for (kind in listOf("notes", "question-sets")) {
            val mapped = mapping.values.filter { it.jsonObject["kind"]?.jsonPrimitive?.content == kind }.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            for ((id, content) in docs(kind)) if (id !in mapped) {
                val title = if (kind == "notes") MarkdownNotes.read(content).title else QuizFiles.read(content).title
                val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60) + "--$id." + if (kind == "notes") "md" else "quiz.json"
                val path = "Study Helper/$name"
                val target = File(root, path)
                require(target.canonicalFile.toPath().startsWith(root.toPath()))
                require(!target.exists() || DesktopStore.readText(target) == content) { "같은 이름의 파일이 이미 있어요: $path" }
                DesktopStore.atomicWrite(target, content)
                mapping[path] = base(id, kind, content); persist(); wrote++
            }
        }
        return "폴더 동기화 완료 · 받기 $read · 보내기 $wrote · 충돌 $conflicts · 제외 $skipped"
    }
}
