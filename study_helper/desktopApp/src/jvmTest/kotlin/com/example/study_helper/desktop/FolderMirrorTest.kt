package com.example.study_helper.desktop

import com.example.study_helper.sync.*
import com.example.study_helper.study.NotebookPlatform
import java.nio.file.Files
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class FolderMirrorTest {
    @Test fun failureIsVisibleAndSurvivesReopening()=scenario {cloud,pc,_ ->
        pc.setPreference("drive_account","account");pc.setPreference("drive_folder","rootfolder")
        pc.folder.resolve("note.md").writeText("# Note\n")
        cloud.loseUpload=true
        assertFailsWith<IllegalStateException> {pc.sync()}
        assertTrue(pc.mirror.state.failed)
        assertFalse(pc.mirror.state.finished)
        assertTrue(pc.mirror.state.result.contains("동기화 중단"))
        val reopened=FolderMirror(pc,pc)
        reopened.restore()
        assertTrue(reopened.state.failed)
        assertEquals(pc.mirror.state.result,reopened.state.result)
        cloud.loseUpload=false
        pc.sync()
        assertFalse(pc.mirror.state.failed)
        assertTrue(pc.mirror.state.finished)
    }
    private fun JsonObject.s(k:String)=this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Remote(val id:String,val name:String,val parent:String,var bytes:ByteArray?=null,var version:Int=1)
    private inner class Cloud {
        val files=mutableMapOf("rootfolder" to Remote("rootfolder","학습","root"))
        var counter=0; var failList=false; var loseUpload=false
        fun meta(r:Remote)=buildJsonObject {
            put("id",r.id); put("title",r.name); put("mimeType",if(r.bytes==null) "application/vnd.google-apps.folder" else "application/octet-stream")
            put("etag","v${r.version}"); r.bytes?.let {put("md5Checksum",hash(it));put("fileSize",it.size)}
        }
        fun add(parent:String,name:String,bytes:ByteArray?):Remote = Remote("r${++counter}",name,parent,bytes).also { files[it.id]=it }
        fun api(method:String,url:String,body:String,etag:String?):String {
            if(url.contains("maxResults")) {
                check(!failList) {"listing failed"}
                val q=URLDecoder.decode(url.substringAfter("&q=").substringBefore('&'),"UTF-8")
                val parent=q.substringAfter("'").substringBefore("'")
                return buildJsonObject {putJsonArray("items") {files.values.filter {it.parent==parent}.forEach {add(meta(it))}}}.toString()
            }
            if(method=="POST" && !url.endsWith("/trash")) {
                val data=Json.parseToJsonElement(body).jsonObject
                return meta(add(data.getValue("parents").jsonArray.first().jsonObject.s("id"),data.s("title"),null)).toString()
            }
            val id=url.substringAfter("/files/").substringBefore('?').substringBefore('/')
            val file=files.getValue(id)
            if(method=="POST") {
                check(etag=="v${file.version}"); files.remove(id); return "{}"
            }
            return meta(file).toString()
        }
    }
    private inner class Disk(val cloud:Cloud, val root:java.io.File):NotebookPlatform,FolderPlatform {
        val folder=root.resolve("folder").apply {mkdirs()}
        val app=root.resolve("app").apply {mkdirs()}
        val store=DesktopStore(app)
        val native=FolderFiles(app,true){folder}
        val mirror=FolderMirror(this,this)
        var raceDownload=false
        var failWriteAt=0; var writeCount=0
        override fun folderCommand(request:String,completion:(String)->Unit) {
            val result=runCatching {
                val input=Json.parseToJsonElement(request).jsonObject
                if(input.s("action")=="writeText") check(++writeCount!=failWriteAt) {"disk write failed"}
                when(input.s("action")) {
                    "upload" -> {
                        val bytes=native.resolve(input.s("path")).readBytes(); check(hash(bytes)==input.s("expected"))
                        val id=input.s("id")
                        val remote=if(id.isEmpty()) {
                            val metadata=Json.parseToJsonElement(input.s("metadata")).jsonObject
                            cloud.add(metadata.getValue("parents").jsonArray.first().jsonObject.s("id"),metadata.s("title"),bytes)
                        } else cloud.files.getValue(id).also {check(input.s("etag")=="v${it.version}");it.bytes=bytes;it.version++}
                        check(!cloud.loseUpload) {"response lost"}; cloud.meta(remote)
                    }
                    "download" -> {
                        val remote=cloud.files.getValue(input.s("id"));check(input.s("etag")=="v${remote.version}")
                        val f=native.resolve(input.s("path"))
                        if(raceDownload) {f.parentFile.mkdirs();f.writeText("concurrent edit")}
                        check((if(f.exists())hash(f.readBytes()) else "")==input.s("expected")) {"local changed"}
                        f.parentFile.mkdirs();f.writeBytes(remote.bytes!!);buildJsonObject {}
                    }
                    else -> native.execute(input)
                }
            }.getOrElse {buildJsonObject {put("error",it.message.orEmpty())}}
            completion(result.toString())
        }
        suspend fun sync(apply:Boolean=true)=mirror.run("account","rootfolder","test-token",apply,cloud::api)
        override fun readFiles(kind:String)=store.readFiles(kind)
        override fun writeFile(kind:String,id:String,content:String)=store.writeFile(kind,id,content)
        override fun newId()=java.util.UUID.randomUUID().toString()
        override fun preference(key:String)=store.preference(key)
        override fun setPreference(key:String,value:String)=store.setPreference(key,value)
        override fun pickDocument(kind:String){}
        override fun exportDocument(filename:String,content:String){}
        override fun copyText(text:String){}
        override fun openLink(url:String){}
        override fun startGame(){}
    }
    private fun scenario(block:suspend (Cloud,Disk,Disk)->Unit)=runBlocking {
        val root=Files.createTempDirectory("folder-mirror-test").toFile()
        try {val c=Cloud();block(c,Disk(c,root.resolve("pc")),Disk(c,root.resolve("mobile")))} finally {root.deleteRecursively()}
    }
    @Test fun preservesPathsBinaryAndMobileEditsWithNoDuplicates()=scenario {cloud,pc,mobile ->
        val path="보안/01주차/노트/수업.md"
        pc.folder.resolve(path).apply {parentFile.mkdirs();writeText("# 수업\n\n원본\n")}
        val pdf=byteArrayOf(0,1,2,-1,32)
        pc.folder.resolve("보안/01주차/PDF/자료.pdf").apply {parentFile.mkdirs();writeBytes(pdf)}
        pc.sync();mobile.sync()
        assertContentEquals(pdf,mobile.folder.resolve("보안/01주차/PDF/자료.pdf").readBytes())
        val notes=Json.parseToJsonElement(mobile.readFiles("notes")).jsonObject.getValue("files").jsonArray
        val id=notes.single().jsonObject.s("id")
        mobile.writeFile("notes",id,"# 수업\n\n모바일 편집\n")
        mobile.sync();pc.sync()
        assertTrue(pc.folder.resolve(path).readText().contains("모바일 편집"))
        val count=cloud.files.size;pc.sync();mobile.sync();assertEquals(count,cloud.files.size)
        val recording=mobile.app.resolve("recordings/recording.m4a").apply {parentFile.mkdirs();writeBytes(byteArrayOf(3,4,5))}
        mobile.mirror.command("collectRecordings");mobile.sync();pc.sync()
        assertContentEquals(recording.readBytes(),pc.folder.resolve("녹음/recording.m4a").readBytes())
    }
    @Test fun propagatesDeletionsAndArchivesLocalCopies()=scenario {cloud,pc,mobile ->
        pc.folder.resolve("test.md").writeText("# Test\n\nbody\n");pc.sync();mobile.sync()
        pc.folder.resolve("test.md").delete();pc.sync();mobile.sync()
        assertFalse(mobile.folder.resolve("test.md").exists())
        assertEquals(0,Json.parseToJsonElement(mobile.readFiles("notes")).jsonObject.getValue("files").jsonArray.size)
        assertTrue(mobile.app.resolve("folder-history").walk().any {it.isFile})
        pc.sync();mobile.sync();assertEquals(1,cloud.files.size)
    }
    @Test fun editVersusDeleteAndTwoEditsRemainConflicts()=scenario {cloud,pc,mobile ->
        pc.folder.resolve("test.md").writeText("# Test\n\nbase\n");pc.sync();mobile.sync()
        mobile.folder.resolve("test.md").writeText("# Test\n\nmobile\n")
        pc.folder.resolve("test.md").writeText("# Test\n\npc\n");pc.sync();mobile.sync()
        assertEquals("충돌",mobile.mirror.state.changes.single().action)
        assertTrue(mobile.folder.resolve("test.md").readText().contains("mobile"))
        cloud.files.values.filter {it.bytes!=null}.map {it.id}.forEach {cloud.files.remove(it)}
        mobile.sync();assertEquals("충돌",mobile.mirror.state.changes.single().action)
    }
    @Test fun partialListingAndConcurrentDownloadNeverOverwrite()=scenario {cloud,pc,mobile ->
        pc.folder.resolve("test.md").writeText("# Test\n\nbase\n");pc.sync()
        cloud.failList=true
        assertFails {mobile.sync()};assertTrue(mobile.folder.listFiles()!!.isEmpty())
        cloud.failList=false;mobile.raceDownload=true
        assertFails {mobile.sync()};assertEquals("concurrent edit",mobile.folder.resolve("test.md").readText())
    }
    @Test fun uploadResponseLossRecoversByPathAndHash()=scenario {cloud,pc,_ ->
        pc.folder.resolve("test.md").writeText("# Test\n\nbase\n");cloud.loseUpload=true
        assertFails {pc.sync()};cloud.loseUpload=false;pc.sync()
        assertEquals(1,cloud.files.values.count {it.bytes!=null})
    }
    @Test fun previewDoesNotWriteRemoteOrDownloadFiles()=scenario {cloud,pc,mobile ->
        pc.folder.resolve("test.md").writeText("# Test\n\nbase\n");pc.sync(false)
        assertEquals(1,cloud.files.size);assertEquals("추가",pc.mirror.state.changes.single().action)
        pc.sync();mobile.sync(false);assertTrue(mobile.folder.listFiles()!!.isEmpty())
    }
    @Test fun traversalAndSymlinksCannotEscapeSelectedFolder()=scenario {_,pc,_ ->
        assertFails {pc.native.resolve("../secret")}
        Files.createSymbolicLink(pc.folder.resolve("link").toPath(),pc.app.toPath())
        assertFails {pc.native.resolve("link/secret.md")}
    }
    @Test fun adoptsLegacyFolderIdentityAndPreservesUnsyncedAppEdit()=scenario {_,pc,_ ->
        pc.folder.resolve("test.md").writeText("# Test\n\nbase\n")
        FolderLink(pc.store).sync(pc.folder)
        val id=Json.parseToJsonElement(pc.readFiles("notes")).jsonObject.getValue("files").jsonArray.single().jsonObject.s("id")
        pc.writeFile("notes",id,"# Test\n\nunsynced app edit\n")
        pc.sync()
        val notes=Json.parseToJsonElement(pc.readFiles("notes")).jsonObject.getValue("files").jsonArray
        assertEquals(1,notes.size);assertEquals(id,notes.single().jsonObject.s("id"))
        assertTrue(pc.folder.resolve("test.md").readText().contains("unsynced app edit"))
    }
    @Test fun interruptedExportOfNewNotesRetriesWithoutDuplicateOrOverwrite()=scenario {cloud,pc,_ ->
        pc.sync()
        pc.writeFile("notes",pc.newId(),"# One\n\nfirst\n")
        pc.writeFile("notes",pc.newId(),"# Two\n\nsecond\n")
        pc.failWriteAt=2
        assertFails {pc.sync()}
        pc.failWriteAt=0;pc.sync();pc.sync()
        assertEquals(2,cloud.files.values.count {it.bytes!=null})
        assertEquals(2,pc.folder.walk().count {it.isFile})
    }
}
