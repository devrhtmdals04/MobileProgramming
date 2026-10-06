package com.example.study_helper.study

import com.example.study_helper.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PdfStudyTest {
    private class Fake : PdfStudyPlatform {
        var requests = 0
        var body = ""
        var event: (String) -> Unit = {}
        var complete: (String) -> Unit = {}
        var picked = """{"filename":"강의.pdf","base64":"JVBERi0xLjQ="}"""
        override fun chooseStudyPdf(completion: (String) -> Unit) = completion(picked)
        override fun generateStudyContent(key: String, model: String, body: String, event: (String) -> Unit, completion: (String) -> Unit) {
            requests++; this.body = body; this.event = event; complete = completion
        }
        override fun cancelStudyGeneration() {}
    }
    private fun chunk(text: String) = buildJsonObject {
        put("type", "response.output_text.delta"); put("delta", text)
    }.toString()
    private val completed = """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
    private fun Fake.emit(text: String) { event(chunk(text)); event(completed) }
    private fun quiz(count: Int = 5) = QuizFiles.write(QuizDocument("문제", "untrusted-id", "untrusted-title", (1..count).map {
        QuizItem("q$it", "질문 $it", listOf("답", "오답", "다른 오답"), 0, "해설 (PDF 2쪽)", "원문 근거")
    }))

    @Test fun doesNotTransmitWithoutKeyOrWithInvalidModel() {
        val p = Fake(); val host = PdfStudyHost(p, { _, _, _ -> "note" }, {})
        host.pick(); host.summarize(); assertEquals(0, p.requests); assertNotNull(host.error)
        host.key = "test-key"; host.model = "../other?key=bad"; host.summarize(); assertEquals(0, p.requests)
    }

    @Test fun streamMustFinishBeforeSaveAndCancellationIgnoresLateCallbacks() = runTest {
        var saves = 0
        val p = Fake(); val host = PdfStudyHost(p, { _, _, _ -> saves++; "note" }, {})
        host.pick(); host.key = "test-key"; host.summarize()
        p.event(chunk("## 핵심 개념\n\n부분 응답"))
        assertTrue(host.summary.contains("부분 응답")); assertFalse(host.saveSummary())
        val lateEvent = p.event; val lateCompletion = p.complete
        host.cancel(); host.summarize()
        lateEvent(chunk("오래된 결과")); lateEvent(completed); lateCompletion("")
        assertEquals("", host.summary); assertTrue(host.busy)
        p.emit("## 한 줄 요약\n\n완성된 응답"); p.complete("")
        assertTrue(host.summaryComplete); assertTrue(host.saveSummary()); assertEquals(1, saves)
    }

    @Test fun truncatedAndEmptyResponsesAreNotSaved() = runTest {
        val p = Fake(); val host = PdfStudyHost(p, { _, _, _ -> error("must not save") }, {})
        host.pick(); host.key = "test-key"; host.summarize()
        p.event("""{"type":"response.incomplete","response":{"status":"incomplete"}}"""); p.complete("")
        assertFalse(host.busy); assertFalse(host.summaryComplete); assertFalse(host.saveSummary()); assertNotNull(host.error)
        host.summarize(); p.complete(""); assertFalse(host.summaryComplete)
    }

    @Test fun questionsUseOriginalPdfAndPersistOnlyAfterValidationAndReview() = runTest {
        val p = Fake(); val saved = mutableListOf<String>()
        val host = PdfStudyHost(p, { _, _, _ -> "real-note-id" }, { saved += it })
        host.pick(); host.key = "test-key"; host.count = 5; host.summarize()
        p.emit("## 한 줄 요약\n\n요약"); p.complete("")
        host.generateQuiz()
        val parts = Json.parseToJsonElement(p.body).jsonObject.getValue("input").jsonArray[0].jsonObject.getValue("content").jsonArray
        assertEquals("data:application/pdf;base64,JVBERi0xLjQ=", parts[0].jsonObject.getValue("file_data").jsonPrimitive.content)
        p.emit(quiz()); p.complete("")
        assertTrue(saved.isEmpty()); assertNotNull(host.quizJson)
        assertTrue(host.saveQuestions()); assertFalse(host.saveQuestions()); assertEquals(1, saved.size)
        val document = QuizFiles.read(saved.single())
        assertEquals("real-note-id", document.sourceNoteId); assertEquals("강의", document.sourceNoteTitle)
    }

    @Test fun wrongQuestionCountAndMalformedQuestionAreRejected() {
        assertFailsWith<IllegalArgumentException> { PdfStudyProtocol.canonicalQuiz(quiz(3), "id", "title", 5) }
        assertFailsWith<IllegalArgumentException> { PdfStudyProtocol.canonicalQuiz("{}", "id", "title", 5) }
    }

    @Test fun saveFailureKeepsDraftAndDoesNotSendQuizRequest() = runTest {
        val p = Fake(); val host = PdfStudyHost(p, { _, _, _ -> error("disk full") }, {})
        host.pick(); host.key = "test-key"; host.summarize()
        p.emit("## 한 줄 요약\n\n요약"); p.complete("")
        host.generateQuiz()
        assertEquals(1, p.requests); assertTrue(host.summaryComplete); assertNotNull(host.error); assertFalse(host.saving)
    }

    @Test fun cancelledOrInvalidPickerKeepsPreviousDocument() {
        val p = Fake(); val host = PdfStudyHost(p, { _, _, _ -> "id" }, {})
        host.pick(); p.picked = """{"cancelled":true}"""; host.pick()
        assertEquals("강의.pdf", host.filename); assertTrue(host.ready); assertFalse(host.picking)
        p.picked = """{"filename":"bad.pdf","base64":"not-a-pdf"}"""; host.pick()
        assertEquals("강의.pdf", host.filename); assertNotNull(host.error)
    }

    @Test fun ignoresReasoningAndRejectsRefusalsAndServerErrors() {
        assertEquals("" to false, PdfStudyProtocol.eventText("""{"type":"response.reasoning_summary_text.delta","delta":"reasoning"}"""))
        assertEquals("" to true, PdfStudyProtocol.eventText(completed))
        assertFailsWith<IllegalStateException> { PdfStudyProtocol.eventText("""{"type":"response.refusal.delta","delta":"refused"}""") }
        assertFailsWith<IllegalStateException> { PdfStudyProtocol.eventText("""{"type":"error","message":"secret response"}""") }
    }

    @Test fun requestUsesResponsesContractAndDoesNotStoreResponse() {
        val request = Json.parseToJsonElement(PdfStudyProtocol.request("PDF", "return JSON", true, "gpt-custom")).jsonObject
        assertEquals("gpt-custom", request.getValue("model").jsonPrimitive.content)
        assertTrue(request.getValue("stream").jsonPrimitive.boolean)
        assertFalse(request.getValue("store").jsonPrimitive.boolean)
        assertEquals("json_object", request.getValue("text").jsonObject.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(request.containsKey("api_key"))
    }
}
