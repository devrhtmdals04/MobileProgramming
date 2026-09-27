package com.example.study_helper.runner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs

@Composable
fun RunnerScreen(model: RunnerViewModel = viewModel { RunnerViewModel() }) {
    val phase by remember(model) { derivedStateOf { model.state.phase } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) model.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.pause()
        }
    }
    LaunchedEffect(phase, model) {
        if (!model.state.isMoving) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (model.state.isMoving) {
            val now = withFrameNanos { it }
            model.advance((now - previous) / 1_000_000_000.0)
            previous = now
        }
    }

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxSize().safeDrawingPadding()) {
            val compact = maxHeight < 520.dp
            val displayPhase = if (phase == RunPhase.Paused) model.state.phaseBeforePause else phase
            Column(Modifier.fillMaxSize()) {
                Header(model, phase, compact)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    RunnerScene(
                        state = { model.state },
                        modifier = Modifier.fillMaxSize().pointerInput(model) {
                            val threshold = 26.dp.toPx()
                            var total = Offset.Zero
                            var handled = false
                            detectDragGestures(
                                onDragStart = { total = Offset.Zero; handled = false },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (!handled) {
                                        total += amount
                                        if (maxOf(abs(total.x), abs(total.y)) >= threshold) {
                                            if (abs(total.x) > abs(total.y)) {
                                                model.move(if (total.x > 0) 1 else -1)
                                            } else if (total.y < 0) model.jump() else model.slide()
                                            handled = true
                                        }
                                    }
                                },
                            )
                        },
                    )
                    if (phase == RunPhase.Ready && !compact) {
                        Column(
                            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("THE LOST PATH", color = Gold, fontSize = 10.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
                            Text("유적의 길", color = Cream, fontSize = 38.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp))
                            Text("달리고, 고르고, 기억하고.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (phase == RunPhase.Running) RunHint(model, Modifier.align(Alignment.TopCenter))
                    if (displayPhase in listOf(RunPhase.Question, RunPhase.PassingGate, RunPhase.AnswerFeedback)) {
                        QuizPrompt(model.state, compact, Modifier.align(Alignment.TopCenter))
                    }
                }
                when (displayPhase) {
                    RunPhase.Ready -> ReadyFooter(model, compact)
                    RunPhase.Question, RunPhase.PassingGate -> QuizChoices(model, compact)
                    RunPhase.AnswerFeedback -> QuizFeedback(model, compact)
                    else -> Controls(model, phase == RunPhase.Running, compact)
                }
            }
            if (phase == RunPhase.Paused || phase == RunPhase.Finished) {
                RunOverlay(model, phase)
            }
        }
    }
}

@Composable
private fun Header(model: RunnerViewModel, phase: RunPhase, compact: Boolean) {
    val distance by remember(model) { derivedStateOf { model.state.distance.toInt() } }
    val score by remember(model) { derivedStateOf { model.state.score } }
    val coins by remember(model) { derivedStateOf { model.state.coins } }
    val health by remember(model) { derivedStateOf { model.state.health } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 6.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = Gold, modifier = Modifier.size(27.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("S", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp) }
                }
                Text("STUDY RUN", color = Cream, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 2.sp)
            }
            if (phase == RunPhase.Ready) {
                Surface(shape = CircleShape, color = Color(0xFF214039), border = BorderStroke(1.dp, Muted.copy(alpha = 0.25f))) {
                    Text("02 / QUIZ RUN", color = Gold, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            } else {
                Surface(
                    onClick = model::pause, enabled = phase != RunPhase.Paused && phase != RunPhase.Finished,
                    shape = CircleShape, color = Color(0xFF23443D),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "일시정지" },
                ) { Box(contentAlignment = Alignment.Center) { Text("Ⅱ", color = Cream, fontSize = 20.sp) } }
            }
        }
        if (phase != RunPhase.Ready) {
            Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(distance.toString().padStart(3, '0'), color = Cream, fontSize = if (compact) 26.sp else 38.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("m", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 7.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("●  $coins     $score PT", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.padding(top = 5.dp).semantics { contentDescription = "남은 체력 $health / 3" }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(3) { index ->
                            Canvas(Modifier.size(18.dp)) {
                                val w = size.width
                                val h = size.height
                                val heart = Path().apply {
                                    moveTo(w * 0.5f, h * 0.9f)
                                    cubicTo(-w * 0.28f, h * 0.4f, w * 0.05f, -h * 0.18f, w * 0.5f, h * 0.22f)
                                    cubicTo(w * 0.95f, -h * 0.18f, w * 1.28f, h * 0.4f, w * 0.5f, h * 0.9f)
                                    close()
                                }
                                drawPath(heart, if (index < health) Color(0xFFE8A184) else Color(0xFF37534A))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyFooter(model: RunnerViewModel, compact: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Instruction("↔", "레인 이동")
                Instruction("↑", "점프")
                Instruction("↓", "슬라이드")
            }
        }
        if (!compact) ExampleSetLabel()
        PrimaryAction("예제 문제로 달리기  →", model::startStudy)
        TextButton(onClick = model::startFreeRun) { Text("문제 없이 자유 달리기", color = Muted, fontSize = 12.sp) }
        if (!compact) {
            Text("문제 앞에서 잠시 멈춰요. 정답 레인을 골라보세요.", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Instruction(symbol: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(symbol, color = Gold, fontSize = 23.sp)
        Text(label, color = Cream, fontSize = 12.sp)
    }
}

@Composable
private fun Controls(model: RunnerViewModel, enabled: Boolean, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val actions = listOf<Triple<String, String, () -> Unit>>(
            Triple("←", "왼쪽", { model.move(-1) }), Triple("↑", "점프", model::jump),
            Triple("↓", "슬라이드", model::slide), Triple("→", "오른쪽", { model.move(1) }),
        )
        for ((symbol, label, action) in actions) {
            Surface(
                onClick = action, enabled = enabled, color = Color(0xFF1C3934), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF355248)),
                modifier = Modifier.weight(1f).heightIn(min = if (compact) 48.dp else 66.dp)
                    .semantics { contentDescription = label },
            ) {
                Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(symbol, color = if (enabled) Gold else Muted, fontSize = 22.sp)
                    if (!compact) Text(label, color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun RunHint(model: RunnerViewModel, modifier: Modifier) {
    val hint by remember(model) {
        derivedStateOf {
            val state = model.state
            val untilQuestion = state.gateDistance?.minus(RunnerState.READING_DISTANCE)?.minus(state.distance)
            when {
                state.feedbackRemaining > 0 -> state.feedback
                untilQuestion != null && untilQuestion < 30f ->
                    "${untilQuestion.toInt().coerceAtLeast(0)} m 뒤 예제 문제 · ${state.answers.size}/${state.questions.size} 완료"
                state.distance < 35 -> "좌우로 스와이프해 코인을 따라가세요"
                state.distance < 75 -> "↑ 낮은 벽은 점프   ↓ 높은 문은 슬라이드"
                else -> ""
            }
        }
    }
    if (hint.isNotEmpty()) {
        Surface(modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Ink.copy(alpha = 0.88f), shape = CircleShape) {
            Text(hint, color = Cream, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun RunOverlay(model: RunnerViewModel, phase: RunPhase) {
    // A clickable scrim consumes touches so paused/result screens cannot control the runner.
    Surface(onClick = {}, color = Ink.copy(alpha = 0.84f), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF1C3D36), border = BorderStroke(1.dp, Color(0xFF446052)), modifier = Modifier.widthIn(max = 360.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (phase == RunPhase.Paused) "TAKE A BREATH" else "RUN COMPLETE", color = Gold, letterSpacing = 2.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(when {
                        phase == RunPhase.Paused -> "잠시 쉬어가기"
                        model.state.completedStudy -> "학습 달리기 완료!"
                        else -> "멋진 달리기였어요!"
                    }, color = Cream, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    Text(if (phase == RunPhase.Paused) "준비되면 이어서 진행하세요." else "달리기와 학습 결과를 확인해 보세요.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("${model.state.score}", color = Gold, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 48.sp)
                    Text("TOTAL SCORE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ResultStat("거리", "${model.state.distance.toInt()} m")
                        ResultStat("코인", "${model.state.coins}")
                        ResultStat("이번 실행 최고", "${maxOf(model.state.bestScore, model.state.score)}")
                    }
                    StudyRunSummary(model.state)
                    PrimaryAction(if (phase == RunPhase.Paused) "계속하기  →" else "다시 달리기  ↻", if (phase == RunPhase.Paused) model::resume else model::restart)
                    if (phase == RunPhase.Paused) TextButton(onClick = model::restart, modifier = Modifier.padding(top = 6.dp)) {
                        Text("처음부터 다시", color = Muted, fontSize = 12.sp)
                    }
                    if (phase == RunPhase.Finished && model.state.answers.any { !it.isCorrect }) {
                        TextButton(onClick = model::retryMistakes, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text("오답만 다시 달리기", color = Gold, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = model::returnToReady) { Text("시작 화면으로", color = Muted, fontSize = 12.sp) }
                    if (phase == RunPhase.Finished) MistakeReview(model.state)
                }
            }
        }
    }
}

@Composable
private fun ResultStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun PrimaryAction(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 6.dp))
    }
}
