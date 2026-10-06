package com.example.study_helper.sync

import com.example.study_helper.core.MarkdownNotes
import com.example.study_helper.core.QuizFiles
import com.example.study_helper.study.MobileStudyGuides
import kotlinx.serialization.json.*

internal data class GuideRetirement(val kind: String, val oldId: String, val oldPath: String,
    val targetId: String, val targetPath: String)

private fun JsonObject.field(name: String) = this[name]?.jsonPrimitive?.content.orEmpty()

// Only the renderer's generated source links/footer are ignored, never user prose.
internal fun originalGuideBody(body: String): String = body.substringBefore("\n\n## 원본 자료")
    .replace(Regex("\\[자료 (\\d+)(?: · PDF (\\d+)쪽)?]\\(study-source://[^)]+\\)")) {
        "(자료 ${it.groupValues[1]}" + if (it.groupValues[2].isEmpty()) ")" else " · PDF ${it.groupValues[2]}쪽)"
    }.trim()

/** Plan pairs only when both seeded documents and their canonical targets are unchanged. */
internal fun bundledGuideRetirements(documents: Map<String, Map<String, String>>,
    bindings: Map<String, JsonElement>): List<GuideRetirement> = buildList {
    for (guide in MobileStudyGuides.all) {
        val pair = mutableListOf<GuideRetirement>()
        for ((kind, oldId) in listOf("notes" to guide.noteId, "question-sets" to guide.quizId)) {
            val current = documents[kind]?.get(oldId) ?: continue
            val expected = if (kind == "notes") MarkdownNotes.write(guide.title, guide.body)
                else QuizFiles.write(QuizFiles.read(guide.quizJson))
            if (current != expected) continue // A personal edit is never retired automatically.
            val old = bindings.entries.singleOrNull { it.value.jsonObject.field("id") == oldId &&
                it.value.jsonObject.field("kind") == kind && it.key.startsWith("모바일/") } ?: continue
            if (old.value.jsonObject.field("content") != expected) continue
            val prefix = guide.path.substringBeforeLast('/') + if (kind == "notes") "/노트/" else "/문제/"
            val target = bindings.entries.singleOrNull { (path, value) ->
                val bind = value.jsonObject
                if (!path.startsWith(prefix) || bind.field("kind") != kind || bind.field("id") == oldId) false
                else runCatching {
                    val text = documents[kind]?.get(bind.field("id")) ?: return@runCatching false
                    if (text != bind.field("content")) return@runCatching false
                    if (kind == "notes") {
                        val note = MarkdownNotes.read(text)
                        note.title == guide.title && note.body.trim() == originalGuideBody(guide.body)
                    } else QuizFiles.read(text).copy(sourceNoteId = "") == QuizFiles.read(guide.quizJson).copy(sourceNoteId = "")
                }.getOrDefault(false)
            } ?: continue
            pair += GuideRetirement(kind, oldId, old.key, target.value.jsonObject.field("id"), target.key)
        }
        if (pair.size == 2) addAll(pair)
    }
}
