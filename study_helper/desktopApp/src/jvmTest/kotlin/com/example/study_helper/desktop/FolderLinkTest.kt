package com.example.study_helper.desktop

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class FolderLinkTest {
    @Test fun nestedFolderRoundTripConflictAndDeletionPreserveData() {
        val temporary = Files.createTempDirectory("study-folder-test").toFile()
        try {
            val folder = temporary.resolve("courses").apply { mkdirs() }
            val file = folder.resolve("과목/week1.md").apply { parentFile.mkdirs(); writeText("# 수업\n\n원본") }
            val store = DesktopStore(temporary.resolve("app")); val link = FolderLink(store)
            link.sync(folder)
            fun notes() = Json.parseToJsonElement(store.readFiles("notes")).jsonObject.getValue("files").jsonArray
            val id = notes().single().jsonObject.getValue("id").jsonPrimitive.content
            assertNull(store.writeFile("notes", id, "# 수업\n\n앱 수정"))
            link.sync(folder)
            assertTrue(file.readText().contains("앱 수정"))
            file.writeText("# 수업\n\n외부 수정")
            store.writeFile("notes", id, "# 수업\n\n다른 기기 수정")
            val result = link.sync(folder)
            assertTrue(result.contains("충돌 1"))
            assertEquals(2, notes().size)
            assertTrue(notes().any { it.jsonObject.getValue("content").jsonPrimitive.content.contains("다른 기기 수정") })
            file.delete(); link.sync(folder)
            assertFalse(file.exists()); assertEquals(2, notes().size)
            val reopened = DesktopStore(temporary.resolve("app"))
            assertEquals(store.readFiles("notes"), reopened.readFiles("notes"))
        } finally { temporary.deleteRecursively() }
    }
    @Test fun rejectsTraversalAndMalformedUtf8() {
        val temp = Files.createTempDirectory("study-store-test").toFile()
        try {
            val store = DesktopStore(temp.resolve("app"))
            assertNotNull(store.writeFile("notes", "../../outside", "text"))
            val file = temp.resolve("bad.md").apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
            assertFails { DesktopStore.readText(file) }
        } finally { temp.deleteRecursively() }
    }
}
