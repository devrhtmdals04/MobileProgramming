package com.example.study_helper.runner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.study_helper.study.ExampleStudySet

internal val AnswerGreen = Color(0xFF9CDBB4)
internal val AnswerOrange = Color(0xFFF0AA8A)
internal val LaneLetters = listOf("A", "B", "C")
private val laneNames = listOf("왼쪽", "가운데", "오른쪽")

@Composable
internal fun QuizPrompt(state: RunnerState, compact: Boolean, modifier: Modifier = Modifier) {
    val question = state.activeQuestion ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        color = Ink.copy(alpha = 0.96f), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.heightIn(max = if (compact) 110.dp else 175.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("예제 문제 ${state.questionIndex + 1} / ${state.questions.size}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(if (state.phase == RunPhase.PassingGate) "레인 통과 중" else "시간 제한 없음", color = Muted, fontSize = 10.sp)
            }
            Text(question.prompt, color = Cream, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp))
        }
    }
}

@Composable
internal fun QuizChoices(model: RunnerViewModel, compact: Boolean) {
    val state = model.state
    val question = state.activeQuestion ?: return
    val canSelect = state.phase == RunPhase.Question
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp)) {
        if (!compact) {
            Text("답을 누르거나 좌우로 스와이프해 레인을 고르세요", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            question.choices.forEachIndexed { index, choice ->
                val isSelected = state.selectedAnswer == index
                Surface(
                    onClick = { model.selectAnswer(index) }, enabled = canSelect,
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) Color(0xFF4D5035) else Color(0xFF1C3934),
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Gold else Color(0xFF355248)),
                    modifier = Modifier.weight(1f).height(if (compact) 72.dp else 98.dp).semantics {
                        role = Role.RadioButton
                        selected = isSelected
                        contentDescription = "${LaneLetters[index]} ${laneNames[index]} 레인: $choice"
                    },
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${LaneLetters[index]} · ${laneNames[index]}", color = if (isSelected) Gold else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(choice, color = Cream, fontSize = if (compact) 14.sp else 17.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        val selected = state.selectedAnswer
        PrimaryAction(
            label = when {
                state.phase == RunPhase.PassingGate -> "선택한 레인으로 통과 중…"
                selected == null -> "정답 레인을 선택하세요"
                else -> "${LaneLetters[selected]} 레인으로 통과  →"
            },
            onClick = model::confirmAnswer,
            enabled = canSelect && selected != null,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
internal fun QuizFeedback(model: RunnerViewModel, compact: Boolean) {
    val state = model.state
    val answer = state.answers.lastOrNull() ?: return
    val question = answer.question
    val color = if (answer.isCorrect) AnswerGreen else AnswerOrange
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 12.dp)) {
        Surface(color = Color(0xFF1C3934), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.4f))) {
            Column(Modifier.fillMaxWidth().heightIn(max = if (compact) 100.dp else 195.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(if (answer.isCorrect) "정답이에요!  +100 PT" else "해설을 확인해 보세요", color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (!answer.isCorrect) {
                    Text("내 답: ${question.choices[answer.selectedChoice]}  ·  정답: ${question.choices[question.correctChoice]}", color = Cream, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Text(question.explanation, color = Cream, fontSize = 13.sp, modifier = Modifier.padding(top = 9.dp))
                Text("예제 학습 노트", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(question.sourceNote, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        PrimaryAction(
            if (state.completedStudy) "학습 결과 보기  →" else "계속 달리기  →",
            model::continueAfterAnswer, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
internal fun StudyRunSummary(state: RunnerState) {
    if (state.questions.isEmpty()) return
    Surface(color = Ink.copy(alpha = 0.65f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("학습 기록 · 예제 문제", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("${state.questions.size}문제 중 ${state.answers.size}문제 풀이 · ${state.correctAnswers}문제 정답", color = Cream, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            val accuracy = if (state.answers.isEmpty()) "아직 푼 문제 없음" else "정답률 ${state.correctAnswers * 100 / state.answers.size}%"
            Text("$accuracy · 학습 보너스 ${state.quizBonus} PT", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun MistakeReview(state: RunnerState) {
    val mistakes = state.answers.filterNot { it.isCorrect }
    if (mistakes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("다시 살펴볼 문제 ${mistakes.size}개", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        mistakes.forEach { answer ->
            val question = answer.question
            Column {
                Text(question.prompt, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("내 답: ${question.choices[answer.selectedChoice]}", color = AnswerOrange, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("정답: ${question.choices[question.correctChoice]}", color = AnswerGreen, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text(question.explanation, color = Cream, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("예제 학습 노트 · ${question.sourceNote}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
internal fun ExampleSetLabel() {
    Text(ExampleStudySet.title, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
}
