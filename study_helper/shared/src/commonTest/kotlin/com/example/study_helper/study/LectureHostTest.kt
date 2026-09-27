package com.example.study_helper.study

import kotlin.test.*

class LectureHostTest {
    private class Platform : LecturePlatform {
        val commands = mutableListOf<Triple<String, String, String>>()
        override fun audioCommand(action: String, id: String, title: String) { commands += Triple(action, id, title) }
    }
    @Test fun recordingBlocksDestructiveAndConflictingActionsButAllowsStopAndNavigation() {
        val platform = Platform(); val host = LectureHost(platform)
        host.updateState("""{"recording":true,"seconds":123}""")
        listOf("start", "play", "rename", "delete").forEach { host.command(it, "id") }
        assertTrue(platform.commands.isEmpty())
        host.command("stop"); host.command("close")
        assertEquals(listOf("stop", "close"), platform.commands.map { it.first })
        assertEquals(123, host.state.seconds)
    }
    @Test fun permissionPendingPreventsDuplicateStart() {
        val platform = Platform(); val host = LectureHost(platform)
        host.updateState("""{"pending":true}""")
        host.command("start"); host.command("delete", "id")
        assertTrue(platform.commands.isEmpty())
        host.updateState("""{"message":"권한이 필요해요"}""")
        host.command("start", title = "  디지털시스템입문  ")
        assertEquals("디지털시스템입문", platform.commands.single().third)
    }
    @Test fun invalidSnapshotDoesNotLoseActiveRecordingStateOrSavedList() {
        val host = LectureHost(Platform())
        host.updateState("""{"recording":true,"recordings":[{"id":"a","title":"강의","date":"2026-09-27","seconds":60}]}""")
        host.updateState("broken")
        assertTrue(host.state.recording)
        assertEquals("강의", host.state.recordings.single().title)
        assertTrue(host.state.message.isNotBlank())
    }
    @Test fun playbackAndCompletedRecordingSnapshotsReplaceActiveState() {
        val host = LectureHost(Platform())
        host.updateState("""{"recording":true,"seconds":30}""")
        host.updateState("""{"playing":"a","playbackSeconds":15,"recordings":[{"id":"a","title":"강의","date":"today","seconds":30}]}""")
        assertFalse(host.state.recording)
        assertEquals("a", host.state.playing)
        assertEquals(15, host.state.playbackSeconds)
        assertEquals(30, host.state.recordings.single().seconds)
    }
}
