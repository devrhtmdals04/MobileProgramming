package com.example.study_helper.sync

import com.example.study_helper.core.MarkdownNotes
import com.example.study_helper.core.QuizFiles
import com.example.study_helper.study.MobileStudyGuides
import kotlinx.serialization.json.*
import kotlin.test.*

class BundledGuideRetirementTest {
    private val docs = mutableMapOf("notes" to mutableMapOf<String,String>(), "question-sets" to mutableMapOf<String,String>())
    private val bindings = mutableMapOf<String,JsonElement>()
    private fun seed() {
        for ((i,g) in MobileStudyGuides.all.withIndex()) {
            val prefix=g.path.substringBeforeLast('/')
            val note=MarkdownNotes.write(g.title,g.body)
            val quiz=QuizFiles.write(QuizFiles.read(g.quizJson))
            bind("모바일/노트/$i.md","notes",g.noteId,note)
            bind("모바일/문제/$i.json","question-sets",g.quizId,quiz)
            bind("$prefix/노트/guide.md","notes","target-note-$i",MarkdownNotes.write(g.title,originalGuideBody(g.body)))
            bind("$prefix/문제/guide.json","question-sets","target-quiz-$i",QuizFiles.write(QuizFiles.read(quiz).copy(sourceNoteId="web-note-$i")))
        }
    }
    private fun bind(path:String,kind:String,id:String,text:String) {
        docs.getValue(kind)[id]=text
        bindings[path]=buildJsonObject {put("id",id);put("kind",kind);put("content",text);put("raw",text)}
    }
    @Test fun exactSeedPairsRetireToExistingCanonicalIdentities() {
        seed();val result=bundledGuideRetirements(docs,bindings)
        assertEquals(8,result.size)
        assertEquals(8,result.map { it.oldId }.toSet().size)
        assertTrue(result.all {it.targetPath.startsWith("AI와정보보안/")})
    }
    @Test fun personalSeedEditKeepsTheWholePair() {
        seed();docs.getValue("notes")[MobileStudyGuides.all.first().noteId]+="\n개인 필기"
        assertEquals(6,bundledGuideRetirements(docs,bindings).size)
    }
    @Test fun changedCanonicalOrMissingPairIsNotRetired() {
        seed();docs.getValue("notes")["target-note-0"]+="\n다른 내용"
        bindings.remove("모바일/문제/1.json")
        assertEquals(4,bundledGuideRetirements(docs,bindings).size)
    }
    @Test fun ambiguousDestinationIsNotGuessed() {
        seed();val g=MobileStudyGuides.all.first()
        bind(g.path.substringBeforeLast('/')+"/노트/second.md","notes","second-target",docs.getValue("notes").getValue("target-note-0"))
        assertEquals(6,bundledGuideRetirements(docs,bindings).size)
    }
}
