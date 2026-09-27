package com.example.study_helper.core

import kotlin.random.Random

internal data class GeneratedQuestion(
    val id: String,
    val prompt: String,
    val choices: List<String>,
    val correctChoice: Int,
    val explanation: String,
)

/** Offline term/definition generator. This is intentionally not an AI generator. */
internal object QuestionGenerator {
    fun generate(notes: String, random: Random): List<GeneratedQuestion> {
        val entries = parse(notes)
        return entries.shuffled(random).take(3).mapIndexed { index, (term, definition) ->
            val distractors = entries.map { it.first }.filter { it != term }.shuffled(random).take(2)
            val choices = (distractors + term).shuffled(random)
            GeneratedQuestion("q${index + 1}", "다음 설명에 해당하는 용어는?\n$definition", choices,
                choices.indexOf(term), "$term: $definition")
        }
    }

    fun parse(notes: String): List<Pair<String, String>> {
        require(notes.length <= 12_000) { "학습 노트는 12,000자 이하로 입력해 주세요." }
        val entries = notes.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(':', limit = 2).map(String::trim)
            require(parts.size == 2 && parts[0].length in 1..60 && parts[1].length in 1..300) {
                "각 줄을 '용어: 설명'으로 입력해 주세요. 용어는 60자, 설명은 300자까지 가능합니다."
            }
            parts[0] to parts[1]
        }.toList()
        require(entries.size in 3..20) { "서로 다른 용어를 3~20개 입력해 주세요." }
        require(entries.map { it.first }.distinct().size == entries.size &&
            entries.map { it.second }.distinct().size == entries.size) { "용어와 설명은 각각 중복될 수 없습니다." }
        return entries
    }
}
