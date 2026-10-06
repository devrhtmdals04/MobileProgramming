package com.example.study_helper.study

/** Presentation only: original paths and documents remain unchanged. */
data class LibraryMaterial(
    val key: String, val id: String, val title: String, val subject: String, val unit: String,
    val quiz: Boolean, val questions: Int = 0, val summary: Boolean = false,
)

private fun libraryLocation(path: String): Pair<String, String> {
    val folders = path.replace('\\', '/').split('/').dropLast(1)
        .filter { it.isNotBlank() && it != "학습" && !Regex("\\d{4}[-_]\\d+").matches(it) }
    val subject = folders.firstOrNull()?.takeUnless { it in setOf("노트", "문제", "모바일") } ?: "개인 노트"
    val unit = folders.drop(1).firstOrNull { Regex("\\d+주차").matches(it) || it in setOf("공통", "주차 확인 필요") } ?: "공통"
    return subject to unit
}

fun studyLibrary(notes: List<StudyNoteSummary>, quizzes: List<StudyQuiz>): List<LibraryMaterial> {
    val materialNotes = notes.filter { note ->
        val name = note.path.substringAfterLast('/').lowercase()
        val parts = note.path.split('/')
        name !in setOf("readme.md", "자료정보.json", "transcript.txt", "transcript.json", "transcript.srt") &&
            (note.path.isBlank() || name.endsWith(".md")) && "강의" !in parts
    }.map { note ->
        val (subject, unit) = libraryLocation(note.path)
        LibraryMaterial("note:${note.id}", note.id, note.title, subject, unit, false,
            summary = note.path.substringAfterLast('/').endsWith("_요약.md") || note.title.contains("요약"))
    }
    val materialQuizzes = quizzes.map { quiz ->
        val (subject, unit) = libraryLocation(quiz.libraryPath(notes))
        LibraryMaterial("quiz:${quiz.id}", quiz.id, quiz.document.title, subject, unit, true, quiz.document.questions.size)
    }
    return (materialNotes + materialQuizzes).sortedWith(compareBy(
        { it.subject }, { libraryUnitOrder(it.unit) }, { it.unit }, { it.quiz }, { !it.summary }, { it.title }, { it.key }))
}

fun libraryUnitOrder(unit: String): Int = Regex("^(\\d+)주차$").matchEntire(unit)?.groupValues?.get(1)?.toIntOrNull()
    ?: if (unit == "공통") Int.MAX_VALUE - 1 else Int.MAX_VALUE
