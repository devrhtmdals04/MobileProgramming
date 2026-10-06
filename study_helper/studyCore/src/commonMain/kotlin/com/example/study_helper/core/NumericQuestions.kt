package com.example.study_helper.core

import kotlin.random.Random

/** Explicit supported formulas only. Integer microamps avoid floating-point/rounding ambiguity. */
object NumericQuestions {
    val types=setOf("bjt_beta","bjt_emitter_current","bjt_collector_current")
    private fun milli(micro:Int):String {
        val fraction=(micro%1000).toString().padStart(3,'0').trimEnd('0')
        return (micro/1000).toString()+if(fraction.isEmpty()) "" else ".$fraction"
    }
    fun generate(source:QuizItem, random:Random=Random.Default, previousPrompt:String?=null):QuizItem {
        val type=source.numberVariant ?: return source
        require(type in types) {"지원하지 않는 숫자 변형 유형입니다."}
        val candidates=mutableListOf<QuizItem>()
        for(beta in listOf(50,75,100,125,150,200)) for(base in listOf(10,20,30,40,50,60,80)) {
            val collector=beta*base; val emitter=collector+base
            val question=when(type) {
                "bjt_beta" -> source.copy(
                    prompt="IB = $base µA, IC = ${milli(collector)} mA일 때 직류 전류 이득 βDC는?",
                    choices=listOf("$beta","${beta*10}","${beta+1}"),correctIndex=0,
                    explanation="βDC = IC / IB = $collector µA / $base µA = $beta. 먼저 전류 단위를 맞춥니다. 숫자를 바꾼 연습 문제입니다.")
                "bjt_emitter_current" -> source.copy(
                    prompt="전류 크기가 IB = $base µA, IC = ${milli(collector)} mA라면 IE는?",
                    choices=listOf("${milli(emitter)} mA","${milli(collector-base)} mA","${milli(collector)} mA"),correctIndex=0,
                    explanation="IE = IC + IB = ${milli(collector)} + ${milli(base)} = ${milli(emitter)} mA. $base µA = ${milli(base)} mA입니다. 숫자를 바꾼 연습 문제입니다.")
                else -> source.copy(
                    prompt="활성 동작 조건에서 βDC = $beta, IB = $base µA라면 IC는?",
                    choices=listOf("${milli(collector)} mA","${milli(collector*10)} mA","${milli(emitter)} mA"),correctIndex=0,
                    explanation="활성 동작 조건에서 IC = βDC × IB = $beta × $base µA = $collector µA = ${milli(collector)} mA. 포화 상태에 이 식을 그대로 적용하지 않습니다. 숫자를 바꾼 연습 문제입니다.")
            }
            if(question.prompt!=previousPrompt) candidates+=question
        }
        return candidates.random(random)
    }
}
