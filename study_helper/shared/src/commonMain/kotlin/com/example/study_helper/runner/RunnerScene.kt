package com.example.study_helper.runner

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal val Ink = Color(0xFF102A29)
internal val Cream = Color(0xFFF6F2DE)
internal val Gold = Color(0xFFEAC878)
internal val Muted = Color(0xFF9BB6AB)

@Composable
internal fun RunnerScene(state: () -> RunnerState, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds()) { drawWorld(state()) }
}

private class TrackProjection(val width: Float, val height: Float) {
    val horizon = height * 0.21f
    val ground = height * 0.84f
    fun scale(ahead: Float): Float = 0.055f + 0.945f *
        (1f - ahead / RunnerState.VIEW_DISTANCE).coerceIn(0f, 1.18f).pow(2.3f)
    fun point(lane: Float, ahead: Float): Offset {
        val scale = scale(ahead)
        return Offset(width / 2 + (lane - 1f) * width * 0.265f * scale, horizon + (ground - horizon) * scale)
    }
}

private fun DrawScope.drawWorld(state: RunnerState) {
    val w = size.width
    val h = size.height
    if (w <= 0 || h <= 0) return
    val p = TrackProjection(w, h)
    drawRect(Brush.verticalGradient(listOf(Ink, Color(0xFF397267), Color(0xFF173E38), Ink)))

    // Distant sun, layered forest, and a ruined gate at the vanishing point.
    drawCircle(Color(0xFFEFDAA4).copy(alpha = 0.12f), w * 0.21f, Offset(w * 0.5f, h * 0.2f))
    drawCircle(Color(0xFFEFDAA4).copy(alpha = 0.18f), w * 0.12f, Offset(w * 0.5f, h * 0.2f))
    repeat(3) { layer ->
        val color = listOf(Color(0xFF397466), Color(0xFF285A50), Color(0xFF1D463F))[layer]
        repeat(9) { i ->
            val x = i * w / 7 - w * 0.08f + layer * w * 0.03f
            val y = h * (0.1f + layer * 0.035f + (i % 3) * 0.035f)
            polygon(color, Offset(x - w * 0.13f, h * 0.57f), Offset(x - w * 0.03f, y),
                Offset(x + w * 0.02f, y - h * 0.04f), Offset(x + w * 0.13f, h * 0.57f))
        }
    }
    val gateX = w / 2
    val gateY = p.horizon + h * 0.06f
    val gateWidth = w * 0.16f
    drawRect(Color(0xFF678575), Offset(gateX - gateWidth, gateY - h * 0.19f), Size(gateWidth * 2, h * 0.055f))
    drawRect(Color(0xFF587A68), Offset(gateX - gateWidth * 0.83f, gateY - h * 0.17f), Size(gateWidth * 0.33f, h * 0.2f))
    drawRect(Color(0xFF587A68), Offset(gateX + gateWidth * 0.5f, gateY - h * 0.17f), Size(gateWidth * 0.33f, h * 0.2f))
    drawLine(Gold.copy(alpha = 0.55f), Offset(gateX - gateWidth * 0.7f, gateY - h * 0.17f), Offset(gateX + gateWidth * 0.7f, gateY - h * 0.17f), 2f)

    // Stone bridge with a continuous three-lane perspective grid.
    polygon(Color(0xFF6B7862), p.point(-0.65f, 110f), p.point(2.65f, 110f), p.point(2.65f, -16f), p.point(-0.65f, -16f))
    polygon(Color(0xFF3E5148), p.point(-0.5f, 110f), p.point(2.5f, 110f), p.point(2.5f, -16f), p.point(-0.5f, -16f))
    polygon(Color(0xFF495C50), p.point(0.5f, 110f), p.point(1.5f, 110f), p.point(1.5f, -16f), p.point(0.5f, -16f))
    for (lane in listOf(-0.5f, 0.5f, 1.5f, 2.5f)) {
        drawLine(Color(0xFFB5B492).copy(alpha = 0.35f), p.point(lane, 110f), p.point(lane, -16f), max(1f, w * 0.003f))
    }
    repeat(20) { i ->
        val z = i * 7f - state.distance % 7f - 12f
        val s = p.scale(z)
        drawLine(Color(0xFF162F2A).copy(alpha = 0.55f), p.point(-0.5f, z), p.point(2.5f, z), max(1f, s * 3f))
        if (i % 2 == 0) {
            val lane = (i % 3).toFloat()
            drawLine(Color(0xFF92A181).copy(alpha = 0.28f), p.point(lane - 0.25f, z + 1), p.point(lane + 0.18f, z + 1), max(1f, s * 2f))
        }
    }

    // Repeating roadside columns and leaves provide motion even in an empty lane.
    for (i in 8 downTo 0) {
        val z = i * 17f - state.distance % 17f - 5f
        val scale = p.scale(z)
        for (side in listOf(-1f, 3f)) {
            val base = p.point(side, z)
            val columnW = w * 0.045f * scale
            val columnH = h * 0.26f * scale
            drawRect(Color(0xFF193B34), Offset(base.x - columnW, base.y - columnH), Size(columnW * 2, columnH))
            drawRect(Color(0xFF426452), Offset(base.x - columnW, base.y - columnH), Size(columnW * 0.65f, columnH))
            drawRect(Color(0xFF6C8463), Offset(base.x - columnW * 1.4f, base.y - columnH), Size(columnW * 2.8f, columnW * 0.65f))
            drawCircle(Gold.copy(alpha = 0.12f), columnW * 2, Offset(base.x, base.y - columnH * 0.7f))
            drawCircle(Gold, max(1f, columnW * 0.28f), Offset(base.x, base.y - columnH * 0.7f))
            val leafColor = Color(0xFF255543)
            polygon(leafColor, base, Offset(base.x - columnW * 3.5f, base.y - columnH * 0.4f), Offset(base.x - columnW, base.y - columnH * 0.07f))
            polygon(leafColor, base, Offset(base.x + columnW * 3, base.y - columnH * 0.55f), Offset(base.x + columnW, base.y - columnH * 0.1f))
        }
    }

    val objects = if (state.phase == RunPhase.Ready) listOf(
        TrackObject(0, 30f, ObstacleKind.Coin), TrackObject(0, 38f, ObstacleKind.Coin),
        TrackObject(0, 46f, ObstacleKind.Coin), TrackObject(1, 57f, ObstacleKind.Hurdle),
        TrackObject(2, 82f, ObstacleKind.Arch),
    ) else state.objects
    val sorted = objects.sortedByDescending { it.distance }
    for (item in sorted.filter { it.distance >= state.distance }) {
        drawTrackObject(item, item.distance - state.distance, p)
    }
    drawQuizGates(state, p)
    drawRunner(state, p)
    for (item in sorted.filter { it.distance < state.distance && it.kind != ObstacleKind.Coin }) {
        drawTrackObject(item, item.distance - state.distance, p)
    }

    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.8f)), startY = h * 0.9f))
    if (state.invulnerability > 1.05f) drawRect(Color(0xFFE58C67).copy(alpha = (state.invulnerability - 1.05f) * 0.35f))
}

private fun DrawScope.drawQuizGates(state: RunnerState, p: TrackProjection) {
    val gate = state.gateDistance ?: return
    val ahead = gate - state.distance
    if (ahead !in 0f..RunnerState.VIEW_DISTANCE) return
    val phase = if (state.phase == RunPhase.Paused) state.phaseBeforePause else state.phase
    val revealed = phase == RunPhase.AnswerFeedback
    val correct = state.activeQuestion?.correctChoice
    val scale = p.scale(ahead)
    for (lane in 0..2) {
        val base = p.point(lane.toFloat(), ahead)
        val halfWidth = size.width * 0.103f * scale
        val gateHeight = min(size.width * 0.29f, size.height * 0.36f) * scale
        val color = when {
            revealed && lane == correct -> AnswerGreen
            revealed && lane == state.selectedAnswer -> AnswerOrange
            lane == state.selectedAnswer -> Gold
            else -> Color(0xFF8BAFA5)
        }
        drawRoundRect(
            color.copy(alpha = if (lane == state.selectedAnswer) 0.14f else 0.04f),
            Offset(base.x - halfWidth, base.y - gateHeight), Size(halfWidth * 2, gateHeight),
            CornerRadius(halfWidth * 0.2f),
        )
        drawLine(color, Offset(base.x - halfWidth, base.y), Offset(base.x - halfWidth, base.y - gateHeight), max(1f, halfWidth * 0.12f))
        drawLine(color, Offset(base.x + halfWidth, base.y), Offset(base.x + halfWidth, base.y - gateHeight), max(1f, halfWidth * 0.12f))
        drawRoundRect(color, Offset(base.x - halfWidth * 1.08f, base.y - gateHeight), Size(halfWidth * 2.16f, gateHeight * 0.32f), CornerRadius(halfWidth * 0.12f))
        drawLine(color.copy(alpha = 0.7f), Offset(base.x - halfWidth, base.y), Offset(base.x + halfWidth, base.y), max(1f, scale * 3f))
        val a = gateHeight * 0.19f
        withTransform({ translate(base.x, base.y - gateHeight * 0.84f) }) {
            val letter = Path().apply {
                when (lane) {
                    0 -> {
                        moveTo(-a * 0.38f, a * 0.5f); lineTo(0f, -a * 0.5f); lineTo(a * 0.38f, a * 0.5f)
                        moveTo(-a * 0.23f, a * 0.1f); lineTo(a * 0.23f, a * 0.1f)
                    }
                    1 -> {
                        moveTo(-a * 0.3f, a * 0.5f); lineTo(-a * 0.3f, -a * 0.5f)
                        cubicTo(a * 0.5f, -a * 0.6f, a * 0.5f, 0f, -a * 0.3f, 0f)
                        cubicTo(a * 0.55f, -a * 0.05f, a * 0.55f, a * 0.6f, -a * 0.3f, a * 0.5f)
                    }
                    else -> {
                        moveTo(a * 0.3f, -a * 0.4f)
                        cubicTo(-a * 0.65f, -a * 0.95f, -a * 0.65f, a * 0.95f, a * 0.3f, a * 0.4f)
                    }
                }
            }
            drawPath(letter, Ink, style = Stroke(max(1f, a * 0.13f), cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawTrackObject(item: TrackObject, ahead: Float, p: TrackProjection) {
    if (ahead > RunnerState.VIEW_DISTANCE || ahead < -12f) return
    val base = p.point(item.lane.toFloat(), ahead)
    val s = p.scale(ahead)
    val unit = min(size.width * 0.105f, size.height * 0.125f) * s
    val x = base.x
    val y = base.y
    drawOval(Color.Black.copy(alpha = 0.2f), Offset(x - unit, y - unit * 0.16f), Size(unit * 2, unit * 0.4f))
    when (item.kind) {
        ObstacleKind.Coin -> {
            drawCircle(Gold.copy(alpha = 0.09f), unit * 0.72f, Offset(x, y - unit * 0.7f))
            drawOval(Color(0xFFAC7138), Offset(x - unit * 0.33f, y - unit * 1.25f), Size(unit * 0.78f, unit * 0.9f))
            drawOval(Gold, Offset(x - unit * 0.42f, y - unit * 1.25f), Size(unit * 0.7f, unit * 0.9f))
            drawOval(Color(0xFFFFEAB1), Offset(x - unit * 0.29f, y - unit * 1.12f), Size(unit * 0.44f, unit * 0.63f), style = Stroke(max(1f, unit * 0.055f)))
        }
        ObstacleKind.Crate -> {
            val front = Color(0xFFBA7857)
            polygon(Color(0xFF80523E), Offset(x + unit * 0.8f, y), Offset(x + unit * 1.04f, y - unit * 0.25f), Offset(x + unit * 1.04f, y - unit * 1.75f), Offset(x + unit * 0.8f, y - unit * 1.5f))
            drawRect(front, Offset(x - unit * 0.8f, y - unit * 1.5f), Size(unit * 1.6f, unit * 1.5f))
            polygon(Color(0xFFE0A273), Offset(x - unit * 0.8f, y - unit * 1.5f), Offset(x - unit * 0.56f, y - unit * 1.75f), Offset(x + unit * 1.04f, y - unit * 1.75f), Offset(x + unit * 0.8f, y - unit * 1.5f))
            drawRect(Color(0xFFEFBC86), Offset(x - unit * 0.65f, y - unit * 1.36f), Size(unit * 1.3f, unit * 1.22f), style = Stroke(max(1f, unit * 0.1f)))
            drawLine(Color(0xFFEFBC86), Offset(x - unit * 0.59f, y - unit * 0.2f), Offset(x + unit * 0.6f, y - unit * 1.3f), unit * 0.13f)
        }
        ObstacleKind.Hurdle -> {
            drawRect(Color(0xFFAD8248), Offset(x - unit, y - unit * 0.65f), Size(unit * 2, unit * 0.65f))
            polygon(Gold, Offset(x - unit, y - unit * 0.65f), Offset(x - unit * 0.7f, y - unit * 0.88f), Offset(x + unit * 1.2f, y - unit * 0.88f), Offset(x + unit, y - unit * 0.65f))
            for (i in -1..1) {
                val dx = i * unit * 0.55f
                drawLine(Color(0xFFF8D990), Offset(x + dx - unit * 0.13f, y - unit * 0.18f), Offset(x + dx, y - unit * 0.38f), max(1f, unit * 0.065f))
                drawLine(Color(0xFFF8D990), Offset(x + dx, y - unit * 0.38f), Offset(x + dx + unit * 0.13f, y - unit * 0.18f), max(1f, unit * 0.065f))
            }
        }
        ObstacleKind.Arch -> {
            val color = Color(0xFF7BA99C)
            drawRect(color, Offset(x - unit * 1.08f, y - unit * 2.1f), Size(unit * 0.28f, unit * 2.1f))
            drawRect(color, Offset(x + unit * 0.8f, y - unit * 2.1f), Size(unit * 0.28f, unit * 2.1f))
            drawRect(Color(0xFF53887E), Offset(x - unit * 1.18f, y - unit * 2.15f), Size(unit * 2.36f, unit * 1.13f))
            drawRect(Color(0xFFA2CDB9), Offset(x - unit * 1.18f, y - unit * 2.15f), Size(unit * 2.36f, unit * 0.15f))
            drawLine(Cream, Offset(x - unit * 0.21f, y - unit * 1.65f), Offset(x, y - unit * 1.42f), max(1f, unit * 0.065f))
            drawLine(Cream, Offset(x, y - unit * 1.42f), Offset(x + unit * 0.21f, y - unit * 1.65f), max(1f, unit * 0.065f))
        }
    }
}

private fun DrawScope.drawRunner(state: RunnerState, p: TrackProjection) {
    val base = p.point(state.visualLane, 0f)
    val u = min(size.width * 0.105f, size.height * 0.13f)
    val bounce = if (state.isMoving && state.jumpHeight == 0f && !state.isSliding)
        sin(state.elapsed * 19f) * u * 0.04f else 0f
    val feetY = base.y - state.jumpHeight * u * 1.6f + bounce
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(base.x - u * 0.6f, base.y - u * 0.06f), Size(u * 1.2f, u * 0.32f))
    if (state.phase == RunPhase.Running && state.invulnerability > 0f && (state.elapsed * 12f).toInt() % 2 == 0) return
    withTransform({
        translate(base.x, feetY)
        if (state.isSliding) {
            translate(0f, -u * 0.25f)
            rotate(65f, Offset.Zero)
            scale(0.8f, 0.8f, Offset.Zero)
        }
    }) {
        val stride = if (state.isMoving && state.jumpHeight == 0f) sin(state.elapsed * 19f) * u * 0.27f else 0f
        val pants = Color(0xFF172F36)
        drawLine(pants, Offset(-u * 0.16f, -u * 0.72f), Offset(-u * 0.23f - stride, -u * 0.07f), u * 0.25f, StrokeCap.Round)
        drawLine(pants, Offset(u * 0.16f, -u * 0.72f), Offset(u * 0.23f + stride, -u * 0.07f), u * 0.25f, StrokeCap.Round)
        drawLine(Cream, Offset(-u * 0.23f - stride, -u * 0.07f), Offset(-u * 0.23f - stride, 0f), u * 0.27f, StrokeCap.Round)
        drawLine(Cream, Offset(u * 0.23f + stride, -u * 0.07f), Offset(u * 0.23f + stride, 0f), u * 0.27f, StrokeCap.Round)
        drawLine(Color(0xFF73D0B5), Offset(-u * 0.32f, -u * 1.35f), Offset(-u * 0.52f, -u * 0.75f + stride), u * 0.22f, StrokeCap.Round)
        drawLine(Color(0xFF73D0B5), Offset(u * 0.32f, -u * 1.35f), Offset(u * 0.52f, -u * 0.75f - stride), u * 0.22f, StrokeCap.Round)
        drawRoundRect(Color(0xFF63B9A1), Offset(-u * 0.35f, -u * 1.47f), Size(u * 0.7f, u * 0.9f), CornerRadius(u * 0.2f))
        polygon(Color(0xFFEAB36B), Offset(u * 0.13f, -u * 1.5f), Offset(u * 0.95f, -u * 1.35f + stride * 0.4f), Offset(u * 0.77f, -u * 1.56f), Offset(u * 0.24f, -u * 1.63f))
        drawRoundRect(Color(0xFFD8A461), Offset(-u * 0.25f, -u * 1.33f), Size(u * 0.5f, u * 0.62f), CornerRadius(u * 0.12f))
        drawLine(Color(0xFF8E6341), Offset(-u * 0.16f, -u * 0.99f), Offset(u * 0.16f, -u * 0.99f), u * 0.06f)
        drawCircle(Color(0xFFE0AC82), u * 0.29f, Offset(0f, -u * 1.7f))
        drawRoundRect(Color(0xFF243936), Offset(-u * 0.29f, -u * 2.02f), Size(u * 0.58f, u * 0.34f), CornerRadius(u * 0.13f))
        drawLine(Gold, Offset(-u * 0.3f, -u * 1.75f), Offset(u * 0.3f, -u * 1.75f), u * 0.07f)
    }
}

private fun DrawScope.polygon(color: Color, a: Offset, b: Offset, c: Offset, d: Offset? = null) {
    drawPath(Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        d?.let { lineTo(it.x, it.y) }
        close()
    }, color)
}
