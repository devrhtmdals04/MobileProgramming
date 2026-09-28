package com.example.study_helper.desktop

import com.example.study_helper.sync.FolderFiles
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

class FolderDownloadTest {
    private fun hash(text:String)=MessageDigest.getInstance("MD5").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun mediaDownloadUsesChecksumInsteadOfMetadataEtag() {
        val root=Files.createTempDirectory("media-download-test").toFile()
        try {
            val folder=root.resolve("folder").apply {mkdirs()}
            val file=folder.resolve("note.md").apply {writeText("original")}
            var payload="received"
            var race=false
            val native=FolderFiles(root.resolve("app"),true,openConnection={url ->
                assertTrue(url.query.contains("alt=media"))
                object:HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy()=false
                    // Reproduce Drive rejecting a metadata validator on a media request.
                    override fun getResponseCode()=if(getRequestProperty("If-Match")!=null) 412 else 200
                    override fun getInputStream():java.io.InputStream {
                        assertEquals("Bearer test-token",getRequestProperty("Authorization"))
                        if(race) file.writeText("concurrent local edit")
                        return payload.byteInputStream()
                    }
                }
            }) {folder}
            fun download()=native.execute(buildJsonObject {
                put("action","download");put("path","note.md");put("id","remote")
                put("token","test-token");put("etag","metadata-etag")
                put("expected",hash(file.readText()));put("hash",hash("received"))
            })
            download()
            assertEquals("received",file.readText())
            assertEquals("original",root.resolve("app/folder-history").walk().first {it.isFile}.readText())
            payload="remote changed since listing"
            assertFailsWith<IllegalStateException> {download()}
            assertEquals("received",file.readText())
            payload="received";race=true
            assertFailsWith<IllegalStateException> {download()}
            assertEquals("concurrent local edit",file.readText())
        } finally {root.deleteRecursively()}
    }
}
