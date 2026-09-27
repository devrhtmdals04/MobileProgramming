package com.example.study_helper.core

import kotlinx.serialization.json.*
import kotlin.test.*

class QuizFilesTest {
    private val fixture = QuizDocument("보안 복습", "note-1", "보안 수업", (1..5).map {
        QuizItem("item-$it", "질문 $it", listOf("정답 $it", "오답 A", "오답 B"), 0, "해설 $it", "원문 근거 $it")
    })
    private val json get() = QuizFiles.write(fixture)

    @Test fun fileRoundTripAndPromptPreserveNoteData() {
        assertEquals(fixture, QuizFiles.read(json))
        val prompt = QuizFiles.prompt("note-1", "보안 수업", "# 자유 문단\n코드와 표도 허용")
        assertTrue(prompt.contains("correctIndex"))
        val source = Json.parseToJsonElement(prompt.substringAfter("학습 노트 데이터:\n")).jsonObject
        assertEquals("# 자유 문단\n코드와 표도 허용", source["markdown"]!!.jsonPrimitive.content)
        assertEquals("note-1", source["sourceNoteId"]!!.jsonPrimitive.content)
    }

    @Test fun invalidFilesFailWithoutInventingQuestions() {
        listOf("{}", "[]", "```json\n$json\n```", "{", json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            json.replace("\"correctIndex\":0", "\"correctIndex\":3"), json.replace("\"correctIndex\":0", "\"correctIndex\":\"0\""),
            json.replace("\"sourceQuote\":\"원문 근거 1\"", "\"sourceQuote\":\"\""),
            json.replace("\"오답 B\"", "\"오답 A\""), "x".repeat(100_001)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { QuizFiles.read(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { QuizFiles.write(fixture.copy(questions = fixture.questions.take(2))) }
        assertFailsWith<IllegalArgumentException> { QuizFiles.write(fixture.copy(questions = listOf(fixture.questions[0], fixture.questions[0], fixture.questions[2]))) }
    }

    @Test fun importedQuestionsShuffleAndGradeWithPrivateKeys() {
        repeat(20) {
            val service = StudyService()
            fun call(type: String, id: String, body: JsonObject): JsonObject = Json.parseToJsonElement(service.exchange(buildJsonObject {
                put("version", 1); put("requestId", id); put("type", type); put("body", body)
            }.toString())).jsonObject
            val response = call("begin", "start", buildJsonObject { put("questionSet", Json.parseToJsonElement(json)) })
            assertEquals("session", response["type"]!!.jsonPrimitive.content)
            assertFalse(response.toString().contains("correctIndex"))
            assertFalse(response.toString().contains("correctChoice"))
            assertFalse(response.toString().contains("sourceQuote"))
            assertFalse(response.toString().contains("해설"))
            val session = response["body"]!!.jsonObject
            assertEquals(fixture.title, session["title"]!!.jsonPrimitive.content)
            val questions = session["questions"]!!.jsonArray
            assertEquals(3, questions.size)
            assertEquals(3, questions.map { it.jsonObject["prompt"] }.distinct().size)
            questions.forEachIndexed { index, value ->
                val q = value.jsonObject
                val original = fixture.questions.single { it.prompt == q["prompt"]!!.jsonPrimitive.content }
                val result = call("answer", "answer-$index", buildJsonObject {
                    put("sessionId", session["sessionId"]!!); put("questionId", q["id"]!!)
                    put("selectedChoice", q["choices"]!!.jsonArray.indexOf(JsonPrimitive(original.choices[0])))
                })["body"]!!.jsonObject
                assertTrue(result["correct"]!!.jsonPrimitive.boolean)
                assertEquals(original.explanation, result["explanation"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun invalidQuestionSetDoesNotReplaceCurrentSessionOrFallBackToSamples() {
        val service = StudyService()
        fun call(id: String, body: String) = Json.parseToJsonElement(service.exchange("""{"version":1,"requestId":"$id","type":"begin","body":$body}""")).jsonObject
        val active = call("valid", "{\"questionSet\":$json}")["body"]!!.jsonObject
        assertEquals("error", call("invalid", "{\"questionSet\":{}}")["type"]!!.jsonPrimitive.content)
        val ended = Json.parseToJsonElement(service.exchange(buildJsonObject {
            put("version", 1); put("requestId", "end"); put("type", "end")
            putJsonObject("body") { put("sessionId", active["sessionId"]!!) }
        }.toString())).jsonObject
        assertEquals("summary", ended["type"]!!.jsonPrimitive.content)
    }
}
