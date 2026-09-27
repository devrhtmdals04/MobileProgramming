package com.example.study_helper.runner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.study_helper.study.ExampleStudySet

class RunnerViewModel : ViewModel() {
    private val engine = RunnerEngine()
    var state by mutableStateOf(engine.state)
        private set

    fun startStudy() = update { start(ExampleStudySet.questions) }
    fun startFreeRun() = update { start() }
    fun restart() = update { start(state.questions) }
    fun pause() = update { pause() }
    fun resume() = update { resume() }
    fun returnToReady() = update { returnToReady() }
    fun move(direction: Int) = update { move(direction) }
    fun jump() = update { jump() }
    fun slide() = update { slide() }
    fun advance(seconds: Double) = update { advance(seconds) }
    fun selectAnswer(choice: Int) = update { selectAnswer(choice) }
    fun confirmAnswer() = update { confirmAnswer() }
    fun continueAfterAnswer() = update { continueAfterAnswer() }
    fun retryMistakes() = update { retryMistakes() }

    private inline fun update(action: RunnerEngine.() -> Unit) {
        engine.action()
        state = engine.state
    }
}
