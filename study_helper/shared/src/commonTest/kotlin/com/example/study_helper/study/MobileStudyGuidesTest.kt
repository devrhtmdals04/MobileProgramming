package com.example.study_helper.study

import com.example.study_helper.core.MarkdownNotes
import com.example.study_helper.core.QuizFiles
import kotlinx.serialization.json.*
import kotlin.test.*

class MobileStudyGuidesTest {
    @Test fun sameCourseContainsFourReadableNotesAnd27LinkedQuestions() {
        assertEquals(4, MobileStudyGuides.all.size)
        assertEquals(27, MobileStudyGuides.all.sumOf { QuizFiles.read(it.quizJson).questions.size })
        for (guide in MobileStudyGuides.all) {
            MarkdownNotes.validate(guide.title, guide.body)
            assertEquals(guide.noteId, QuizFiles.read(guide.quizJson).sourceNoteId)
            assertTrue(guide.body.contains("## 학습 목표"))
        }
        assertEquals("AI와정보보안/01주차/PDF/정보보안-1.pdf" to 10, MobileStudyGuides.source("study-source://1/1?page=10"))
        assertNull(MobileStudyGuides.source("study-source://1/../etc?page=1"))
        assertNull(MobileStudyGuides.source("study-source://4/2?page=1"))
        assertNull(MobileStudyGuides.source("study-source://1/1?page=0"))
    }
    @Test fun launchDoesNotInstallOrRecreateBundledDocuments() {
        val disk = Disk()
        val first = NotebookHost(disk)
        assertTrue(first.notebook.notes.none { MobileStudyGuides.isGuide(it.id) })
        assertTrue(first.quizzes.isEmpty())
        NotebookHost(disk)
        assertEquals(1, disk.files.size) // Usage note only.
    }
    @Test fun existingEditsAndRecentResultSurviveLaunch() {
        val disk = Disk()
        val guide = MobileStudyGuides.all.first()
        disk.files["notes" to guide.noteId] = MarkdownNotes.write(guide.title, "직접 수정한 내용")
        disk.prefs["recent_result"] = "preserve historical record even if unrecognized"
        val reopened = NotebookHost(disk)
        assertEquals("직접 수정한 내용", reopened.notebook.notes.single().markdown)
        assertEquals(1, disk.files.size)
        assertEquals("preserve historical record even if unrecognized", disk.prefs["recent_result"])
    }
    private class Disk: NotebookPlatform {
        val files = mutableMapOf<Pair<String,String>,String>()
        val prefs = mutableMapOf<String,String>()
        override fun includesStudyGuides() = true
        override fun readFiles(kind:String) = buildJsonObject { putJsonArray("files") {
            files.filter { it.key.first==kind }.forEach { (key,value) -> add(buildJsonObject { put("id",key.second);put("content",value) }) }
        } }.toString()
        override fun writeFile(kind:String,id:String,content:String):String? { files[kind to id]=content;return null }
        override fun preference(key:String)=prefs[key]
        override fun setPreference(key:String,value:String) {prefs[key]=value}
        override fun newId()="unused"
        override fun pickDocument(kind:String) {}
        override fun exportDocument(filename:String,content:String) {}
        override fun copyText(text:String) {}
        override fun openLink(url:String) {}
        override fun startGame() {}
    }
}
