package com.example.study_helper.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunnerEngineTest {
    private val emptyCourse = RunnerCourse { _, _ -> emptyList() }

    @Test
    fun readyAndPausedGamesIgnoreTimeAndControls() {
        val engine = RunnerEngine(emptyCourse)
        val ready = engine.state
        engine.advance(0.2)
        engine.move(-1)
        engine.jump()
        assertEquals(ready, engine.state)
        engine.start()
        engine.jump()
        engine.advance(0.1)
        engine.pause()
        val paused = engine.state
        repeat(30) { engine.advance(0.2) }
        engine.move(1)
        engine.slide()
        engine.jump()
        assertEquals(paused, engine.state)
        engine.resume()
        engine.advance(0.1)
        assertTrue(engine.state.distance > paused.distance)
        assertTrue(engine.state.jumpRemaining < paused.jumpRemaining)
    }

    @Test
    fun movementIsBoundedAndVisiblePositionCatchesUp() {
        val engine = RunnerEngine(emptyCourse)
        engine.start()
        repeat(4) { engine.move(-1) }
        engine.advance(0.2)
        assertEquals(0, engine.state.lane)
        assertEquals(0f, engine.state.visualLane)
        repeat(6) { engine.move(1) }
        engine.advance(0.25)
        assertEquals(2, engine.state.lane)
        assertEquals(2f, engine.state.visualLane, 0.001f)
    }

    @Test
    fun coinIsCollectedOnceAndOnlyInItsLane() {
        val engine = RunnerEngine(RunnerCourse { index, distance ->
            if (index == 0) listOf(TrackObject(1, distance, ObstacleKind.Coin), TrackObject(0, distance, ObstacleKind.Coin)) else emptyList()
        })
        engine.start()
        advanceTo(engine, 43f)
        assertEquals(1, engine.state.coins)
        advanceTo(engine, 49f)
        assertEquals(1, engine.state.coins)
        assertEquals(engine.state.distance.toInt() + 25, engine.state.score)
    }

    @Test
    fun jumpClearsLowWallButSlideDoesNot() {
        val jumping = singleObstacle(ObstacleKind.Hurdle)
        advanceTo(jumping, 37f)
        jumping.jump()
        advanceTo(jumping, 43f)
        assertEquals(3, jumping.state.health)
        assertEquals("멋진 회피!", jumping.state.feedback)

        val sliding = singleObstacle(ObstacleKind.Hurdle)
        advanceTo(sliding, 37f)
        sliding.slide()
        advanceTo(sliding, 43f)
        assertEquals(2, sliding.state.health)
    }

    @Test
    fun slideClearsArchButJumpDoesNot() {
        val sliding = singleObstacle(ObstacleKind.Arch)
        advanceTo(sliding, 37f)
        sliding.slide()
        advanceTo(sliding, 43f)
        assertEquals(3, sliding.state.health)
        val jumping = singleObstacle(ObstacleKind.Arch)
        advanceTo(jumping, 37f)
        jumping.jump()
        advanceTo(jumping, 43f)
        assertEquals(2, jumping.state.health)
    }

    @Test
    fun crateRequiresLaneChangeAndCannotBeJumped() {
        val dodging = singleObstacle(ObstacleKind.Crate)
        advanceTo(dodging, 37f)
        dodging.move(-1)
        advanceTo(dodging, 43f)
        assertEquals(3, dodging.state.health)
        val jumping = singleObstacle(ObstacleKind.Crate)
        advanceTo(jumping, 37f)
        jumping.jump()
        advanceTo(jumping, 43f)
        assertEquals(2, jumping.state.health)
    }

    @Test
    fun lastMomentLaneCommandDoesNotTeleportPastCollision() {
        val engine = singleObstacle(ObstacleKind.Crate)
        advanceTo(engine, 41.8f)
        engine.move(-1)
        engine.advance(0.025)
        assertEquals(2, engine.state.health)
    }

    @Test
    fun threeHitsEndRunAndRestartResetsRunButKeepsSessionBest() {
        val engine = RunnerEngine(RunnerCourse { _, distance -> listOf(TrackObject(1, distance, ObstacleKind.Crate)) })
        engine.start()
        advanceTo(engine, 100f)
        assertEquals(RunPhase.Finished, engine.state.phase)
        assertEquals(0, engine.state.health)
        val finished = engine.state
        engine.advance(0.2)
        engine.move(1)
        assertEquals(finished, engine.state)
        engine.start()
        assertEquals(RunPhase.Running, engine.state.phase)
        assertEquals(3, engine.state.health)
        assertEquals(0f, engine.state.distance)
        assertEquals(0, engine.state.coins)
        assertEquals(0f, engine.state.invulnerability)
        assertEquals(finished.score, engine.state.bestScore)
    }

    @Test
    fun fixedStepsProduceSameResultAtDifferentFrameRates() {
        val slow = RunnerEngine(emptyCourse).apply { start() }
        val fast = RunnerEngine(emptyCourse).apply { start() }
        repeat(360) { slow.advance(1.0 / 30.0) }
        repeat(1440) { fast.advance(1.0 / 120.0) }
        assertEquals(slow.state, fast.state)
    }

    @Test
    fun stalledOrInvalidFramesDoNotTeleportRunner() {
        val engine = RunnerEngine(emptyCourse).apply { start() }
        val start = engine.state
        engine.advance(Double.NaN)
        engine.advance(Double.POSITIVE_INFINITY)
        engine.advance(-1.0)
        assertEquals(start, engine.state)
        engine.advance(60.0)
        assertTrue(engine.state.distance < 4f)
    }

    @Test
    fun actionsCannotBeStackedOrExtendedByRepeatedPresses() {
        val engine = RunnerEngine(emptyCourse).apply { start() }
        engine.jump()
        engine.advance(0.2)
        val remaining = engine.state.jumpRemaining
        engine.jump()
        engine.slide()
        assertEquals(remaining, engine.state.jumpRemaining)
        assertFalse(engine.state.isSliding)
        repeat(5) { engine.advance(0.2) }
        engine.slide()
        engine.advance(0.2)
        val slideRemaining = engine.state.slideRemaining
        engine.slide()
        engine.jump()
        assertEquals(slideRemaining, engine.state.slideRemaining)
        assertEquals(0f, engine.state.jumpRemaining)
    }

    @Test
    fun generatedRowsAlwaysHaveClearCoinLaneAndStayReproducible() {
        repeat(10) { seed ->
            val course = EndlessCourse(seed)
            repeat(100) { row ->
                val distance = 42f + row * 24f
                val items = course.objectsForRow(row, distance)
                assertEquals(items, EndlessCourse(seed).objectsForRow(row, distance))
                assertTrue(items.all { it.lane in 0..2 })
                val blocked = items.filter { it.kind != ObstacleKind.Coin }.map { it.lane }.toSet()
                assertTrue(blocked.size in 1..2)
                assertTrue(items.filter { it.kind == ObstacleKind.Coin }.all { it.lane !in blocked })
            }
        }
    }

    @Test
    fun longRunsKeepOnlyNearbyObjectsAndCapSpeed() {
        val engine = RunnerEngine(RunnerCourse { _, distance -> listOf(TrackObject(0, distance, ObstacleKind.Coin)) })
        engine.start()
        repeat(12_000) { engine.advance(0.1) }
        assertEquals(RunPhase.Running, engine.state.phase)
        assertTrue(engine.state.objects.size <= 7)
        assertEquals(28f, engine.state.speed)
    }

    private fun singleObstacle(kind: ObstacleKind): RunnerEngine = RunnerEngine(
        RunnerCourse { index, distance -> if (index == 0) listOf(TrackObject(1, distance, kind)) else emptyList() },
    ).apply { start() }

    private fun advanceTo(engine: RunnerEngine, distance: Float) {
        var frames = 0
        while (engine.state.distance < distance && engine.state.phase == RunPhase.Running) {
            engine.advance(1.0 / 120.0)
            check(frames++ < 100_000) { "Run failed to advance" }
        }
    }
}
