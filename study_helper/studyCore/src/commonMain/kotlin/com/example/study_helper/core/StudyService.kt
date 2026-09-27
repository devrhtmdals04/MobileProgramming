package com.example.study_helper.core

import kotlin.random.Random
import kotlinx.serialization.json.*

/** Single-owner service: all generation, answer keys and grading live in Kotlin.
 * Hosts serialize calls; the only engine-facing API is a versioned JSON envelope.
 */
class StudyService {
    private var sessionId = ""
    private var questions = emptyList<GeneratedQuestion>()
    private var pool = emptyList<GeneratedQuestion>()
    private var continuous = false
    private var title = ""
    private val answers = linkedMapOf<String, JsonObject>()
    private val reviewed = mutableSetOf<String>()
    private var closed = true
    private val responses = linkedMapOf<String, Pair<String, String>>()

    fun defaultNotes(): String = """
        광합성: 식물이 빛 에너지를 이용해 물과 이산화 탄소로 양분을 만드는 과정
        증산 작용: 식물의 잎에서 물이 수증기 형태로 빠져나가는 현상
        세포 호흡: 세포가 양분을 분해하여 생명 활동에 필요한 에너지를 얻는 과정
    """.trimIndent()

    /** Validates the editor using the exact same rules as question generation. */
    fun validateNotes(notes: String): String? = try {
        QuestionGenerator.parse(notes)
        null
    } catch (exception: IllegalArgumentException) {
        exception.message
    }

    fun exchange(requestJson: String): String {
        var requestId = ""
        return try {
            require(requestJson.length <= 120_000) { "요청이 너무 큽니다." }
            val request = Json.parseToJsonElement(requestJson).jsonObject
            requestId = request.string("requestId")
            require(requestId.length in 1..80) { "요청 ID가 올바르지 않습니다." }
            require(request["version"]?.jsonPrimitive?.intOrNull == 1) { "지원하지 않는 데이터 버전입니다." }
            responses[requestId]?.let { (previousRequest, response) ->
                require(previousRequest == requestJson) { "동일한 요청 ID를 다른 내용에 사용할 수 없습니다." }
                return response
            }
            val body = request["body"]?.jsonObject ?: buildJsonObject {}
            val (type, result) = when (request.string("type")) {
                "begin" -> "session" to begin(body)
                "answer" -> "graded" to answer(body)
                "review" -> "reviewed" to review(body)
                "next" -> "questions" to next(body)
                "timeout" -> if (body["review"]?.jsonPrimitive?.booleanOrNull == true)
                    "reviewed" to review(body, timedOut = true) else "graded" to answer(body, timedOut = true)
                "end" -> "summary" to end(body)
                else -> error("알 수 없는 요청입니다.")
            }
            val response = envelope(requestId, type, result)
            responses[requestId] = requestJson to response
            if (responses.size > 64) responses.remove(responses.keys.first())
            response
        } catch (exception: IllegalArgumentException) {
            envelope(requestId, "error", buildJsonObject { put("message", exception.message ?: "잘못된 요청입니다.") })
        } catch (exception: IllegalStateException) {
            envelope(requestId, "error", buildJsonObject { put("message", exception.message ?: "요청을 처리할 수 없습니다.") })
        }
    }

    private fun begin(body: JsonObject): JsonObject {
        val quiz = body["questionSet"]?.let { QuizFiles.read(it.toString()) }
        // Validate before replacing the active session.
        val repeat = body["continuous"]?.jsonPrimitive?.booleanOrNull == true
        val generated = if (quiz != null) quiz.questions.mapIndexed { index, q ->
            GeneratedQuestion("q${index + 1}", q.prompt, q.choices, q.correctIndex, q.explanation)
        } else QuestionGenerator.generate(body["notes"]?.jsonPrimitive?.content ?: defaultNotes(), Random.Default)
        continuous = repeat
        pool = generated
        questions = shuffledBatch(0, if (repeat) pool.size else 3)
        title = quiz?.title ?: "학습 노트 · 개념 확인"
        sessionId = Random.nextLong().toULong().toString(16)
        answers.clear()
        reviewed.clear()
        closed = false
        return publicQuestions(questions)
    }

    private fun shuffledBatch(offset: Int, size: Int): List<GeneratedQuestion> {
        val ordered = pool.shuffled().toMutableList()
        if (offset > 0 && ordered.first().prompt == questions.last().prompt) {
            val different = ordered.indexOfFirst { it.prompt != questions.last().prompt }
            if (different > 0) {
                val first = ordered[0]; ordered[0] = ordered[different]; ordered[different] = first
            }
        }
        return ordered.take(size).mapIndexed { index, q ->
            val order = q.choices.indices.shuffled()
            q.copy(id = "q${offset + index + 1}", choices = order.map { q.choices[it] }, correctChoice = order.indexOf(q.correctChoice))
        }
    }

    private fun publicQuestions(batch: List<GeneratedQuestion>) = buildJsonObject {
        put("sessionId", sessionId)
        put("title", title)
        put("continuous", continuous)
        putJsonArray("questions") {
            batch.forEach { question -> add(buildJsonObject {
                put("id", question.id); put("prompt", question.prompt)
                putJsonArray("choices") { question.choices.forEach { add(it) } }
            }) }
        }
    }

    /** Append a shuffled cycle without resetting the session's first-answer history. */
    private fun next(body: JsonObject): JsonObject {
        validateSession(body)
        check(!closed && continuous) { "반복 학습 세션이 아닙니다." }
        val after = body.string("afterQuestionId")
        check(answers.isNotEmpty() && answers.keys.last() == after) { "현재 관문을 먼저 완료해 주세요." }
        check(answers.getValue(after).getValue("correct").jsonPrimitive.boolean || after in reviewed) { "오답을 다시 확인해 주세요." }
        // Retrying the boundary request returns the already prepared cycle.
        if (questions.size > answers.size) {
            check(answers.size % pool.size == 0) { "현재 문제 묶음을 먼저 완료해 주세요." }
            return publicQuestions(questions.drop(answers.size))
        }
        val batch = shuffledBatch(questions.size, pool.size)
        questions = questions + batch
        return publicQuestions(batch)
    }

    private fun answer(body: JsonObject, timedOut: Boolean = false): JsonObject {
        validateSession(body)
        check(!closed) { "이미 종료된 학습입니다. 새로 시작해 주세요." }
        val questionId = body.string("questionId")
        val selected = if (timedOut) -1 else body["selectedChoice"]?.jsonPrimitive?.intOrNull
            ?: error("선택한 답이 올바르지 않습니다.")
        require(timedOut || selected in 0..2) { "선택한 답이 올바르지 않습니다." }
        answers[questionId]?.let { previous ->
            check(previous["selectedChoice"]!!.jsonPrimitive.int == selected) { "이미 답을 제출한 문제입니다." }
            return previous
        }
        val question = questions.getOrNull(answers.size)
        check(question != null && question.id == questionId) { "현재 문제에 대한 답을 제출해 주세요." }
        val correct = selected == question.correctChoice
        val correctCount = answers.values.count { it["correct"]!!.jsonPrimitive.boolean } + if (correct) 1 else 0
        return buildJsonObject {
            put("sessionId", sessionId)
            put("questionId", questionId)
            put("timedOut", timedOut)
            put("selectedChoice", selected)
            put("correct", correct)
            put("correctChoice", question.correctChoice)
            put("explanation", question.explanation)
            put("answeredCount", answers.size + 1)
            put("correctCount", correctCount)
        }.also { answers[questionId] = it }
    }

    /** Guided retry never rewrites the first answer or inflates the initial score. */
    private fun review(body: JsonObject, timedOut: Boolean = false): JsonObject {
        validateSession(body)
        check(!closed) { "이미 종료된 학습입니다." }
        val id = body.string("questionId")
        val first = answers[id] ?: error("먼저 답을 제출해 주세요.")
        check(answers.keys.last() == id && !first.getValue("correct").jsonPrimitive.boolean) { "현재 오답을 다시 확인해 주세요." }
        val selected = if (timedOut) -1 else body["selectedChoice"]?.jsonPrimitive?.intOrNull ?: error("선택한 답이 올바르지 않습니다.")
        require(timedOut || selected in 0..2) { "선택한 답이 올바르지 않습니다." }
        val question = questions.single { it.id == id }
        val correct = selected == question.correctChoice
        if (correct) reviewed.add(id)
        return buildJsonObject {
            put("sessionId", sessionId); put("questionId", id)
            put("timedOut", timedOut)
            put("selectedChoice", selected); put("correct", correct)
            put("correctChoice", question.correctChoice); put("explanation", question.explanation)
            put("answeredCount", answers.size)
            put("correctCount", answers.values.count { it.getValue("correct").jsonPrimitive.boolean })
            put("reviewedCount", reviewed.size)
        }
    }

    private fun end(body: JsonObject): JsonObject {
        validateSession(body)
        closed = true
        return buildJsonObject {
            put("sessionId", sessionId)
            put("answeredCount", answers.size)
            put("questionCount", questions.size)
            put("continuous", continuous)
            put("correctCount", answers.values.count { it["correct"]!!.jsonPrimitive.boolean })
            put("reviewedCount", reviewed.size)
            putJsonArray("answers") { answers.values.forEach { add(it) } }
        }
    }

    private fun validateSession(body: JsonObject) {
        check(sessionId.isNotEmpty() && body.string("sessionId") == sessionId) { "학습 세션이 만료되었습니다. 새로 시작해 주세요." }
    }

    private fun envelope(requestId: String, type: String, body: JsonObject): String = buildJsonObject {
        put("version", 1)
        put("requestId", requestId)
        put("type", type)
        put("body", body)
    }.toString()

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull
        ?: error("필수 항목이 없습니다: $key")
}
