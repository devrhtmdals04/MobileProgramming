package com.example.study_helper.study

private val weekMarker = Regex("(?:^|[\\s_·-])(\\d{1,2})\\s*주차")
private val genericFolders = setOf("학습", "문제", "문제모음", "문제 모음", "quizzes", "questions", "녹음", "정리", "노트")

fun StudyQuiz.subjectName(): String {
    document.subject.trim().takeIf { it.isNotEmpty() }?.let { return it }
    val folders = path.replace('\\', '/').split('/').dropLast(1).filter { it.isNotBlank() }
    // Shared roots can include 학습 or a semester directory before the subject.
    folders.firstOrNull { it !in genericFolders && !Regex("\\d{4}[-_]\\d+|\\d+주차").matches(it) }
        ?.let { return it }
    for (title in listOf(document.sourceNoteTitle, document.title)) {
        val marker = weekMarker.find(title) ?: continue
        title.substring(0, marker.range.first).trim(' ', '·', '-', '_').takeIf { it.isNotEmpty() }?.let { return it }
    }
    return "미분류"
}

fun groupQuizzesBySubject(quizzes: List<StudyQuiz>, query: String = ""): List<Pair<String, List<StudyQuiz>>> {
    val needle = query.trim()
    return quizzes.filter { quiz ->
        listOf(quiz.subjectName(), quiz.document.title, quiz.document.sourceNoteTitle, quiz.path)
            .any { it.contains(needle, ignoreCase = true) }
    }.groupBy { it.subjectName() }.entries
        .sortedWith(compareBy({ it.key == "미분류" }, { it.key.lowercase() }))
        .map { (subject, entries) -> subject to entries.sortedWith(compareBy(
            { weekMarker.find(it.document.title)?.groupValues?.get(1)?.toIntOrNull()
                ?: weekMarker.find(it.document.sourceNoteTitle)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
            { it.document.title.lowercase() }, { it.id })) }
}

/** Prefer exact synchronized location; unsynced imports follow their source note's folder. */
fun StudyQuiz.libraryPath(notes: List<StudyNoteSummary>): String {
    if (path.isNotBlank()) return path
    val source = notes.firstOrNull { it.id == document.sourceNoteId }
        ?: notes.singleOrNull { it.title == document.sourceNoteTitle }
    val parent = source?.path?.substringBeforeLast('/', "").orEmpty()
    val folder = parent.ifEmpty { subjectName().takeUnless { it == "미분류" }.orEmpty() }
    return (if (folder.isEmpty()) "" else "$folder/") + "$id.quiz.json"
}

fun libraryDirectories(paths: List<String>, folder: String): List<String> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    return paths.mapNotNull { path ->
        path.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { '/' in it }?.substringBefore('/')
    }.distinct().sorted()
}
