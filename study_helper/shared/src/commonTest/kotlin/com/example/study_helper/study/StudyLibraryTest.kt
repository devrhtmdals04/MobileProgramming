package com.example.study_helper.study

import com.example.study_helper.core.QuizDocument
import kotlin.test.*

class StudyLibraryTest {
    @Test fun courseWeeksIgnoreStorageSubfoldersAndSortNumerically() {
        val notes = listOf(
            StudyNoteSummary("ten", "10주차", path="학습/수학/10주차/노트/a.md"),
            StudyNoteSummary("two", "2주차", path="수학/02주차/노트/세부/a.md"),
            StudyNoteSummary("summary", "정리", path="수학/02주차/노트/a_요약.md"),
            StudyNoteSummary("unknown", "추가", path="수학/주차 확인 필요/노트/a.md"))
        val rows = studyLibrary(notes, emptyList())
        assertEquals(listOf("summary", "two", "ten", "unknown"), rows.map { it.id })
        assertTrue(rows.all { it.subject == "수학" })
        assertEquals("02주차", rows[1].unit)
    }

    @Test fun technicalFilesStayOutOfStudyViewWithoutRemovingUserNotes() {
        val notes = listOf("README.md", "자료정보.json", "transcript.txt", "transcript.json", "transcript.srt", "내 필기.md")
            .map { StudyNoteSummary(it, it, path="수학/01주차/노트/$it") }
        assertEquals(listOf("내 필기.md"), studyLibrary(notes, emptyList()).map { it.id })
        assertEquals(6, notes.size)
        assertEquals("개인 노트", studyLibrary(listOf(StudyNoteSummary("local", "새 노트")), emptyList()).single().subject)
    }

    @Test fun quizzesFollowSourcePathAndQuizOnlyCoursesRemainVisible() {
        val notes = listOf(StudyNoteSummary("source", "원본", path="수학/03주차/노트/a.md"))
        val linked = StudyQuiz("q", QuizDocument("복습", "source", "원본", emptyList()))
        val standalone = StudyQuiz("q2", QuizDocument("기초", "", "", emptyList()), "회로/02주차/문제/a.quiz.json")
        val rows = studyLibrary(notes, listOf(linked, standalone))
        assertEquals(3, rows.size)
        assertEquals("03주차", rows.single { it.id == "q" }.unit)
        assertEquals("회로", rows.single { it.id == "q2" }.subject)
        assertTrue(rows.single { it.id == "q2" }.quiz)
    }
}
