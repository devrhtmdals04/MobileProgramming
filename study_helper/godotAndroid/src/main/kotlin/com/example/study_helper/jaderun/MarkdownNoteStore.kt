package com.example.study_helper.jaderun

import android.content.Context
import android.util.AtomicFile
import com.example.study_helper.core.MarkdownNotes
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class StoredNote(val id: String, val title: String, val body: String)

/** One UTF-8 .md file per note; file names are generated IDs, never imported paths. */
internal class MarkdownNoteStore(context: Context) {
    private val directory = File(context.filesDir, "notes").apply {
        if (!isDirectory && !mkdirs()) throw IOException("노트 저장 공간을 열 수 없습니다.")
    }

    fun list(): List<StoredNote> = directory.listFiles { file -> file.name.removeSuffix(".bak").endsWith(".md") }
        ?.sortedByDescending { it.lastModified() }
        // Older Android AtomicFile versions may leave only the .bak during an interrupted write.
        ?.map { it.name.removeSuffix(".bak").removeSuffix(".md") }?.distinct()?.map(::read)
        ?: throw IOException("노트 목록을 읽지 못했습니다.")

    fun read(id: String): StoredNote {
        val document = AtomicFile(file(id)).readFully().toString(Charsets.UTF_8)
        val note = MarkdownNotes.read(document)
        return StoredNote(id, note.title, note.body)
    }

    fun save(id: String?, title: String, body: String): StoredNote {
        val content = MarkdownNotes.write(title, body)
        val noteId = id ?: UUID.randomUUID().toString()
        val target = AtomicFile(file(noteId))
        val stream = target.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
        return StoredNote(noteId, title.trim(), body)
    }

    private fun file(id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "올바르지 않은 노트 ID입니다." }
        return File(directory, "$id.md")
    }
}
