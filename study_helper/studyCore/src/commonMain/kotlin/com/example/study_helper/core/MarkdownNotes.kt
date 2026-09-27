package com.example.study_helper.core

data class MarkdownNote(val title: String, val body: String)

/** Portable Markdown storage and a small, explicit review-section format. No inference or engine dependency. */
object MarkdownNotes {
    const val MAX_BODY_LENGTH = 100_000

    fun read(document: String, fallbackTitle: String = "가져온 노트"): MarkdownNote {
        val text = document.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        require(text.length <= MAX_BODY_LENGTH + 100) { "노트는 100,000자 이하로 가져올 수 있어요." }
        require('\u0000' !in text) { "텍스트 마크다운 파일을 선택해 주세요." }
        val firstLine = text.substringBefore('\n')
        val heading = Regex("^# +(.+)$").matchEntire(firstLine)
        val title = heading?.groupValues?.get(1)?.trim()
            ?: fallbackTitle.replace('\n', ' ').replace('\r', ' ').trim().take(60).ifEmpty { "가져온 노트" }
        val body = if (heading == null) text else text.substringAfter('\n', "").removePrefix("\n")
        validate(title, body)
        return MarkdownNote(title, body)
    }

    fun write(title: String, body: String): String {
        validate(title, body)
        return "# ${title.trim()}\n\n$body"
    }

    fun validate(title: String, body: String) {
        require(title.trim().length in 1..60 && '\n' !in title && '\r' !in title && '\u0000' !in title) { "제목은 한 줄로 1~60자 입력해 주세요." }
        require(body.length <= MAX_BODY_LENGTH && '\u0000' !in body) { "노트 내용은 100,000자 이하로 입력해 주세요." }
    }

    /** Explicit review section keeps prose, code samples and URLs out of quiz generation.
     * Older term/definition-only notes also remain valid without a section heading.
     */
    fun questionNotes(markdown: String): String {
        require(markdown.length <= MAX_BODY_LENGTH) { "노트가 너무 깁니다." }
        val lines = markdown.lines()
        val headings = Regex("^(#{1,6}) +(.+?)(?: +#+)?$")
        val visible = mutableListOf<String>()
        var fenceCharacter: Char? = null
        var fenceLength = 0
        for (line in lines) {
            // Indented code blocks are not review entries.
            if (line.startsWith("    ") || line.startsWith('\t')) continue
            val trimmed = line.trim()
            val marker = Regex("^(`{3,}|~{3,})(.*)$").matchEntire(trimmed)
            if (fenceCharacter != null) {
                if (marker != null && marker.groupValues[1].first() == fenceCharacter &&
                    marker.groupValues[1].length >= fenceLength && marker.groupValues[2].isBlank()) fenceCharacter = null
                continue
            }
            if (marker != null) {
                fenceCharacter = marker.groupValues[1].first()
                fenceLength = marker.groupValues[1].length
                continue
            }
            visible += trimmed
        }
        val section = visible.indexOfFirst { headings.matchEntire(it)?.groupValues?.get(2) == "복습 개념" }
        val selected = if (section >= 0) {
            val depth = headings.matchEntire(visible[section])!!.groupValues[1].length
            visible.drop(section + 1).takeWhile { (headings.matchEntire(it)?.groupValues?.get(1)?.length ?: 7) > depth }
        } else visible
        val entries = selected.filter { it.isNotBlank() && !headings.matches(it) }.map { line ->
            val entry = line.replace(Regex("^(?:[-+*]|\\d+[.)]) +"), "")
            require(':' in entry) { "문제를 만들려면 ‘## 복습 개념’ 아래에 ‘- 용어: 설명’을 3~20개 적어 주세요. 일반 노트는 그대로 저장할 수 있어요." }
            val term = entry.substringBefore(':').trim().removeSurrounding("**").removeSurrounding("__").removeSurrounding("`")
            "$term: ${entry.substringAfter(':').trim()}"
        }.joinToString("\n")
        QuestionGenerator.parse(entries)
        return entries
    }
}
