package com.example.study_helper.study

import kotlin.test.*

class LectureHostTest {
    @Test fun reviewKeepsDraftAndTimedSegmentsSeparateFromEditor() {
        val host=LectureHost(Platform())
        host.updateState("""{"reviewAvailable":true,"autoReview":true,"reviewContext":"전계, mA","editorDraft":"실시간 초안","editorText":"교정한 본문","editorSegments":[{"start":62.5,"end":65,"confidence":0.9,"text":"3.7mA"},{"start":66,"end":70,"confidence":0.3,"text":"용어"},{"start":71,"end":75,"confidence":0.9,"text":"보통 문장"}]}""")
        assertEquals("실시간 초안",host.state.editorDraft)
        assertEquals("교정한 본문",host.state.editorText)
        assertEquals(62,host.state.editorSegments.first().start)
        assertEquals(listOf(true,true,false),host.state.editorSegments.map {it.needsReview})
        assertTrue(host.state.autoReview)
    }
    @Test fun referenceContextIsBoundedAndCannotChangeWhileRecording() {
        val platform=Platform();val host=LectureHost(platform)
        host.command("setReviewContext",title="가".repeat(3000))
        assertEquals(2000,platform.commands.single().third.length)
        host.updateState("""{"recording":true}""")
        host.command("setReviewContext",title="변경");host.command("playSegment","id","60")
        assertEquals(1,platform.commands.size)
    }
    private class Platform : LecturePlatform {
        val commands = mutableListOf<Triple<String, String, String>>()
        override fun audioCommand(action: String, id: String, title: String) { commands += Triple(action, id, title) }
    }
    @Test fun recordingBlocksDestructiveAndConflictingActionsButAllowsStopAndNavigation() {
        val platform = Platform(); val host = LectureHost(platform)
        host.updateState("""{"recording":true,"seconds":123}""")
        listOf("start", "play", "rename", "delete", "transcribeLocal", "transcribeDevice", "downloadDeviceModel").forEach { host.command(it, "id") }
        assertTrue(platform.commands.isEmpty())
        host.command("stop"); host.command("close")
        assertEquals(listOf("stop", "close"), platform.commands.map { it.first })
        assertEquals(123, host.state.seconds)
    }
    @Test fun localConversionBlocksMutationsButAllowsCancellation() {
        val platform = Platform(); val host = LectureHost(platform)
        host.updateState("""{"pending":true,"localTranscriptionAvailable":true,"localTranscribing":true}""")
        assertTrue(host.state.localTranscriptionAvailable)
        assertTrue(host.state.localTranscribing)
        listOf("start", "delete", "saveTranscript", "transcribeLocal").forEach { host.command(it, "id") }
        assertTrue(platform.commands.isEmpty())
        host.command("cancelLocalTranscription")
        assertEquals("cancelLocalTranscription", platform.commands.single().first)
        host.updateState("""{}""")
        assertFalse(host.state.localTranscriptionAvailable)
        assertFalse(host.state.localTranscribing)
    }
    @Test fun offlineModelAndProgressAreOptionalAndClamped() {
        val host = LectureHost(Platform())
        host.updateState("""{"deviceTranscriptionAvailable":true,"deviceModelReady":true,"deviceTranscribing":true,"deviceProgress":120}""")
        assertTrue(host.state.deviceModelReady)
        assertTrue(host.state.deviceTranscribing)
        assertEquals(100, host.state.deviceProgress)
        host.updateState("""{}""")
        assertFalse(host.state.deviceTranscriptionAvailable)
        assertEquals(0, host.state.deviceProgress)
    }
    @Test fun deviceConversionAllowsOnlyCancellationWhilePending() {
        val platform = Platform(); val host = LectureHost(platform)
        host.updateState("""{"pending":true,"deviceTranscribing":true}""")
        listOf("start", "saveTranscript", "transcribeDevice", "downloadDeviceModel").forEach { host.command(it) }
        assertTrue(platform.commands.isEmpty())
        host.command("cancelDeviceTranscription")
        assertEquals("cancelDeviceTranscription", platform.commands.single().first)
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
    @Test fun transcriptSavingDoesNotTruncateLectureToTitleLength() {
        val platform = Platform(); val host = LectureHost(platform)
        val text = "강의 받아쓰기 본문입니다.\n".repeat(100)
        host.command("saveTranscript", "recording-id", text)
        assertEquals(text.trim(), platform.commands.single().third)
        host.updateState("""{"recording":true}""")
        host.command("saveTranscript", "recording-id", "덮어쓰기")
        assertEquals(1, platform.commands.size)
    }
    @Test fun finalAndProvisionalTextAndEditorSurvivePlatformSnapshots() {
        val host = LectureHost(Platform())
        host.updateState("""{"transcript":"확정 문장","provisional":"인식 중","editorId":"a","editorTitle":"강의","editorText":"수정할 내용","recordings":[{"id":"a","title":"강의","date":"today","seconds":10,"hasTranscript":true}]}""")
        assertEquals("확정 문장", host.state.transcript)
        assertEquals("인식 중", host.state.provisional)
        assertEquals("수정할 내용", host.state.editorText)
        assertTrue(host.state.recordings.single().hasTranscript)
        host.updateState("""{"editorId":""}""")
        assertTrue(host.state.editorId.isEmpty())
    }

}
