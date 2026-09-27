package com.example.study_helper.core

import kotlin.test.*
import kotlinx.serialization.json.*

class MarkdownNotesTest {
    @Test fun markdownBodyRoundTripsWithoutRemovingFormatting() {
        val body = "## 수업 노트\n\n**중요**한 내용과 [참고](https://example.org).\n\n```kotlin\nval x = 1\n```\n"
        assertEquals(MarkdownNote("생물", body), MarkdownNotes.read(MarkdownNotes.write("생물", body)))
    }

    @Test fun importsBomWindowsNewlinesAndUsesFilenameWithoutHeading() {
        assertEquals(MarkdownNote("제목", "본문\n"), MarkdownNotes.read("\uFEFF# 제목\r\n\r\n본문\r\n"))
        assertEquals(MarkdownNote("강의", "본문"), MarkdownNotes.read("본문", "강의"))
    }

    @Test fun reviewSectionIgnoresProseCodeAndFollowingSections() {
        val body = """
            # 제목
            자유로운 강의 정리입니다.
            ```text
            ## 복습 개념
            가짜: 코드 예시
            ```
            ## 복습 개념
            - **A**: 첫 설명
            - B: 둘째 설명
            - C: 셋째 설명
            ## 참고
            https://example.org
        """.trimIndent()
        assertEquals("A: 첫 설명\nB: 둘째 설명\nC: 셋째 설명", MarkdownNotes.questionNotes(body))
    }

    @Test fun legacyNotesRemainUsableAndUnstructuredNotesCanBeSaved() {
        val legacy = StudyService().defaultNotes()
        assertEquals(legacy, MarkdownNotes.questionNotes(legacy))
        MarkdownNotes.validate("강의", "문단으로 정리한 학습 내용")
        assertFailsWith<IllegalArgumentException> { MarkdownNotes.questionNotes("문단으로 정리한 학습 내용") }
    }

    @Test fun malformedReviewEntriesAndBinaryOrOversizedDocumentsAreRejected() {
        for (body in listOf("## 복습 개념\n- A: 하나", "## 복습 개념\n- A: 하나\n- A: 둘\n- C: 셋")) {
            assertFailsWith<IllegalArgumentException> { MarkdownNotes.questionNotes(body) }
        }
        assertFailsWith<IllegalArgumentException> { MarkdownNotes.read("\u0000binary") }
        assertFailsWith<IllegalArgumentException> { MarkdownNotes.write("제목", "x".repeat(100_001)) }
    }

    @Test fun fencesOfDifferentLengthsAndIndentedCodeDoNotBecomeConcepts() {
        val body = "## 복습 개념\n````text\n```\nFake: not a concept\n````\n" +
            "    Also fake: indented code\n1. `A`: 하나\n2. __B__: 둘\n3. C: 셋: 추가 설명\n# 다음 장\nD: 제외"
        assertEquals("A: 하나\nB: 둘\nC: 셋: 추가 설명", MarkdownNotes.questionNotes(body))
    }

    @Test fun titleAndEmptyBodyRoundTripWithoutSilentTitleTruncation() {
        assertEquals(MarkdownNote("C# #", ""), MarkdownNotes.read(MarkdownNotes.write("C# #", "")))
        assertFailsWith<IllegalArgumentException> { MarkdownNotes.read("# ${"a".repeat(61)}\n\n본문") }
        assertFailsWith<IllegalArgumentException> { MarkdownNotes.write("제목\u0000", "본문") }
    }

    @Test fun importedMarkdownCreatesPlayableQuestionsAndGradedExplanations() {
        val source = "# 네트워크\n\n강의 정리입니다.\n\n## 복습 개념\n" +
            "- HTTP: 웹 문서를 전송하는 규약\n- DNS: 도메인 이름을 주소로 바꾸는 체계\n- TCP: 신뢰성 있는 전송 규약\n\n## 참고\n출제하지 않는 문단"
        val imported = MarkdownNotes.read(source)
        val stored = MarkdownNotes.read(MarkdownNotes.write(imported.title, imported.body))
        val entries = MarkdownNotes.questionNotes(stored.body)
        val service = StudyService()
        fun call(type: String, id: String, body: JsonObject): JsonObject = Json.parseToJsonElement(service.exchange(buildJsonObject {
            put("version", 1); put("requestId", id); put("type", type); put("body", body)
        }.toString())).jsonObject
        val response = call("begin", "begin", buildJsonObject { put("notes", entries) })
        assertEquals("session", response["type"]!!.jsonPrimitive.content)
        val session = response["body"]!!.jsonObject
        val questions = session["questions"]!!.jsonArray
        assertEquals(3, questions.size)
        assertFalse(response.toString().contains("correctChoice"))
        questions.forEachIndexed { index, value ->
            val question = value.jsonObject
            val definition = question["prompt"]!!.jsonPrimitive.content.substringAfter('\n')
            val term = entries.lines().single { it.substringAfter(": ") == definition }.substringBefore(':')
            val answer = call("answer", "answer-$index", buildJsonObject {
                put("sessionId", session["sessionId"]!!)
                put("questionId", question["id"]!!)
                put("selectedChoice", question["choices"]!!.jsonArray.indexOf(JsonPrimitive(term)))
            })["body"]!!.jsonObject
            assertTrue(answer["correct"]!!.jsonPrimitive.boolean)
            assertEquals("$term: $definition", answer["explanation"]!!.jsonPrimitive.content)
        }
        val summary = call("end", "end", buildJsonObject { put("sessionId", session["sessionId"]!!) })["body"]!!.jsonObject
        assertEquals(3, summary["correctCount"]!!.jsonPrimitive.int)
    }
}
