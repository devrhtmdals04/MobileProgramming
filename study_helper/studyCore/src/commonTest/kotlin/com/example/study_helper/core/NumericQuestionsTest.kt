package com.example.study_helper.core

import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.*

class NumericQuestionsTest {
    private fun source(type:String)=QuizItem(type,"원래 $type 문제",listOf("원래 정답","오답 1","오답 2"),0,"원래 해설","전류 관계식",type)
    private fun doc()=QuizDocument("숫자 연습","","전류 관계식",NumericQuestions.types.map(::source))
    private fun expected(prompt:String):Double {
        val ib=Regex("IB = (\\d+) µA").find(prompt)!!.groupValues[1].toDouble()
        return when {
            "전류 이득" in prompt -> Regex("IC = ([0-9.]+) mA").find(prompt)!!.groupValues[1].toDouble()*1000/ib
            "라면 IE" in prompt -> Regex("IC = ([0-9.]+) mA").find(prompt)!!.groupValues[1].toDouble()+ib/1000
            else -> Regex("βDC = (\\d+)").find(prompt)!!.groupValues[1].toDouble()*ib/1000
        }
    }
    private fun number(choice:String)=choice.removeSuffix(" mA").toDouble()
    @Test fun variedValuesHaveExactlyOneCorrectChoiceAndConsistentUnits() {
        for(type in NumericQuestions.types) {
            var previous:String?=null
            repeat(300) {seed ->
                val q=NumericQuestions.generate(source(type),Random(seed),previous)
                assertNotEquals(previous,q.prompt)
                assertEquals(3,q.choices.distinct().size)
                assertEquals(expected(q.prompt),number(q.choices[q.correctIndex]),0.0000001)
                assertEquals(1,q.choices.count {kotlin.math.abs(number(it)-expected(q.prompt))<0.0000001})
                assertTrue(q.explanation.contains(q.choices[q.correctIndex].removeSuffix(" mA")))
                previous=q.prompt
            }
        }
    }
    @Test fun formulasRoundTripAndUnknownTypesAreRejected() {
        val document=doc()
        assertEquals(document,QuizFiles.read(QuizFiles.write(document)))
        assertFailsWith<IllegalArgumentException> {QuizFiles.read(QuizFiles.write(document).replace("bjt_beta","arbitrary_formula"))}
        val plain=source("bjt_beta").copy(numberVariant=null)
        assertEquals(plain,NumericQuestions.generate(plain))
    }
    @Test fun cyclesChangeNumbersButRetriesKeepTheSameQuestionAndGrade() {
        val service=StudyService();var seq=0
        fun call(type:String,body:JsonObject,id:String="r${++seq}")=Json.parseToJsonElement(service.exchange(buildJsonObject {
            put("version",1);put("requestId",id);put("type",type);put("body",body)
        }.toString())).jsonObject
        val first=call("begin",buildJsonObject {put("questionSet",Json.parseToJsonElement(QuizFiles.write(doc())));put("continuous",true)})
        assertEquals("session",first["type"]!!.jsonPrimitive.content)
        assertFalse(first.toString().contains("correctChoice"));assertFalse(first.toString().contains("numberVariant"))
        val session=first.getValue("body").jsonObject
        val questions=session.getValue("questions").jsonArray.map {it.jsonObject}
        for((index,q) in questions.withIndex()) {
            val correct=q.getValue("choices").jsonArray.indexOfFirst {kotlin.math.abs(number(it.jsonPrimitive.content)-expected(q.getValue("prompt").jsonPrimitive.content))<0.0000001}
            val body=buildJsonObject {put("sessionId",session.getValue("sessionId"));put("questionId",q.getValue("id"));put("selectedChoice",if(index==0) (correct+1)%3 else correct)}
            val requestId="answer${++seq}"
            val graded=call("answer",body,requestId)
            assertEquals(index!=0,graded.getValue("body").jsonObject.getValue("correct").jsonPrimitive.boolean)
            assertEquals(graded,call("answer",body,requestId))
            if(index==0) {
                val retry=call("review",JsonObject(body+("selectedChoice" to JsonPrimitive(correct))))
                assertTrue(retry.getValue("body").jsonObject.getValue("correct").jsonPrimitive.boolean)
                assertEquals(graded.getValue("body").jsonObject.getValue("explanation"),retry.getValue("body").jsonObject.getValue("explanation"))
            }
        }
        val boundary=buildJsonObject {put("sessionId",session.getValue("sessionId"));put("afterQuestionId",questions.last().getValue("id"))}
        val next=call("next",boundary)
        assertEquals(next.getValue("body"),call("next",boundary).getValue("body"))
        val before=questions.map {it.getValue("prompt").jsonPrimitive.content}.toSet()
        val after=next.getValue("body").jsonObject.getValue("questions").jsonArray.map {it.jsonObject.getValue("prompt").jsonPrimitive.content}
        assertTrue(after.none {it in before})
    }
    @Test fun disablingVariantsRetainsOriginalNumbers() {
        val response=Json.parseToJsonElement(StudyService().exchange(buildJsonObject {
            put("version",1);put("requestId","begin");put("type","begin")
            putJsonObject("body") {put("questionSet",Json.parseToJsonElement(QuizFiles.write(doc())));put("varyNumbers",false)}
        }.toString())).jsonObject
        val prompts=response.getValue("body").jsonObject.getValue("questions").jsonArray.map {it.jsonObject.getValue("prompt").jsonPrimitive.content}.toSet()
        assertEquals(doc().questions.map {it.prompt}.toSet(),prompts)
    }
}
