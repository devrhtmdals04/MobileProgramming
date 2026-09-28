package com.example.study_helper.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.example.study_helper.study.*
import com.example.study_helper.sync.*
import com.example.study_helper.core.*
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.UUID
import javax.swing.JFileChooser
import kotlinx.coroutines.*

fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val probe = args.contains("--desktop-probe")
    val platform = remember { DesktopPlatform(scope, if (probe) java.nio.file.Files.createTempDirectory("study-desktop-probe-").toFile() else DesktopPlatform.defaultRoot()) }
    LaunchedEffect(Unit) {
        if (args.contains("--share-folder")) platform.host.sharing = true
        if (probe) {
            var passed = false
            try {
                delay(1500)
                val fixture = QuizDocument("PC 통합 검증", "", "검증용 노트", (1..3).map {
                    QuizItem("q$it", "기밀성 검증 $it", listOf("기밀성", "무결성", "가용성"), 0, "기밀성 해설", "기밀성 근거")
                })
                check(platform.host.importDocument("question-sets", "probe.json", QuizFiles.write(fixture)) == null)
                platform.probing = true
                platform.host.startLearning(platform.host.quizzes.single().id)
                withTimeout(90_000) { while (platform.host.launching) delay(100) }
                check(platform.host.recap?.correct == 7) { "PC 채점 결과가 올바르지 않습니다: ${platform.host.recap}; ${platform.host.notebook.error}" }
                val report = Json.parseToJsonElement(File(platform.lastBridge, "notebook-engine-2.json").readText()).jsonObject
                check(report.getValue("failures").jsonArray.isEmpty()) { report.toString() }
                println("DESKTOP PROBE PASSED: ${platform.store.root}")
                passed = true
            } catch (error: Exception) { System.err.println("DESKTOP PROBE FAILED: ${error.message}"); error.printStackTrace() }
            finally { platform.close(); if (!passed) kotlin.system.exitProcess(1); exitApplication() }
        }
    }
    Window(onCloseRequest = { platform.close(); exitApplication() }, title = "Study Helper", state = rememberWindowState(width = 1100.dp, height = 820.dp)) {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { platform.settings = false; platform.host.sharing = true }, enabled = !platform.host.syncBusy) { Text("공부 폴더 공유") }
                    TextButton(onClick = { platform.settings = !platform.settings }, enabled = !platform.host.syncBusy) { Text("설정") }
                }
                if (platform.status.isNotEmpty()) Text(platform.status, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (platform.folderBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (platform.settings) DesktopSettings(platform)
                else if (platform.recordings) LectureRecordings(platform.audio.host)
                else Box(Modifier.weight(1f)) { platform.host.Content() }
            }
        }
    }
}

class DesktopPlatform(private val scope: CoroutineScope, root: File = defaultRoot()) : NotebookPlatform, DrivePlatform, FolderPlatform {
    val store = DesktopStore(root)
    val host = NotebookHost(this)
    var settings by mutableStateOf(false)
    var recordings by mutableStateOf(false)
    var status by mutableStateOf("")
    var folderBusy by mutableStateOf(false)
    private val drive = GoogleDriveDesktop({ store.preference("oauth_file")?.let(::File) ?: System.getenv("STUDY_GOOGLE_DESKTOP_OAUTH")?.let(::File) }, scope)
    private val folderFiles = FolderFiles(store.root,true) { store.preference("linked_folder")?.let(::File) }
    override fun folderCommand(request: String, completion: (String) -> Unit) {
        scope.launch {
            val result=runCatching {
                val input=Json.parseToJsonElement(request).jsonObject
                when(input.getValue("action").jsonPrimitive.content) {
                    "choose" -> {
                        chooseFile(true)?.let {
                            require(!it.canonicalFile.toPath().startsWith(store.root.canonicalFile.toPath()) && !store.root.canonicalFile.toPath().startsWith(it.canonicalFile.toPath())) { "앱 내부 저장 폴더는 선택할 수 없습니다." }
                            store.setPreference("linked_folder",it.absolutePath)
                        }; buildJsonObject {}
                    }
                    "open" -> { Desktop.getDesktop().open(folderFiles.resolve(input["path"]?.jsonPrimitive?.content.orEmpty())); buildJsonObject {} }
                    else -> {
                        check(!audio.isRecording && store.preference("active_recording").isNullOrEmpty()) { "녹음을 먼저 저장해 주세요." }
                        withContext(Dispatchers.IO) { folderFiles.execute(input) }
                    }
                }
            }.getOrElse { buildJsonObject { put("error",it.message ?: "폴더 작업에 실패했어요.") } }
            completion(result.toString())
        }
    }
    private var game: Process? = null
    var probing = false
    var lastBridge: File? = null
    val audio by lazy { DesktopAudio(store, host, scope) { recordings = false } }
    override fun readFiles(kind: String) = store.readFiles(kind)
    override fun writeFile(kind: String, id: String, content: String) = store.writeFile(kind, id, content)
    override fun newId() = UUID.randomUUID().toString()
    override fun preference(key: String) = store.preference(key)
    override fun setPreference(key: String, value: String) = store.setPreference(key, value)
    override fun copyText(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    override fun openLink(url: String) {
        runCatching { val uri = URI(url); require(uri.scheme in listOf("https", "http", "mailto")); Desktop.getDesktop().browse(uri) }
            .onFailure { host.reportError("링크를 열지 못했어요.") }
    }
    fun chooseFile(directory: Boolean = false): File? {
        val chooser = JFileChooser().apply { fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    override fun pickDocument(kind: String) {
        chooseFile()?.let { file -> runCatching { host.importDocument(kind, file.name, DesktopStore.readText(file)) }.onFailure { host.reportError(it.message.orEmpty()) } }
    }
    override fun exportDocument(filename: String, content: String) {
        val chooser = JFileChooser().apply { selectedFile = File(filename) }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val target = chooser.selectedFile
            if (target.exists() && javax.swing.JOptionPane.showConfirmDialog(null, "${target.name} 파일을 덮어쓸까요?", "내보내기", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return
            runCatching { DesktopStore.atomicWrite(target, content) }.onFailure { host.reportError(it.message.orEmpty()) }
        }
    }
    fun syncFolder() {
        if (folderBusy || host.syncBusy || host.launching) return
        val folder = store.preference("linked_folder")?.let(::File)
        if (folder == null) { settings = true; status = "먼저 PC 공부 폴더를 선택해 주세요."; return }
        folderBusy = true
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { runCatching { FolderLink(store).sync(folder) }.getOrElse { it.message ?: "폴더를 동기화하지 못했어요." } }
                host.reload()
            } finally { folderBusy = false }
        }
    }
    override fun openRecordings() { recordings = true; audio.audioCommand("refresh", "", "") }
    override fun authorizeDrive(completion: (String) -> Unit) = drive.authorizeDrive(completion)
    override fun driveRequest(request: String, completion: (String) -> Unit) = drive.driveRequest(request, completion)
    override fun disconnectDrive() = drive.disconnectDrive()
    override fun startGame() {
        if (audio.isRecording) { host.gameFailed("녹음을 저장한 뒤 게임을 시작해 주세요."); return }
        scope.launch {
            try {
                val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
                val bundled = resources?.resolve(if (System.getProperty("os.name").startsWith("Windows")) "godot.exe" else "godot")
                val executable = store.preference("godot_bin")?.let(::File)
                    ?: bundled?.takeIf { it.exists() }
                    ?: File(System.getenv("GODOT_BIN") ?: "/Applications/Godot.app/Contents/MacOS/Godot")
                require(executable.isFile) { "PC 설정에서 Godot 실행 파일을 선택해 주세요." }
                val bridge = File(store.root, "game-bridge/${newId()}").apply { mkdirs() }
                lastBridge = bridge
                DesktopStore.atomicWrite(File(bridge, "host.json"), "{\"version\":1}")
                DesktopStore.atomicWrite(File(bridge, "ready.json"), "{\"version\":1}")
                val pack = resources?.resolve("study-game.pck")
                val command = mutableListOf(executable.absolutePath)
                if (pack?.exists() == true) command += listOf("--main-pack", pack.absolutePath)
                else {
                    val project = File("godot-runner").takeIf { it.isDirectory } ?: File("../godot-runner")
                    require(project.isDirectory) { "게임 자료를 찾지 못했어요. 패키지 빌드로 실행해 주세요." }
                    command += listOf("--path", project.canonicalPath)
                }
                command += listOf("--", "--study-bridge-dir=${bridge.absolutePath}")
                if (probing) {
                    DesktopStore.atomicWrite(File(bridge, "probe-stage.json"), "{\"round\":2}")
                    command += "--notebook-probe"
                }
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(File(store.root, "last-game.log")).start()
                game = process
                while (process.isAlive) {
                    val request = File(bridge, "request.json")
                    if (request.exists()) {
                        val response = host.exchange(request.readText())
                        check(request.delete())
                        DesktopStore.atomicWrite(File(bridge, "response.json"), response)
                    }
                    if (File(bridge, "return.json").exists()) { process.destroy(); break }
                    delay(35)
                }
                host.finishGame()
                if (!process.isAlive && process.exitValue() != 0) host.reportError("게임이 종료됐어요. 저장 폴더의 last-game.log를 확인해 주세요.")
            } catch (error: Exception) { host.gameFailed(error.message ?: "게임을 시작하지 못했어요.") }
            finally { game = null }
        }
    }
    fun close() { game?.destroy(); audio.close() }
    companion object {
        fun defaultRoot(): File {
            System.getenv("STUDY_HELPER_DATA_DIR")?.let { return File(it) }
            val home = System.getProperty("user.home")
            return when {
                System.getProperty("os.name").startsWith("Mac") -> File(home, "Library/Application Support/StudyHelperDesktop")
                System.getProperty("os.name").startsWith("Windows") -> File(System.getenv("APPDATA") ?: home, "StudyHelper")
                else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "study-helper")
            }
        }
    }
}

@Composable
private fun DesktopSettings(platform: DesktopPlatform) {
    var revision by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("PC 설정", style = MaterialTheme.typography.headlineMedium)
        Text("공부 폴더 선택과 동기화는 ‘공부 폴더 공유’에서 진행합니다.")
        for ((key, label, directory) in listOf(
            Triple("oauth_file", "Google OAuth JSON 선택 (선택)", false),
            Triple("godot_bin", "Godot 실행 파일 (선택)", false), Triple("whisper_bin", "Whisper 실행 파일", false), Triple("whisper_model", "Whisper 모델 파일", false))) {
            val value = remember(revision) { platform.preference(key).orEmpty() }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { platform.chooseFile(directory)?.let { platform.setPreference(key, it.absolutePath); revision++ } }) { Text(label) }
                Text(value.ifEmpty { "미설정" }, Modifier.weight(1f).padding(top = 12.dp))
            }
        }
        Text("Google 연결을 누르면 기본 브라우저에서 로그인합니다. 기본 PC 클라이언트 ID는 앱에 포함되어 있습니다. 다른 클라이언트나 추가 인증 설정이 필요한 경우 OAuth JSON을 선택하세요. 인증 토큰은 디스크에 저장하지 않습니다.")
        TextButton(onClick = { platform.openLink("https://console.cloud.google.com/apis/credentials") }) { Text("Google Cloud 설정 열기") }
        Button(onClick = { platform.settings = false }) { Text("서재로") }
        TextButton(onClick = { Desktop.getDesktop().open(platform.store.root) }) { Text("고급: 앱 저장 폴더 열기") }
    }
}
