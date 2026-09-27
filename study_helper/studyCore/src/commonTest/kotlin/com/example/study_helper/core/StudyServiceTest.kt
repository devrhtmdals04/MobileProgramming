package com.example.study_helper.core

import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.*

class StudyServiceTest {
    private val service = StudyService()
    private var sequence = 0
    private fun call(type: String, body: JsonObject = buildJsonObject {}, id: String = "r${++sequence}", version: Int = 1): JsonObject =
        Json.parseToJsonElement(service.exchange(buildJsonObject {
            put("version", version); put("requestId", id); put("type", type); put("body", body)
        }.toString())).jsonObject
    private fun begin() = call("begin")["body"]!!.jsonObject
    private fun answer(session: JsonObject, question: JsonObject, selected: Int) = buildJsonObject {
        put("sessionId", session["sessionId"]!!); put("questionId", question["id"]!!); put("selectedChoice", selected)
    }
    private fun correct(question: JsonObject): Int {
        val definition = question["prompt"]!!.jsonPrimitive.content.substringAfter('\n')
        val term = service.defaultNotes().lines().first { it.substringAfter(": ") == definition }.substringBefore(':')
        return question["choices"]!!.jsonArray.indexOf(JsonPrimitive(term))
    }
    private fun assertType(type: String, response: JsonObject) = assertEquals(type, response["type"]!!.jsonPrimitive.content)

    @Test fun generatesFromProvidedNotesWithoutLeakingAnswers() {
        val session = call("begin", buildJsonObject { put("notes", "A: 따옴표 \"와\" 역슬래시 \\\nB: 두 번째 설명\nC: 세 번째 설명") })
        assertType("session", session)
        val wire = session.toString()
        assertFalse(wire.contains("correctChoice"))
        assertFalse(wire.contains("explanation"))
        assertTrue(wire.contains("따옴표"))
        assertEquals(3, session["body"]!!.jsonObject["questions"]!!.jsonArray.size)
    }
    @Test fun generatorProvidesThreeUniqueChoicesWithOneCorrectAnswer() {
        repeat(100) { seed ->
            QuestionGenerator.generate(service.defaultNotes(), Random(seed)).forEach {
                assertEquals(3, it.choices.distinct().size)
                assertEquals(it.explanation.substringBefore(':'), it.choices[it.correctChoice])
            }
        }
    }
    @Test fun rejectsInvalidNotesBeforeReplacingSession() {
        val session = begin()
        for (notes in listOf("", "A: one", "A: one\nA: two\nC: three", "A: one\nB: one\nC: three", "unstructured text")) {
            assertType("error", call("begin", buildJsonObject { put("notes", notes) }))
        }
        val q = session["questions"]!!.jsonArray[0].jsonObject
        assertType("graded", call("answer", answer(session, q, correct(q))))
    }
    @Test fun editorValidationMatchesGenerationAndDoesNotReplaceActiveSession() {
        val active = begin()
        val cases = listOf(service.defaultNotes(), "A: one\nB: two\nC: three", "", "A: one",
            "A: one\nB: one\nC: three", "A: one\nA: two\nC: three", "A: ${"x".repeat(301)}\nB: two\nC: three")
        for (notes in cases) {
            val error = service.validateNotes(notes)
            val separate = StudyService()
            val response = Json.parseToJsonElement(separate.exchange(buildJsonObject {
                put("version", 1); put("requestId", "validation"); put("type", "begin")
                putJsonObject("body") { put("notes", notes) }
            }.toString())).jsonObject
            assertEquals(error == null, response["type"]!!.jsonPrimitive.content == "session")
        }
        val q = active["questions"]!!.jsonArray[0].jsonObject
        assertType("graded", call("answer", answer(active, q, correct(q))))
    }
    @Test fun gradesCorrectAndWrongAnswersAndReturnsKotlinSummary() {
        val session = begin()
        session["questions"]!!.jsonArray.forEachIndexed { index, value ->
            val q = value.jsonObject
            val selected = if (index == 1) (correct(q) + 1) % 3 else correct(q)
            val result = call("answer", answer(session, q, selected))["body"]!!.jsonObject
            assertEquals(index != 1, result["correct"]!!.jsonPrimitive.boolean)
            assertTrue(result["explanation"]!!.jsonPrimitive.content.isNotEmpty())
        }
        val result = call("end", buildJsonObject { put("sessionId", session["sessionId"]!!) })["body"]!!.jsonObject
        assertEquals(2, result["correctCount"]!!.jsonPrimitive.int)
        assertEquals(3, result["answers"]!!.jsonArray.size)
    }
    @Test fun duplicateAnswerDoesNotIncreaseCountAndCannotChangeAnswer() {
        val session = begin()
        val q = session["questions"]!!.jsonArray[0].jsonObject
        val body = answer(session, q, 0)
        val first = call("answer", body, "same-request")
        assertEquals(first, call("answer", body, "same-request"))
        assertEquals(first["body"], call("answer", body)["body"])
        assertType("error", call("answer", answer(session, q, 1)))
        assertType("error", call("answer", answer(session, q, 1), "same-request"))
    }
    @Test fun rejectsOutOfOrderInvalidChoiceAndExpiredSession() {
        val old = begin()
        val session = begin()
        val qs = session["questions"]!!.jsonArray
        assertType("error", call("answer", answer(old, qs[0].jsonObject, 0)))
        assertType("error", call("answer", answer(session, qs[1].jsonObject, 0)))
        assertType("error", call("answer", answer(session, qs[0].jsonObject, -1)))
        assertType("error", call("answer", answer(session, qs[0].jsonObject, 3)))
    }
    @Test fun rejectsAnswerAfterEndAndEndIsIdempotent() {
        val session = begin()
        val body = buildJsonObject { put("sessionId", session["sessionId"]!!) }
        val first = call("end", body)
        assertEquals(first["body"], call("end", body)["body"])
        assertType("error", call("answer", answer(session, session["questions"]!!.jsonArray[0].jsonObject, 0)))
    }
    @Test fun continuousCyclesCoverTheWholeFileAndKeepHistory() {
        val quiz = QuizDocument("반복 복습", "", "원문", (1..5).map {
            QuizItem("item-$it", "질문 $it", listOf("정답 $it", "오답 A", "오답 B"), 0, "해설 $it", "근거 $it")
        })
        val session = call("begin", buildJsonObject {
            put("continuous", true); put("questionSet", Json.parseToJsonElement(QuizFiles.write(quiz)))
        })["body"]!!.jsonObject
        var batch = session["questions"]!!.jsonArray
        val ids = mutableSetOf<String>()
        var submitted = 0
        repeat(3) {
            assertEquals(quiz.questions.map { it.prompt }.toSet(), batch.map { it.jsonObject["prompt"]!!.jsonPrimitive.content }.toSet())
            batch.forEachIndexed { index, item ->
                val q = item.jsonObject
                assertTrue(ids.add(q["id"]!!.jsonPrimitive.content))
                val right = q["choices"]!!.jsonArray.indexOfFirst { it.jsonPrimitive.content.startsWith("정답") }
                val grade = call("answer", answer(session, q, right))["body"]!!.jsonObject
                submitted++
                assertEquals(submitted, grade["correctCount"]!!.jsonPrimitive.int)
                if (index == 0) assertType("error", call("next", buildJsonObject {
                    put("sessionId", session["sessionId"]!!); put("afterQuestionId", q["id"]!!)
                }))
            }
            val request = buildJsonObject {
                put("sessionId", session["sessionId"]!!); put("afterQuestionId", batch.last().jsonObject["id"]!!)
            }
            val response = call("next", request)
            assertType("questions", response)
            assertFalse(response.toString().contains("correctChoice"))
            val next = response["body"]!!.jsonObject
            assertEquals(next, call("next", request)["body"]!!.jsonObject)
            val newBatch = next["questions"]!!.jsonArray
            assertNotEquals(batch.last().jsonObject["prompt"], newBatch.first().jsonObject["prompt"])
            batch = newBatch
        }
        val end = buildJsonObject { put("sessionId", session["sessionId"]!!) }
        val summary = call("end", end)["body"]!!.jsonObject
        assertEquals(15, summary["answeredCount"]!!.jsonPrimitive.int)
        assertEquals(15, summary["correctCount"]!!.jsonPrimitive.int)
        assertEquals(15, summary["answers"]!!.jsonArray.size)
        assertType("error", call("next", buildJsonObject {
            put("sessionId", session["sessionId"]!!); put("afterQuestionId", "q15")
        }))
    }

    @Test fun timeoutRecordsNoSelectionAndAllowsGuidedReview() {
        val session = begin()
        val q = session["questions"]!!.jsonArray.first().jsonObject
        val request = buildJsonObject { put("sessionId", session["sessionId"]!!); put("questionId", q["id"]!!) }
        val response = call("timeout", request)
        assertType("graded", response)
        val verdict = response["body"]!!.jsonObject
        assertEquals(-1, verdict["selectedChoice"]!!.jsonPrimitive.int)
        assertTrue(verdict["timedOut"]!!.jsonPrimitive.boolean)
        assertFalse(verdict["correct"]!!.jsonPrimitive.boolean)
        assertEquals(1, verdict["answeredCount"]!!.jsonPrimitive.int)
        assertEquals(verdict, call("timeout", request)["body"]!!.jsonObject)
        assertType("error", call("answer", answer(session, q, correct(q))))
        val retryTimeout = call("timeout", JsonObject(request + ("review" to JsonPrimitive(true))))
        assertType("reviewed", retryTimeout)
        assertEquals(0, retryTimeout["body"]!!.jsonObject["correctCount"]!!.jsonPrimitive.int)
        val reviewed = call("review", answer(session, q, correct(q)))["body"]!!.jsonObject
        assertEquals(1, reviewed["reviewedCount"]!!.jsonPrimitive.int)
        assertEquals(0, reviewed["correctCount"]!!.jsonPrimitive.int)
        call("end", buildJsonObject { put("sessionId", session["sessionId"]!!) })
        assertType("error", call("timeout", request))
    }

    @Test fun guidedRetryIsGradedWithoutChangingFirstAnswer() {
        val session = begin()
        val qs = session["questions"]!!.jsonArray
        val q = qs[0].jsonObject
        val wrong = answer(session, q, (correct(q) + 1) % 3)
        assertType("error", call("review", wrong))
        call("answer", wrong)
        assertFalse(call("review", wrong)["body"]!!.jsonObject["correct"]!!.jsonPrimitive.boolean)
        val right = answer(session, q, correct(q))
        val result = call("review", right)
        assertType("reviewed", result)
        assertEquals(0, result["body"]!!.jsonObject["correctCount"]!!.jsonPrimitive.int)
        assertEquals(1, result["body"]!!.jsonObject["reviewedCount"]!!.jsonPrimitive.int)
        assertEquals(result["body"], call("review", right)["body"])
        assertType("error", call("answer", right))
        val next = qs[1].jsonObject
        call("answer", answer(session, next, correct(next)))
        assertType("error", call("review", right))
        assertType("error", call("review", answer(session, next, correct(next))))
        val summary = call("end", buildJsonObject { put("sessionId", session["sessionId"]!!) })["body"]!!.jsonObject
        assertEquals(1, summary["correctCount"]!!.jsonPrimitive.int)
        assertEquals(1, summary["reviewedCount"]!!.jsonPrimitive.int)
        assertFalse(summary["answers"]!!.jsonArray[0].jsonObject["correct"]!!.jsonPrimitive.boolean)
        assertType("error", call("review", right))
        val fresh = begin()
        assertType("error", call("review", right))
        val ended = call("end", buildJsonObject { put("sessionId", fresh["sessionId"]!!) })["body"]!!.jsonObject
        assertEquals(0, ended["reviewedCount"]!!.jsonPrimitive.int)
    }

    @Test fun malformedOrUnsupportedRequestsReturnErrors() {
        assertType("error", call("begin", version = 2))
        assertType("error", call("unknown"))
        for (input in listOf("{", "[]", "null", "{}", "{\"requestId\":{},\"version\":1}")) {
            assertType("error", Json.parseToJsonElement(service.exchange(input)).jsonObject)
        }
    }
}
