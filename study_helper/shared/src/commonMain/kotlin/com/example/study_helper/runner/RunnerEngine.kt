package com.example.study_helper.runner

import com.example.study_helper.study.StudyAnswer
import com.example.study_helper.study.StudyQuestion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class RunPhase { Ready, Running, Question, PassingGate, AnswerFeedback, Paused, Finished }
enum class ObstacleKind { Crate, Hurdle, Arch, Coin }

data class TrackObject(val lane: Int, val distance: Float, val kind: ObstacleKind)

data class RunnerState(
    val phase: RunPhase = RunPhase.Ready,
    val distance: Float = 0f,
    val elapsed: Float = 0f,
    val lane: Int = 1,
    val visualLane: Float = 1f,
    val jumpRemaining: Float = 0f,
    val slideRemaining: Float = 0f,
    val health: Int = 3,
    val coins: Int = 0,
    val bestScore: Int = 0,
    val invulnerability: Float = 0f,
    val feedback: String = "",
    val feedbackRemaining: Float = 0f,
    val objects: List<TrackObject> = emptyList(),
    val questions: List<StudyQuestion> = emptyList(),
    val questionIndex: Int = 0,
    val selectedAnswer: Int? = null,
    val answers: List<StudyAnswer> = emptyList(),
    val phaseBeforePause: RunPhase = RunPhase.Running,
) {
    val correctAnswers: Int get() = answers.count { it.isCorrect }
    val quizBonus: Int get() = correctAnswers * 100
    val score: Int get() = distance.toInt() + coins * 25 + quizBonus
    val activeQuestion: StudyQuestion? get() = questions.getOrNull(questionIndex)
    val gateDistance: Float? get() = activeQuestion?.let { FIRST_GATE + questionIndex * GATE_SPACING }
    val completedStudy: Boolean get() = questions.isNotEmpty() && answers.size == questions.size
    val isMoving: Boolean get() = phase == RunPhase.Running || phase == RunPhase.PassingGate
    val speed: Float get() = min(28f, 15f + distance / 110f)
    val jumpHeight: Float
        get() = if (jumpRemaining > 0f) sin(PI * (1f - jumpRemaining / JUMP_DURATION)).toFloat() else 0f
    val isSliding: Boolean get() = slideRemaining > 0f

    companion object {
        const val JUMP_DURATION = 1.05f
        const val SLIDE_DURATION = 1.05f
        const val VIEW_DISTANCE = 110f
        const val FIRST_GATE = 84f
        const val GATE_SPACING = 96f
        const val READING_DISTANCE = 24f
    }
}

/** Row-based courses can later place quiz gates without changing movement or collision handling. */
fun interface RunnerCourse {
    fun objectsForRow(index: Int, distance: Float): List<TrackObject>
}

class EndlessCourse(private val seed: Int = Random.nextInt()) : RunnerCourse {
    override fun objectsForRow(index: Int, distance: Float): List<TrackObject> {
        val random = Random(seed + index * 7919)
        val safeLane = if (index == 0) 0 else random.nextInt(3)
        val blockedLanes = (0..2).filter { it != safeLane }.shuffled(random)
        val obstacleCount = if (index < 5) 1 else random.nextInt(1, 3)
        val kind = when (index % 3) {
            0 -> ObstacleKind.Crate
            1 -> ObstacleKind.Hurdle
            else -> ObstacleKind.Arch
        }
        return buildList {
            repeat(obstacleCount) { offset ->
                val lane = if (index == 0) 1 else blockedLanes[offset]
                add(TrackObject(lane, distance, kind))
            }
            repeat(3) { add(TrackObject(safeLane, distance + it * 4f, ObstacleKind.Coin)) }
        }
    }
}

/** Pure Kotlin simulation. Fixed steps keep collisions consistent across 30/60/120 Hz displays. */
class RunnerEngine(private val course: RunnerCourse = EndlessCourse()) {
    var state = RunnerState()
        private set
    private var accumulator = 0.0
    private var nextRow = 0

    fun start(questions: List<StudyQuestion> = emptyList()) {
        require(questions.map { it.id }.distinct().size == questions.size) { "Question IDs must be unique" }
        state = RunnerState(
            phase = RunPhase.Running,
            bestScore = max(state.bestScore, state.score),
            questions = questions.toList(),
        )
        accumulator = 0.0
        nextRow = 0
        fillTrack()
    }

    fun pause() {
        if (state.phase in listOf(RunPhase.Running, RunPhase.Question, RunPhase.PassingGate, RunPhase.AnswerFeedback)) {
            state = state.copy(phase = RunPhase.Paused, phaseBeforePause = state.phase)
            accumulator = 0.0
        }
    }

    fun resume() {
        if (state.phase == RunPhase.Paused) state = state.copy(phase = state.phaseBeforePause)
    }

    fun returnToReady() {
        if (state.phase != RunPhase.Paused && state.phase != RunPhase.Finished) return
        state = RunnerState(bestScore = max(state.bestScore, state.score))
        accumulator = 0.0
        nextRow = 0
    }

    fun move(direction: Int) {
        if (state.phase == RunPhase.Question) {
            selectAnswer((state.lane + direction.coerceIn(-1, 1)).coerceIn(0, 2))
            return
        }
        if (state.phase != RunPhase.Running) return
        state = state.copy(lane = (state.lane + direction.coerceIn(-1, 1)).coerceIn(0, 2))
    }

    fun jump() {
        if (state.phase == RunPhase.Running && state.jumpRemaining == 0f && !state.isSliding) {
            state = state.copy(jumpRemaining = RunnerState.JUMP_DURATION)
        }
    }

    fun slide() {
        if (state.phase == RunPhase.Running && state.jumpRemaining == 0f && !state.isSliding) {
            state = state.copy(slideRemaining = RunnerState.SLIDE_DURATION)
        }
    }

    fun selectAnswer(choice: Int) {
        if (state.phase != RunPhase.Question || choice !in 0..2) return
        state = state.copy(selectedAnswer = choice, lane = choice, visualLane = choice.toFloat())
    }

    fun confirmAnswer() {
        if (state.phase != RunPhase.Question || state.selectedAnswer == null) return
        state = state.copy(phase = RunPhase.PassingGate)
        accumulator = 0.0
    }

    fun continueAfterAnswer() {
        if (state.phase != RunPhase.AnswerFeedback) return
        val complete = state.completedStudy
        state = state.copy(
            phase = if (complete) RunPhase.Finished else RunPhase.Running,
            questionIndex = state.questionIndex + 1,
            selectedAnswer = null,
            invulnerability = 1.35f,
            bestScore = max(state.bestScore, state.score),
        )
        accumulator = 0.0
    }

    fun retryMistakes() {
        if (state.phase != RunPhase.Finished) return
        val mistakes = state.answers.filterNot { it.isCorrect }.map { it.question }
        if (mistakes.isNotEmpty()) start(mistakes)
    }

    fun advance(seconds: Double) {
        if (!state.isMoving || !seconds.isFinite() || seconds <= 0.0) return
        accumulator += seconds.coerceAtMost(0.25)
        while (accumulator + 1e-10 >= STEP && state.isMoving) {
            simulate(STEP.toFloat())
            accumulator -= STEP
        }
        if (!state.isMoving) accumulator = 0.0
    }

    private fun simulate(dt: Float) {
        if (state.phase == RunPhase.PassingGate) {
            passGate(dt)
            return
        }
        val before = state
        val readingPoint = before.gateDistance?.minus(RunnerState.READING_DISTANCE)
        val laneDifference = before.lane - before.visualLane
        state = before.copy(
            distance = min(before.distance + before.speed * dt, readingPoint ?: Float.MAX_VALUE),
            elapsed = before.elapsed + dt,
            visualLane = before.visualLane + laneDifference.coerceIn(-8f * dt, 8f * dt),
            jumpRemaining = max(0f, before.jumpRemaining - dt),
            slideRemaining = max(0f, before.slideRemaining - dt),
            invulnerability = max(0f, before.invulnerability - dt),
            feedbackRemaining = max(0f, before.feedbackRemaining - dt),
        )
        for (item in before.objects) {
            if (item.distance <= before.distance || item.distance > state.distance) continue
            // Match the visible position; a late swipe cannot teleport through an obstacle.
            if (abs(item.lane - state.visualLane) > 0.48f) continue
            if (item.kind == ObstacleKind.Coin) {
                state = state.copy(coins = state.coins + 1)
                continue
            }
            val avoided = when (item.kind) {
                ObstacleKind.Hurdle -> state.jumpHeight > 0.38f
                ObstacleKind.Arch -> state.isSliding
                else -> false
            }
            if (avoided) {
                state = state.copy(feedback = "멋진 회피!", feedbackRemaining = 0.85f)
            } else if (state.invulnerability == 0f) {
                val health = state.health - 1
                state = state.copy(
                    health = health,
                    invulnerability = 1.35f,
                    feedback = when (item.kind) {
                        ObstacleKind.Crate -> "상자는 옆으로 피하세요"
                        ObstacleKind.Hurdle -> "낮은 벽은 점프!"
                        else -> "높은 문은 슬라이드!"
                    },
                    feedbackRemaining = 1.3f,
                    phase = if (health == 0) RunPhase.Finished else RunPhase.Running,
                    bestScore = max(state.bestScore, state.score),
                )
                if (health == 0) break
            }
        }
        state = state.copy(objects = state.objects.filter { it.distance > state.distance - 12f })
        if (state.phase == RunPhase.Running) {
            fillTrack()
            if (readingPoint != null && state.distance >= readingPoint) {
                state = state.copy(
                    phase = RunPhase.Question, selectedAnswer = null,
                    visualLane = state.lane.toFloat(), jumpRemaining = 0f, slideRemaining = 0f,
                    feedbackRemaining = 0f,
                )
            }
        }
    }

    private fun passGate(dt: Float) {
        val gate = state.gateDistance ?: return
        val question = state.activeQuestion ?: return
        val selected = state.selectedAnswer ?: return
        state = state.copy(distance = min(gate, state.distance + 24f * dt), elapsed = state.elapsed + dt)
        if (state.distance >= gate) {
            val answer = StudyAnswer(question, selected)
            state = state.copy(phase = RunPhase.AnswerFeedback, answers = state.answers + answer)
        }
    }

    private fun fillTrack() {
        val additions = mutableListOf<TrackObject>()
        while (rowDistance(nextRow) <= state.distance + RunnerState.VIEW_DISTANCE) {
            // Reserve a calm approach and exit for every question, including rows generated in advance.
            additions += course.objectsForRow(nextRow, rowDistance(nextRow)).filter { item ->
                state.questions.indices.none { index ->
                    val gate = RunnerState.FIRST_GATE + index * RunnerState.GATE_SPACING
                    item.distance >= gate - 30f && item.distance <= gate + 24f
                }
            }
            nextRow++
        }
        if (additions.isNotEmpty()) state = state.copy(objects = state.objects + additions)
    }

    private fun rowDistance(index: Int): Float = 42f + index * 24f

    private companion object { const val STEP = 1.0 / 120.0 }
}
