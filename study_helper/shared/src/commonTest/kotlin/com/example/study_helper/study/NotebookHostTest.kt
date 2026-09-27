package com.example.study_helper.study

import com.example.study_helper.core.QuizDocument
import com.example.study_helper.core.QuizFiles
import com.example.study_helper.core.QuizItem
import kotlinx.serialization.json.*
import kotlin.test.*

class NotebookHostTest {
    private val fixture = QuizDocument("보안 복습", "", "보안 수업", (1..3).map {
        QuizItem("q$it", "질문 $it", listOf("정답 $it", "오답 A", "오답 B"), 0, "해설 $it", "원문 근거 $it")
    })

    @Test fun coldLaunchIsNotebookOnlyAndPlainMarkdownSurvivesReload() {
        val disk = MemoryPlatform()
        val host = NotebookHost(disk)
        assertTrue(host.notebook.ready)
        assertEquals(0, disk.starts)
        val markdown = "## 자유로운 필기\n\n**강조**와 표, 코드도 그대로 보관합니다."
        assertNull(host.importDocument("notes", "강의.md", markdown))
        val selected = host.notebook.id
        val reopened = NotebookHost(disk)
        assertEquals(selected, reopened.notebook.id)
        assertEquals(markdown, reopened.notebook.markdown)
        assertEquals(0, disk.starts)
    }

    @Test fun quizValidationIsAtomicAndRepeatedImportIsIdempotent() {
        val disk = MemoryPlatform()
        val host = NotebookHost(disk)
        assertNull(host.importDocument("question-sets", "valid.json", QuizFiles.write(fixture)))
        val original = disk.documents.toMap()
        val id = host.quizzes.single().id
        assertNotNull(host.importDocument("question-sets", "bad.json", "{\"schemaVersion\":1}"))
        assertEquals(original, disk.documents)
        assertEquals(id, host.quizzes.single().id)
        assertNull(host.importDocument("question-sets", "renamed.json", QuizFiles.write(fixture)))
        assertEquals(id, host.quizzes.single().id)
    }

    @Test fun writeFailurePreservesVisibleNoteAndCanBeRetried() {
        val disk = MemoryPlatform()
        val host = NotebookHost(disk)
        val before = host.notebook
        disk.writeError = "저장 공간이 부족합니다."
        assertNotNull(host.saveNote(before.id, "수정", "새 내용"))
        assertEquals(before.markdown, host.notebook.markdown)
        assertFalse(host.notebook.busy)
        disk.writeError = null
        assertNull(host.saveNote(before.id, "수정", "새 내용"))
        assertEquals("새 내용", NotebookHost(disk).notebook.markdown)
    }

    @Test fun nativeCloseGradesPrivateKeysPersistsRecapAndNextGameUsesNewFile() {
        val disk = MemoryPlatform()
        val host = NotebookHost(disk)
        host.importDocument("question-sets", "quiz.json", QuizFiles.write(fixture))
        host.startLearning(host.quizzes.single().id)
        assertEquals(1, disk.starts)
        val begin = call(host, "begin", "start")
        assertEquals("session", begin["type"]!!.jsonPrimitive.content)
        listOf("correctIndex", "sourceQuote", "해설", "원문 근거").forEach { assertFalse(begin.toString().contains(it)) }
        val session = begin["body"]!!.jsonObject
        val question = session["questions"]!!.jsonArray.first().jsonObject
        val original = fixture.questions.single { it.prompt == question["prompt"]!!.jsonPrimitive.content }
        val graded = call(host, "answer", "answer", buildJsonObject {
            put("sessionId", session["sessionId"]!!)
            put("questionId", question["id"]!!)
            put("selectedChoice", question["choices"]!!.jsonArray.indexOf(JsonPrimitive(original.choices[0])))
        })
        assertTrue(graded["body"]!!.jsonObject["correct"]!!.jsonPrimitive.boolean)
        host.finishGame()
        assertFalse(host.launching)
        assertEquals(1, host.recap!!.correct)
        assertEquals(1, host.recap!!.answered)
        assertEquals(host.recap, NotebookHost(disk).recap)
        host.finishGame() // UIKit dismissal and engine return may both attempt to finish.
        assertEquals(1, host.recap!!.answered)
        val next = fixture.copy(title = "두 번째 복습")
        host.importDocument("question-sets", "next.json", QuizFiles.write(next))
        host.startLearning(host.quizzes.single { it.document.title == next.title }.id)
        val second = call(host, "begin", "start")["body"]!!.jsonObject
        assertEquals(next.title, second["title"]!!.jsonPrimitive.content)
        assertNotEquals(session["sessionId"], second["sessionId"])
    }

    @Test fun beginWithoutSelectedFileCannotFallBackToSampleQuestions() {
        val host = NotebookHost(MemoryPlatform())
        assertEquals("error", call(host, "begin", "unexpected")["type"]!!.jsonPrimitive.content)
    }

    private fun call(host: NotebookHost, type: String, id: String, body: JsonObject = buildJsonObject {}): JsonObject =
        Json.parseToJsonElement(host.exchange(buildJsonObject {
            put("version", 1); put("type", type); put("requestId", id); put("body", body)
        }.toString())).jsonObject

    private class MemoryPlatform : NotebookPlatform {
        val documents = linkedMapOf<Pair<String, String>, String>()
        private val preferences = mutableMapOf<String, String>()
        private var counter = 0
        var starts = 0
        var writeError: String? = null
        override fun newId() = "id-${++counter}"
        override fun readFiles(kind: String) = buildJsonObject {
            putJsonArray("files") { documents.filterKeys { it.first == kind }.forEach { (key, content) ->
                add(buildJsonObject { put("id", key.second); put("content", content) })
            } }
        }.toString()
        override fun writeFile(kind: String, id: String, content: String): String? {
            if (writeError != null) return writeError
            documents[kind to id] = content
            return null
        }
        override fun preference(key: String) = preferences[key]
        override fun setPreference(key: String, value: String) { preferences[key] = value }
        override fun startGame() { starts++ }
        override fun pickDocument(kind: String) = Unit
        override fun exportDocument(filename: String, content: String) = Unit
        override fun copyText(text: String) = Unit
        override fun openLink(url: String) = Unit
    }
}
