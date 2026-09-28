package com.example.study_helper.sync

import com.example.study_helper.study.NotebookPlatform
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class DriveSyncTest {
    private val original = "# 수업\n\n원본\n"
    private val localEdit = "# 수업\n\n아이폰에서 편집\n"
    private val remoteEdit = "# 수업\n\nPC에서 편집\n"

    @Test fun downloadThenUploadAndRestartAreIdempotent() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); val sync = DriveSync(disk, drive)
        sync.sync()
        assertEquals(1, disk.docs.size)
        val id = disk.docs.keys.single()
        disk.docs[id] = localEdit
        sync.sync()
        assertEquals(localEdit.trim(), drive.content.trim())
        assertEquals(1, drive.writes)
        DriveSync(disk, drive).sync()
        assertEquals(1, drive.writes)
        assertEquals(1, disk.docs.size)
    }
    @Test fun conflictKeepsBothVersionsAndUploadsTheCopy() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); val sync = DriveSync(disk, drive)
        sync.sync()
        val id = disk.docs.keys.single()
        disk.docs[id] = localEdit; drive.content = remoteEdit; drive.etag = "v2"
        sync.sync()
        assertTrue(disk.docs.getValue(id).contains("PC에서 편집"))
        assertTrue(disk.docs.values.any { it.contains("아이폰에서 편집") })
        assertEquals(remoteEdit, drive.content)
        assertEquals(0, drive.writes)
        assertEquals(1, drive.creates)
    }
    @Test fun racingRemoteWriteIsRejectedAndLocalEditSurvives() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); val sync = DriveSync(disk, drive)
        sync.sync(); val id = disk.docs.keys.single(); disk.docs[id] = localEdit
        drive.raceOnWrite = true
        sync.sync()
        assertEquals(localEdit, disk.docs[id])
        assertEquals(0, drive.writes)
        assertTrue(sync.state.message.contains("다른 기기"))
        assertFalse(sync.state.busy)
    }
    @Test fun failedDiskWriteDoesNotAdvanceBaseline() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); val sync = DriveSync(disk, drive)
        disk.failWrite = true; sync.sync()
        assertTrue(disk.docs.isEmpty())
        assertFalse(disk.prefs.keys.any { it.startsWith("drive_baseline") })
        disk.failWrite = false; sync.sync()
        assertEquals(1, disk.docs.size)
    }
    @Test fun incompleteListingDoesNotUploadOrOverwrite() = runTest {
        val disk = Disk(); disk.docs["notes/${disk.newId()}"] = localEdit
        val drive = FakeDrive(original); drive.failList = true
        DriveSync(disk, drive).sync()
        assertEquals(0, drive.writes); assertEquals(0, drive.creates)
        assertEquals(listOf(localEdit), disk.docs.values.toList())
    }
    @Test fun remoteDeletionDoesNotEraseLocalOrResurrectRemote() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); val sync = DriveSync(disk, drive)
        sync.sync(); drive.deleted = true; sync.sync()
        assertEquals(1, disk.docs.size); assertEquals(0, drive.creates)
    }
    @Test fun invalidJsonIsSkippedWithoutBlockingNotes() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); drive.invalidJson = true
        val sync = DriveSync(disk, drive); sync.sync()
        assertEquals(1, disk.docs.size)
        assertTrue(sync.state.message.contains("지원하지 않는 파일 1"))
    }
    @Test fun accountChangeRequiresSelectingFolderAgain() = runTest {
        val disk = Disk(); disk.prefs["drive_account"] = "old-account"
        val drive = FakeDrive(original); val sync = DriveSync(disk, drive); sync.sync()
        assertEquals("", sync.state.folderId); assertEquals(0, drive.creates); assertTrue(disk.docs.isEmpty())
    }
    @Test fun unicodeQueryEncodingAndDecisionTable() {
        assertEquals("%ED%95%9C%EA%B8%80%20%26%2B", urlEncode("한글 &+"))
        assertEquals(SyncDecision.DOWNLOAD, syncDecision(null, null, "remote"))
        assertEquals(SyncDecision.CONFLICT, syncDecision(null, "local", "remote"))
        assertEquals(SyncDecision.UPLOAD, syncDecision("base", "edit", "base"))
        assertEquals(SyncDecision.DOWNLOAD, syncDecision("base", "base", "edit"))
        assertEquals(SyncDecision.SAME, syncDecision("base", "edit", "edit"))
    }

    @Test fun uploadCommittedButResponseLostIsRecoveredWithoutDuplicate() = runTest {
        val disk = Disk(); disk.docs["notes/${disk.newId()}"] = localEdit
        val drive = FakeDrive(original); drive.deleted = true; drive.loseUploadResponse = true
        val sync = DriveSync(disk, drive)
        sync.sync()
        assertEquals(1, drive.creates)
        assertTrue(sync.state.message.contains("HTTP 503"))
        drive.loseUploadResponse = false
        sync.sync()
        assertEquals(1, drive.creates)
        assertEquals(1, disk.docs.size)
    }

    @Test fun remoteChangeDuringDownloadDoesNotChangeLocalBaseline() = runTest {
        val disk = Disk(); val drive = FakeDrive(original); drive.raceOnRead = true
        val sync = DriveSync(disk, drive); sync.sync()
        assertTrue(disk.docs.isEmpty())
        assertTrue(sync.state.message.contains("다른 기기"))
        assertFalse(disk.prefs.keys.any { it.startsWith("drive_baseline") })
    }

    private class Disk : NotebookPlatform {
        val docs = mutableMapOf<String, String>()
        val prefs = mutableMapOf("drive_folder" to "folder", "drive_folder_name" to "수업")
        var failWrite = false
        var counter = 0
        override fun newId() = "00000000-0000-0000-0000-${(++counter).toString().padStart(12, '0')}"
        override fun readFiles(kind: String) = buildJsonObject { putJsonArray("files") {
            docs.filterKeys { it.startsWith("$kind/") }.forEach { (id, text) -> add(buildJsonObject {
                put("id", id.substringAfter('/')); put("content", text)
            }) }
        } }.toString()
        override fun writeFile(kind: String, id: String, content: String): String? {
            if (failWrite) return "저장 실패"
            docs["$kind/$id"] = content; return null
        }
        override fun preference(key: String) = prefs[key]
        override fun setPreference(key: String, value: String) { prefs[key] = value }
        override fun pickDocument(kind: String) = Unit
        override fun exportDocument(filename: String, content: String) = Unit
        override fun copyText(text: String) = Unit
        override fun openLink(url: String) = Unit
        override fun startGame() = Unit
    }
    private class FakeDrive(var content: String) : DrivePlatform {
        var etag = "v1"; var raceOnWrite = false; var failList = false; var deleted = false; var invalidJson = false
        var writes = 0; var creates = 0
        var loseUploadResponse = false; var raceOnRead = false
        private val createdFiles = mutableMapOf<String, Pair<JsonObject, String>>()
        private fun meta(id: String = "remote", name: String = "수업.md") = buildJsonObject {
            put("id", id); put("title", name); put("mimeType", "text/plain"); put("etag", etag)
        }
        override fun authorizeDrive(completion: (String) -> Unit) = completion("{\"accessToken\":\"test\"}")
        override fun disconnectDrive() = Unit
        override fun driveRequest(request: String, completion: (String) -> Unit) {
            val data = Json.parseToJsonElement(request).jsonObject
            val url = data.getValue("url").jsonPrimitive.content
            val method = data.getValue("method").jsonPrimitive.content
            var status = 200
            val body = when {
                url.contains("/about?") -> "{\"user\":{\"permissionId\":\"test-account\"}}"
                url.contains("/files/folder?") -> "{\"mimeType\":\"application/vnd.google-apps.folder\"}"
                url.contains("maxResults=") -> {
                    if (failList) status = 503
                    buildJsonObject { putJsonArray("items") {
                        if (!deleted) add(meta())
                        if (invalidJson) add(meta("invalid", "unrelated.json"))
                        createdFiles.values.forEach { add(it.first) }
                    } }.toString()
                }
                method == "PUT" -> {
                    val sent = data.getValue("headers").jsonObject["If-Match"]?.jsonPrimitive?.content
                    if (raceOnWrite || sent != etag) { status = 412; "" }
                    else { content = data.getValue("body").jsonPrimitive.content; writes++; etag = "v${writes + 1}"; meta().toString() }
                }
                method == "POST" -> {
                    creates++
                    val payload = data.getValue("body").jsonPrimitive.content
                    val sections = payload.split("\r\n\r\n")
                    val metadata = Json.parseToJsonElement(sections[1].substringBefore("\r\n--")).jsonObject
                    val uploaded = sections[2].substringBeforeLast("\r\n--")
                    val result = JsonObject(meta("created$creates", metadata.getValue("title").jsonPrimitive.content) + ("properties" to metadata.getValue("properties")))
                    createdFiles["created$creates"] = result to uploaded
                    if (loseUploadResponse) status = 503
                    result.toString()
                }
                url.contains("alt=media") -> {
                    if (raceOnRead) status = 412
                    val id = url.substringAfter("/files/").substringBefore('?')
                    if (url.contains("invalid")) "{\"unrelated\":true}" else createdFiles[id]?.second ?: content
                }
                url.contains("/remote?") -> meta().toString()
                url.contains("/invalid?") -> meta("invalid", "unrelated.json").toString()
                url.contains("/created") -> createdFiles.getValue(url.substringAfter("/files/").substringBefore('?')).first.toString()
                else -> error("Unexpected request: $method $url")
            }
            completion(buildJsonObject { put("status", status); put("body", body) }.toString())
        }
    }
}
