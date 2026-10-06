package com.example.study_helper.study

import com.example.study_helper.core.*
import kotlin.test.*

class QuizSubjectsTest {
    private fun quiz(id: String, title: String, subject: String = "", path: String = "") =
        StudyQuiz(id, QuizDocument(title, "", title, emptyList(), subject), path)

    @Test fun classificationUsesExplicitSubjectThenFoldersThenWeekTitle() {
        assertEquals("회로", quiz("1", "보안 1주차", "회로", "학습/수학/문제/a.json").subjectName())
        assertEquals("수학", quiz("1", "복습", path="2026-2/학습/수학/02주차/문제/a.json").subjectName())
        assertEquals("디지털시스템입문", quiz("1", "디지털시스템입문 4주차 · BJT").subjectName())
        assertEquals("미분류", quiz("1", "기초 문제 10개").subjectName())
    }

    @Test fun groupingSearchAndNumericWeekOrderKeepAllFiles() {
        val quizzes = listOf(quiz("10", "수학 10주차"), quiz("2", "수학 2주차"),
            quiz("x", "복습"), quiz("y", "정보보안 1주차"))
        val groups = groupQuizzesBySubject(quizzes)
        assertEquals(4, groups.sumOf { it.second.size })
        assertEquals("미분류", groups.last().first)
        assertEquals(listOf("2", "10"), groups.first { it.first == "수학" }.second.map { it.id })
        assertEquals(2, groupQuizzesBySubject(quizzes, "수학").single().second.size)
        assertEquals("10", groupQuizzesBySubject(quizzes, "10주차").single().second.single().id)
        assertTrue(groupQuizzesBySubject(quizzes, "없는 과목").isEmpty())
    }
    @Test fun unifiedLibraryIncludesQuizOnlyFoldersAndExactSiblingPaths() {
        val paths = listOf("수학/01주차/노트/a.md", "수학/01주차/문제/b.quiz.json", "수학/10주차/문제/c.quiz.json")
        assertEquals(listOf("수학"), libraryDirectories(paths, ""))
        assertEquals(listOf("노트", "문제"), libraryDirectories(paths, "수학/01주차"))
        assertEquals(emptyList(), libraryDirectories(paths, "수학/1"))
        assertEquals("수학/01주차/문제/b.quiz.json", quiz("b", "복습", path=paths[1]).libraryPath(emptyList()))
    }

    @Test fun importsFollowUniqueSourceNoteAndAmbiguousSourcesStayUnassigned() {
        val notes = listOf(StudyNoteSummary("n", "원본", path="수학/02주차/노트/a.md"))
        val q = quiz("q", "복습").let { it.copy(document=it.document.copy(sourceNoteTitle="원본")) }
        assertEquals("수학/02주차/노트/q.quiz.json", q.libraryPath(notes))
        assertEquals("q.quiz.json", q.libraryPath(notes + notes[0].copy(id="other")))
    }
}
