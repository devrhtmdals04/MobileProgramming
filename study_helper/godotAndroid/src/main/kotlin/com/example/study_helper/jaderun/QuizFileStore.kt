package com.example.study_helper.jaderun

import android.content.Context
import android.util.AtomicFile
import com.example.study_helper.core.QuizDocument
import com.example.study_helper.core.QuizFiles
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class StoredQuiz(val id: String, val document: QuizDocument)

internal class QuizFileStore(context: Context) {
    private val directory = File(context.filesDir, "question-sets").apply {
        if (!isDirectory && !mkdirs()) throw IOException("문제 저장 공간을 열 수 없습니다.")
    }

    fun list(): List<StoredQuiz> = directory.listFiles { it.name.removeSuffix(".bak").endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.name.removeSuffix(".bak").removeSuffix(".json") }?.distinct()?.map(::read)
        ?: throw IOException("문제 목록을 읽지 못했습니다.")

    fun read(id: String): StoredQuiz = StoredQuiz(id, QuizFiles.read(AtomicFile(file(id)).readFully().toString(Charsets.UTF_8)))

    fun import(document: String): StoredQuiz {
        val parsed = QuizFiles.read(document)
        val canonical = QuizFiles.write(parsed)
        // Reimporting an unchanged file must not duplicate the question set.
        list().firstOrNull { QuizFiles.write(it.document) == canonical }?.let { return it }
        val id = UUID.randomUUID().toString()
        val target = AtomicFile(file(id))
        val stream = target.startWrite()
        try {
            stream.write(canonical.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
        return StoredQuiz(id, parsed)
    }

    private fun file(id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "올바르지 않은 문제 ID입니다." }
        return File(directory, "$id.json")
    }
}
