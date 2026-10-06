package com.example.study_helper.core

import kotlinx.serialization.json.*
import kotlin.test.*

class QuestionTypesTest {
    private val concept = QuizItem("concept", "개념 질문", listOf("정답", "오답1", "오답2"), 0, "해설", "근거")
    private val calculation = concept.copy(id = "calc", prompt = "계산 질문", type = QuestionType.CALCULATION,
        numberVariant = "bjt_beta")
    private val written = QuizItem("written", "이유를 설명하세요", emptyList(), -1, "해설", "근거",
        type = QuestionType.WRITTEN, modelAnswer = "근거에 따른 모범 답안", rubric = listOf("원인 설명", "결과 설명"))
    private val mixed = QuizDocument("유형별 복습", "", "노트", listOf(concept, written, calculation))

    @Test fun mixedTypesRoundTripAndLegacyDefaultsRemainReadable() {
        val json = QuizFiles.write(mixed)
        assertTrue(json.contains("\"schemaVersion\":2"))
        assertEquals(mixed, QuizFiles.read(json))
        val legacy = mixed.copy(questions = listOf(concept, calculation, concept.copy(id="other", prompt="다른 질문")))
        val old = QuizFiles.write(legacy).replace("\"type\":\"concept\",", "").replace("\"type\":\"calculation\",", "")
        assertEquals(legacy, QuizFiles.read(old))
    }

    @Test fun invalidTypeAndAnswerCombinationsAreRejected() {
        val valid = QuizFiles.write(mixed)
        listOf(valid.replace("\"written\"", "\"essay_unknown\""),
            valid.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            valid.replace("\"modelAnswer\":\"근거에 따른 모범 답안\"", "\"modelAnswer\":\"\""),
            valid.replace("\"rubric\":[\"원인 설명\",\"결과 설명\"]", "\"rubric\":[]"),
            valid.replace("\"type\":\"calculation\"", "\"type\":\"concept\""),
            valid.replace("\"type\":\"written\"", "\"type\":\"written\",\"correctIndex\":0"),
            valid.replace("\"type\":\"concept\",", "")
        ).forEach { assertFailsWith<IllegalArgumentException> { QuizFiles.read(it) } }
    }

    private fun call(service: StudyService, id: String, doc: QuizDocument): JsonObject =
        Json.parseToJsonElement(service.exchange(buildJsonObject {
            put("version", 1); put("requestId", id); put("type", "begin")
            putJsonObject("body") { put("questionSet", Json.parseToJsonElement(QuizFiles.write(doc))); put("continuous", true) }
        }.toString())).jsonObject

    @Test fun gameFiltersWrittenBeforeAssigningNumericTemplates() {
        val response = call(StudyService(), "mixed", mixed)
        assertEquals("session", response.getValue("type").jsonPrimitive.content)
        val questions = response.getValue("body").jsonObject.getValue("questions").jsonArray
        assertEquals(2, questions.size)
        assertTrue(questions.any { "IB =" in it.jsonObject.getValue("prompt").jsonPrimitive.content })
        assertFalse(response.toString().contains(written.prompt))
        assertFalse(response.toString().contains("modelAnswer"))
        assertFalse(response.toString().contains("rubric"))
    }

    @Test fun writtenOnlyGameRequestDoesNotReplaceActiveSession() {
        val service = StudyService()
        val active = call(service, "mixed", mixed).getValue("body").jsonObject
        val onlyWritten = mixed.copy(questions = (1..3).map { written.copy(id="w$it", prompt="설명 $it") })
        assertEquals("error", call(service, "written", onlyWritten).getValue("type").jsonPrimitive.content)
        val ended = Json.parseToJsonElement(service.exchange(buildJsonObject {
            put("version", 1); put("requestId", "end"); put("type", "end")
            putJsonObject("body") { put("sessionId", active.getValue("sessionId")) }
        }.toString())).jsonObject
        assertEquals("summary", ended.getValue("type").jsonPrimitive.content)
    }
}
