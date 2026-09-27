package com.example.study_helper.study

/** A three-choice question maps each choice to one runner lane. */
data class StudyQuestion(
    val id: String,
    val prompt: String,
    val choices: List<String>,
    val correctChoice: Int,
    val explanation: String,
    val sourceNote: String,
) {
    init {
        require(id.isNotBlank() && prompt.isNotBlank())
        require(choices.size == 3 && choices.all { it.isNotBlank() })
        require(choices.distinct().size == 3)
        require(correctChoice in choices.indices)
        require(explanation.isNotBlank() && sourceNote.isNotBlank())
    }
}

data class StudyAnswer(val question: StudyQuestion, val selectedChoice: Int) {
    init { require(selectedChoice in question.choices.indices) }
    val isCorrect: Boolean get() = selectedChoice == question.correctChoice
}

/** Authored demo content, not questions generated from a user's materials. */
object ExampleStudySet {
    const val title = "기초 수학 · 예제 학습 노트"
    val questions = listOf(
        StudyQuestion(
            id = "triangle-area",
            prompt = "밑변이 6 cm, 높이가 4 cm인 삼각형의 넓이는?",
            choices = listOf("10 cm²", "12 cm²", "24 cm²"),
            correctChoice = 1,
            explanation = "삼각형의 넓이는 밑변 × 높이 ÷ 2입니다. 6 × 4 ÷ 2 = 12 cm²입니다.",
            sourceNote = "삼각형의 넓이 = 밑변 × 높이 ÷ 2. 높이는 밑변에 수직인 길이입니다.",
        ),
        StudyQuestion(
            id = "fraction-sum",
            prompt = "분수 1/4과 1/2을 더하면 얼마일까요?",
            choices = listOf("2/6", "1/8", "3/4"),
            correctChoice = 2,
            explanation = "1/2을 2/4로 통분하면, 1/4 + 2/4 = 3/4입니다. 분모는 그대로 두고 분자를 더합니다.",
            sourceNote = "분모가 다른 분수는 같은 분모로 통분한 뒤 분자를 더합니다.",
        ),
        StudyQuestion(
            id = "order-of-operations",
            prompt = "3 + 4 × 2를 계산한 값은?",
            choices = listOf("11", "14", "10"),
            correctChoice = 0,
            explanation = "곱셈을 먼저 계산합니다. 4 × 2 = 8이므로, 3 + 8 = 11입니다.",
            sourceNote = "괄호가 없는 식에서는 곱셈과 나눗셈을 덧셈과 뺄셈보다 먼저 계산합니다.",
        ),
    )
}
