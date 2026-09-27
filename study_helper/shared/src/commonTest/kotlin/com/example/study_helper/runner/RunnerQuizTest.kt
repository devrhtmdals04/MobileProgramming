package com.example.study_helper.runner

import com.example.study_helper.study.ExampleStudySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerQuizTest {
    private val emptyCourse = RunnerCourse { _, _ -> emptyList() }
    private val questions = ExampleStudySet.questions

    @Test
    fun questionStopsAtExactApproachAndWaitsWithoutDefaultAnswer() {
        val engine = studyRun()
        advanceToStop(engine)
        assertEquals(RunPhase.Question, engine.state.phase)
        assertEquals(60f, engine.state.distance)
        assertNull(engine.state.selectedAnswer)
        val waiting = engine.state
        repeat(600) { engine.advance(0.1) }
        engine.confirmAnswer()
        engine.jump()
        engine.slide()
        assertEquals(waiting, engine.state)
    }

    @Test
    fun choiceAndSwipeSelectMatchingVisibleLaneButDoNotSubmit() {
        val engine = studyRun()
        advanceToStop(engine)
        engine.selectAnswer(2)
        assertEquals(2, engine.state.selectedAnswer)
        assertEquals(2f, engine.state.visualLane)
        engine.move(-1)
        assertEquals(1, engine.state.selectedAnswer)
        assertEquals(1f, engine.state.visualLane)
        engine.selectAnswer(-1)
        engine.selectAnswer(3)
        assertEquals(1, engine.state.selectedAnswer)
        assertEquals(RunPhase.Question, engine.state.phase)
        assertTrue(engine.state.answers.isEmpty())
    }

    @Test
    fun crossingGateGradesOnceAndLocksChosenLane() {
        val engine = studyRun()
        advanceToStop(engine)
        engine.selectAnswer(1)
        engine.confirmAnswer()
        engine.advance(0.25)
        assertEquals(RunPhase.PassingGate, engine.state.phase)
        assertTrue(engine.state.distance > 60f && engine.state.distance < 84f)
        assertTrue(engine.state.answers.isEmpty())
        engine.selectAnswer(0)
        engine.move(1)
        engine.jump()
        engine.slide()
        engine.confirmAnswer()
        assertEquals(1, engine.state.selectedAnswer)
        advanceToStop(engine)
        assertEquals(RunPhase.AnswerFeedback, engine.state.phase)
        assertEquals(84f, engine.state.distance)
        assertEquals(1, engine.state.answers.size)
        assertTrue(engine.state.answers.single().isCorrect)
        assertEquals(100, engine.state.quizBonus)
        assertEquals(184, engine.state.score)
        val feedback = engine.state
        repeat(20) { engine.confirmAnswer(); engine.advance(0.1) }
        assertEquals(feedback, engine.state)
    }

    @Test
    fun wrongAnswerKeepsHealthAndStoresChosenAnswerWithExplanation() {
        val engine = studyRun()
        advanceToStop(engine)
        val health = engine.state.health
        answer(engine, 0)
        assertEquals(health, engine.state.health)
        assertEquals(0, engine.state.correctAnswers)
        assertEquals(0, engine.state.quizBonus)
        assertEquals(84, engine.state.score)
        val answer = engine.state.answers.single()
        assertEquals(0, answer.selectedChoice)
        assertFalse(answer.isCorrect)
        assertEquals(questions.first().explanation, answer.question.explanation)
        assertEquals(questions.first().sourceNote, answer.question.sourceNote)
    }

    @Test
    fun pausePreservesQuestionAndSelectedChoice() {
        val engine = studyRun()
        advanceToStop(engine)
        engine.selectAnswer(2)
        pauseAndResume(engine)
        assertEquals(RunPhase.Question, engine.state.phase)
        assertEquals(2, engine.state.selectedAnswer)
        assertEquals(60f, engine.state.distance)
    }

    @Test
    fun pauseHalfwayThroughGateCannotGradeUntilResumed() {
        val engine = studyRun()
        advanceToStop(engine)
        engine.selectAnswer(1)
        engine.confirmAnswer()
        engine.advance(0.25)
        pauseAndResume(engine)
        assertEquals(RunPhase.PassingGate, engine.state.phase)
        assertTrue(engine.state.answers.isEmpty())
        advanceToStop(engine)
        assertEquals(1, engine.state.answers.size)
        assertEquals(1, engine.state.correctAnswers)
    }

    @Test
    fun pauseOnExplanationResumesExplanationWithoutSkippingQuestion() {
        val engine = studyRun()
        advanceToStop(engine)
        answer(engine, 0)
        pauseAndResume(engine)
        assertEquals(RunPhase.AnswerFeedback, engine.state.phase)
        assertEquals(0, engine.state.questionIndex)
        engine.continueAfterAnswer()
        engine.continueAfterAnswer()
        assertEquals(RunPhase.Running, engine.state.phase)
        assertEquals(1, engine.state.questionIndex)
        assertNull(engine.state.selectedAnswer)
        advanceToStop(engine)
        assertEquals(RunPhase.Question, engine.state.phase)
        assertEquals(156f, engine.state.distance)
        assertEquals(1, engine.state.answers.size)
    }

    @Test
    fun allQuestionsEndStudyOnlyAfterLastExplanation() {
        val engine = studyRun()
        questions.forEachIndexed { index, question ->
            advanceToStop(engine)
            assertEquals(index, engine.state.questionIndex)
            answer(engine, question.correctChoice)
            assertEquals(RunPhase.AnswerFeedback, engine.state.phase)
            engine.continueAfterAnswer()
        }
        assertEquals(RunPhase.Finished, engine.state.phase)
        assertTrue(engine.state.completedStudy)
        assertEquals(3, engine.state.answers.size)
        assertEquals(3, engine.state.correctAnswers)
        assertEquals(300, engine.state.quizBonus)
        assertEquals(576, engine.state.score)
        assertEquals(576, engine.state.bestScore)
        val finished = engine.state
        engine.retryMistakes()
        engine.continueAfterAnswer()
        assertEquals(finished, engine.state)
    }

    @Test
    fun reviewUsesOnlyMistakesInOriginalOrderAndStartsFresh() {
        val engine = studyRun()
        listOf(0, 2, 2).forEach { choice ->
            advanceToStop(engine)
            answer(engine, choice)
            engine.continueAfterAnswer()
        }
        val best = engine.state.score
        engine.retryMistakes()
        assertEquals(RunPhase.Running, engine.state.phase)
        assertEquals(listOf(questions[0], questions[2]), engine.state.questions)
        assertTrue(engine.state.answers.isEmpty())
        assertEquals(0f, engine.state.distance)
        assertEquals(3, engine.state.health)
        assertEquals(best, engine.state.bestScore)
        engine.state.questions.forEach { question ->
            advanceToStop(engine)
            answer(engine, question.correctChoice)
            engine.continueAfterAnswer()
        }
        assertTrue(engine.state.completedStudy)
        assertEquals(2, engine.state.correctAnswers)
    }

    @Test
    fun earlyGameOverDoesNotTreatUnansweredQuestionsAsMistakes() {
        val engine = RunnerEngine(RunnerCourse { _, distance ->
            (0..2).map { TrackObject(it, distance, ObstacleKind.Crate) }
        }).apply { start(questions) }
        advanceToStop(engine)
        assertEquals(RunPhase.Question, engine.state.phase)
        answer(engine, 0)
        engine.continueAfterAnswer()
        advanceToStop(engine)
        assertEquals(RunPhase.Finished, engine.state.phase)
        assertFalse(engine.state.completedStudy)
        assertEquals(1, engine.state.answers.size)
        assertEquals(3, engine.state.questions.size)
        engine.retryMistakes()
        assertEquals(listOf(questions[0]), engine.state.questions)
    }

    @Test
    fun generatedObjectsStayOutsideAllQuestionCorridors() {
        val engine = RunnerEngine(RunnerCourse { _, distance -> listOf(TrackObject(0, distance, ObstacleKind.Coin)) }).apply { start(questions) }
        repeat(3) {
            advanceToStop(engine)
            for (item in engine.state.objects) {
                questions.indices.forEach { index ->
                    val gate = RunnerState.FIRST_GATE + index * RunnerState.GATE_SPACING
                    assertFalse(item.distance in (gate - 30f)..(gate + 24f))
                }
            }
            answer(engine, 1)
            engine.continueAfterAnswer()
        }
    }

    @Test
    fun questionBoundaryMatchesAtThirtyAndOneHundredTwentyFramesPerSecond() {
        val slow = studyRun()
        val fast = studyRun()
        advanceToStop(slow, 1.0 / 30.0)
        advanceToStop(fast, 1.0 / 120.0)
        assertEquals(slow.state, fast.state)
    }

    @Test
    fun restartClearsPendingSubmissionAndFreeModeHasNoQuestionStops() {
        val engine = studyRun()
        advanceToStop(engine)
        engine.selectAnswer(0)
        engine.confirmAnswer()
        engine.advance(0.2)
        engine.start()
        assertTrue(engine.state.questions.isEmpty())
        assertTrue(engine.state.answers.isEmpty())
        assertNull(engine.state.selectedAnswer)
        repeat(100) { engine.advance(0.1) }
        assertEquals(RunPhase.Running, engine.state.phase)
        assertTrue(engine.state.distance > 100f)
    }

    @Test
    fun leavingPausedStudyAllowsModeSwitchWithoutStaleAnswers() {
        val engine = studyRun()
        advanceToStop(engine)
        answer(engine, 1)
        engine.pause()
        val score = engine.state.score
        engine.returnToReady()
        assertEquals(RunPhase.Ready, engine.state.phase)
        assertTrue(engine.state.questions.isEmpty())
        assertTrue(engine.state.answers.isEmpty())
        assertEquals(score, engine.state.bestScore)
        engine.start()
        repeat(100) { engine.advance(0.1) }
        assertEquals(RunPhase.Running, engine.state.phase)
        assertTrue(engine.state.distance > 100f)
    }

    @Test
    fun malformedQuestionsAndDuplicateIdsAreRejectedBeforeRunStarts() {
        val question = questions.first()
        assertFailsWith<IllegalArgumentException> { question.copy(choices = listOf("A", "B")) }
        assertFailsWith<IllegalArgumentException> { question.copy(choices = listOf("A", "A", "C")) }
        assertFailsWith<IllegalArgumentException> { question.copy(correctChoice = 3) }
        assertFailsWith<IllegalArgumentException> { question.copy(sourceNote = "") }
        val engine = RunnerEngine(emptyCourse)
        val ready = engine.state
        assertFailsWith<IllegalArgumentException> { engine.start(listOf(question, question)) }
        assertEquals(ready, engine.state)
    }

    private fun studyRun() = RunnerEngine(emptyCourse).apply { start(questions) }

    private fun answer(engine: RunnerEngine, choice: Int) {
        engine.selectAnswer(choice)
        engine.confirmAnswer()
        advanceToStop(engine)
        assertEquals(RunPhase.AnswerFeedback, engine.state.phase)
    }

    private fun advanceToStop(engine: RunnerEngine, dt: Double = 0.1) {
        var frames = 0
        while (engine.state.isMoving) {
            engine.advance(dt)
            check(frames++ < 10_000) { "Run failed to stop at a question or finish" }
        }
    }

    private fun pauseAndResume(engine: RunnerEngine) {
        val before = engine.state
        engine.pause()
        val paused = engine.state
        engine.pause()
        engine.selectAnswer(0)
        engine.confirmAnswer()
        engine.continueAfterAnswer()
        repeat(20) { engine.advance(0.25) }
        assertEquals(paused, engine.state)
        engine.resume()
        assertEquals(before.phase, engine.state.phase)
        assertEquals(before.distance, engine.state.distance)
        assertEquals(before.answers, engine.state.answers)
        assertEquals(before.selectedAnswer, engine.state.selectedAnswer)
    }
}
