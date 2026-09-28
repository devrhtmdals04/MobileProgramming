import SwiftUI
import UIKit
import UniformTypeIdentifiers
import Shared
#if DEBUG
import Speech
#endif

@main
struct StudyHelperApp: App {
    @UIApplicationDelegateAdaptor(StudyApplicationDelegate.self) private var appDelegate
    @StateObject private var platform = StudyPlatform()
    var body: some Scene {
        WindowGroup { NotebookView(platform: platform).ignoresSafeArea() }
    }
}

/// Godot's device motion adapter reads UIApplicationDelegate.window for orientation.
/// SwiftUI forwards this optional delegate property without starting the engine.
final class StudyApplicationDelegate: NSObject, UIApplicationDelegate {
    var window: UIWindow? {
        get { UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) }
        set { /* SwiftUI owns the window. */ }
    }
}

private struct NotebookView: UIViewControllerRepresentable {
    let platform: StudyPlatform
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = NotebookViewControllerKt.NotebookViewController(host: platform.host)
        platform.root = controller
        #if DEBUG
        platform.beginProbeIfRequested()
        #endif
        return controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// UIKit, files, and transport only. Kotlin owns all document validation and learning rules.
final class StudyPlatform: NSObject, ObservableObject, NotebookPlatform, DrivePlatform, FolderPlatform, UIDocumentPickerDelegate, UIDocumentInteractionControllerDelegate {
    lazy var host = NotebookHost(platform: self)
    weak var root: UIViewController?
    private let files = FileManager.default
    private let engine = StudyEngine()
    private lazy var drive: GoogleDriveConnection = {
        let connection = GoogleDriveConnection()
        connection.window = { [weak self] in self?.root?.view.window }
        return connection
    }()
    func authorizeDrive(completion: @escaping (String) -> Void) { drive.authorize(completion) }
    func driveRequest(request: String, completion: @escaping (String) -> Void) { drive.request(request, completion) }
    func disconnectDrive() { drive.disconnect() }
    private lazy var sharedFolder = SharedFolderFiles(appRoot: files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(storageName))
    private var sharedDocument: UIDocumentInteractionController?
    func documentInteractionControllerViewControllerForPreview(_ controller: UIDocumentInteractionController) -> UIViewController { root ?? UIViewController() }
    func folderCommand(request: String, completion: @escaping (String) -> Void) {
        guard let input = try? JSONSerialization.jsonObject(with: Data(request.utf8)) as? [String:String] else { completion(json(["error":"잘못된 폴더 요청입니다."])); return }
        if input["action"] == "open" {
            do {
                let url = try sharedFolder.resolve(input["path"] ?? "")
                sharedDocument = UIDocumentInteractionController(url: url)
                sharedDocument?.delegate = self
                if sharedDocument?.presentPreview(animated: true) == true { completion(json([:])); return }
                guard let view = root?.view, sharedDocument?.presentOptionsMenu(from: view.bounds, in: view, animated: true) == true else { throw message("이 파일을 열 앱을 찾지 못했어요.") }
                completion(json([:]))
            } catch { completion(json(["error":error.localizedDescription])) }
            return
        }
        guard !lectureAudio.isRecording else { completion(json(["error":"녹음을 먼저 저장해 주세요."])); return }
        let storage = sharedFolder
        DispatchQueue.global(qos: .utility).async {
            let response: [String:Any]
            do { response = try storage.execute(input) }
            catch { response = ["error":error.localizedDescription] }
            DispatchQueue.main.async { completion(self.json(response)) }
        }
    }
    private lazy var lectureAudio: LectureAudio = {
        let audio = LectureAudio(folder: files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(storageName).appendingPathComponent("recordings"))
        audio.saveNote = { [weak self] id, title, body in
            guard let self else { return "서재가 닫혀 있어요." }
            return self.host.saveNote(id: id, title: title, body: body)
        }
        return audio
    }()
    private var game: GameContainer?
    private var mailboxTimer: Timer?
    private var importKind: String?
    private var closing = false
    private var gameStartedAt: Date?
    #if DEBUG
    private let probeID = (ProcessInfo.processInfo.arguments.contains("--notebook-probe") || ProcessInfo.processInfo.arguments.contains("--lecture-transcription-probe") || ProcessInfo.processInfo.arguments.contains("--lecture-local-probe") || ProcessInfo.processInfo.arguments.contains("--lecture-device-probe")) ? UUID().uuidString : nil
    private var probeStarted = false
    #endif
    private lazy var preferences: UserDefaults = {
        #if DEBUG
        if let probeID { return UserDefaults(suiteName: "notebook-probe-" + probeID)! }
        #endif
        return .standard
    }()
    private var storageName: String {
        #if DEBUG
        if let probeID { return "StudyHelper-Probe-" + probeID }
        #endif
        return "StudyHelper"
    }
    private var bridge: URL { files.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("study-bridge", isDirectory: true) }

    private func directory(_ kind: String) throws -> URL {
        guard ["notes", "question-sets"].contains(kind) else { throw message("지원하지 않는 문서 종류입니다.") }
        let url = files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(storageName, isDirectory: true).appendingPathComponent(kind, isDirectory: true)
        try files.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    private func validID(_ id: String) -> Bool { UUID(uuidString: id) != nil && !id.contains("/") }
    private func message(_ text: String) -> NSError { NSError(domain: "StudyHelper", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
    private func json(_ value: Any) -> String { String(data: try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), encoding: .utf8)! }

    func readFiles(kind: String) -> String {
        do {
            let suffix = kind == "notes" ? "md" : "json"
            let urls = try files.contentsOfDirectory(at: directory(kind), includingPropertiesForKeys: [.contentModificationDateKey], options: [.skipsHiddenFiles])
                .filter { $0.pathExtension == suffix && validID($0.deletingPathExtension().lastPathComponent) }
                .sorted { left, right in
                    let leftDate = (try? left.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                    let rightDate = (try? right.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                    return leftDate == rightDate ? left.lastPathComponent < right.lastPathComponent : leftDate > rightDate
                }
            return json(["files": try urls.map { ["id": $0.deletingPathExtension().lastPathComponent, "content": try readText($0)] }])
        } catch { return json(["error": "저장된 문서를 읽지 못했어요. \(error.localizedDescription)"]) }
    }
    func writeFile(kind: String, id: String, content: String) -> String? {
        do {
            guard validID(id) else { throw message("올바르지 않은 문서 ID입니다.") }
            let url = try directory(kind).appendingPathComponent(id).appendingPathExtension(kind == "notes" ? "md" : "json")
            try content.write(to: url, atomically: true, encoding: .utf8)
            return nil
        } catch { return "문서를 저장하지 못했어요. \(error.localizedDescription)" }
    }
    func doNewId() -> String { UUID().uuidString.lowercased() }
    func preference(key: String) -> String? { preferences.string(forKey: "study_" + key) }
    func setPreference(key: String, value: String) { preferences.set(value, forKey: "study_" + key) }
    func doCopyText(text: String) { UIPasteboard.general.string = text }
    func openLink(url: String) {
        guard let link = URL(string: url), ["https", "http", "mailto"].contains(link.scheme?.lowercased() ?? "") else {
            host.reportError(message: "첨부 파일과 노트 간 링크 연결은 아직 지원하지 않아요."); return
        }
        UIApplication.shared.open(link, options: [:]) { [weak self] opened in
            if !opened { self?.host.reportError(message: "이 링크를 열 수 있는 앱이 없어요.") }
        }
    }

    func openRecordings() {
        let controller = NotebookViewControllerKt.LectureViewController(host: lectureAudio.host)
        controller.modalPresentationStyle = .fullScreen
        lectureAudio.close = { [weak controller] in controller?.dismiss(animated: true) }
        lectureAudio.audioCommand(action: "refresh", id: "", title: "")
        presenter?.present(controller, animated: true)
    }

    func pickDocument(kind: String) {
        importKind = kind
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: true)
        picker.allowsMultipleSelection = false
        picker.delegate = self
        presenter?.present(picker, animated: true)
    }
    func exportDocument(filename: String, content: String) {
        do {
            importKind = nil
            let folder = files.temporaryDirectory.appendingPathComponent("note-export", isDirectory: true)
            try files.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent(filename)
            try content.write(to: url, atomically: true, encoding: .utf8)
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            picker.delegate = self
            presenter?.present(picker, animated: true)
        } catch { host.reportError(message: "노트를 내보내지 못했어요. \(error.localizedDescription)") }
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { importKind = nil }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let kind = importKind, let url = urls.first else { return }
        importKind = nil
        // File providers may download from iCloud. Coordinate outside the UI thread.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            var result: Result<String, Error> = .failure(self.message("파일을 읽지 못했어요."))
            var coordinationError: NSError?
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { local in
                result = Result { try self.readText(local) }
            }
            if let coordinationError { result = .failure(coordinationError) }
            let completed = result
            DispatchQueue.main.async {
                switch completed {
                case .success(let text): _ = self.host.importDocument(kind: kind, filename: url.lastPathComponent, content: text)
                case .failure(let error): self.host.reportError(message: error.localizedDescription)
                }
            }
        }
    }
    private func readText(_ url: URL) throws -> String {
        let stream = try FileHandle(forReadingFrom: url)
        defer { try? stream.close() }
        var bytes = Data()
        while bytes.count <= 400_400 {
            let chunk = try stream.read(upToCount: min(8192, 400_401 - bytes.count)) ?? Data()
            if chunk.isEmpty { break }
            bytes.append(chunk)
        }
        guard bytes.count <= 400_400 else { throw message("파일이 너무 커요. 100,000자 이하의 파일을 선택해 주세요.") }
        guard let text = String(data: bytes, encoding: .utf8) else { throw message("UTF-8로 저장된 텍스트 파일을 선택해 주세요.") }
        return text
    }
    private var presenter: UIViewController? {
        var current = root?.view.window?.rootViewController ?? root
        while let presented = current?.presentedViewController { current = presented }
        return current
    }

    func startGame() {
        guard !lectureAudio.isRecording else {
            host.gameFailed(message: "강의 녹음을 종료하고 저장한 뒤 게임을 시작해 주세요."); return
        }
        guard game == nil, let root else { host.gameFailed(message: "게임 화면을 열지 못했어요."); return }
        do {
            try files.createDirectory(at: bridge, withIntermediateDirectories: true)
            for name in ["request.json", "response.json", "return.json", "game-ready.json", "restart.json"] {
                let url = bridge.appendingPathComponent(name)
                if files.fileExists(atPath: url.path) { try files.removeItem(at: url) }
            }
            try signal("host.json")
            try signal("ready.json")
            if engine.initialized { try signal("restart.json") }
        } catch { host.gameFailed(message: "게임 연결을 준비하지 못했어요. \(error.localizedDescription)"); return }
        closing = false
        let container = GameContainer()
        game = container
        gameStartedAt = Date()
        container.onClose = { [weak self] in self?.finishGame() }
        container.onAppear = { [weak self, weak container] in
            guard let self, let container, self.game === container, !self.closing else { return }
            guard let controller = self.engine.prepare() else {
                self.host.reportError(message: "게임 엔진을 준비하지 못했어요."); self.finishGame(); return
            }
            container.embed(controller)
            self.engine.resume()
        }
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in self?.pump() }
        RunLoop.main.add(timer, forMode: .common)
        mailboxTimer = timer
        root.present(container, animated: true)
    }
    private func signal(_ name: String) throws { try "{\"version\":1}".write(to: bridge.appendingPathComponent(name), atomically: true, encoding: .utf8) }
    private func pump() {
        guard game != nil, !closing else { return }
        do {
            let request = bridge.appendingPathComponent("request.json")
            if files.fileExists(atPath: request.path) {
                let text = try readText(request)
                let response = host.exchange(request: text)
                try files.removeItem(at: request)
                try response.write(to: bridge.appendingPathComponent("response.json"), atomically: true, encoding: .utf8)
            }
            let ready = bridge.appendingPathComponent("game-ready.json")
            if files.fileExists(atPath: ready.path) {
                try files.removeItem(at: ready)
                game?.ready()
                gameStartedAt = nil
            }
            if files.fileExists(atPath: bridge.appendingPathComponent("return.json").path) { finishGame() }
            else if let started = gameStartedAt, Date().timeIntervalSince(started) > 90 {
                host.reportError(message: "게임 준비가 오래 걸리고 있어요. 다시 시도해 주세요."); finishGame()
            }
        } catch { host.reportError(message: "게임 연결 오류: \(error.localizedDescription)"); finishGame() }
    }
    private func finishGame() {
        guard !closing, let container = game else { return }
        closing = true
        mailboxTimer?.invalidate(); mailboxTimer = nil
        host.finishGame()
        engine.suspend()
        container.dismiss(animated: true) { [weak self] in
            container.detach()
            self?.game = nil
            self?.closing = false
        }
        for name in ["ready.json", "host.json"] { try? files.removeItem(at: bridge.appendingPathComponent(name)) }
    }
}

private final class GameContainer: UIViewController {
    var onClose: (() -> Void)?
    var onAppear: (() -> Void)?
    private var child: UIViewController?
    private let loading = UIView()
    private let close = UIButton(type: .system)
    override init(nibName: String? = nil, bundle: Bundle? = nil) {
        super.init(nibName: nibName, bundle: bundle)
        modalPresentationStyle = .fullScreen
        isModalInPresentation = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var prefersStatusBarHidden: Bool { true }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        loading.backgroundColor = .systemBackground
        loading.frame = view.bounds; loading.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        let label = UILabel(); label.text = "게임 학습을 준비하고 있어요"; label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        loading.addSubview(label)
        NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: loading.centerXAnchor), label.centerYAnchor.constraint(equalTo: loading.centerYAnchor)])
        view.addSubview(loading)
        close.setTitle("‹ 서재", for: .normal)
        close.accessibilityLabel = "게임을 마치고 서재로"
        close.backgroundColor = .secondarySystemBackground
        close.layer.cornerRadius = 12
        close.translatesAutoresizingMaskIntoConstraints = false
        close.addTarget(self, action: #selector(finish), for: .touchUpInside)
        view.addSubview(close)
        NSLayoutConstraint.activate([close.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12), close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8), close.widthAnchor.constraint(equalToConstant: 72), close.heightAnchor.constraint(equalToConstant: 40)])
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        let appear = onAppear; onAppear = nil
        DispatchQueue.main.async { appear?() }
    }
    func embed(_ controller: UIViewController) {
        child = controller
        addChild(controller)
        controller.view.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(controller.view, at: 0)
        NSLayoutConstraint.activate([
            controller.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            controller.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            controller.view.topAnchor.constraint(equalTo: close.bottomAnchor, constant: 8),
            controller.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        controller.didMove(toParent: self)
    }
    func ready() { loading.removeFromSuperview() }
    func detach() { child?.willMove(toParent: nil); child?.view.removeFromSuperview(); child?.removeFromParent(); child = nil }
    @objc private func finish() { onClose?() }
}

#if DEBUG
// Explicit, isolated integration check for the real UIKit ↔ Kotlin ↔ Godot path.
// No test document or preference is written into the user's library.
private extension StudyPlatform {
    func beginProbeIfRequested() {
        if ProcessInfo.processInfo.arguments.contains("--lecture-device-probe") && !probeStarted {
            probeStarted = true
            Task { @MainActor in
                do {
                    let documents = files.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    let report = try await LectureAudio.runDeviceProbe(input: documents.appendingPathComponent("lecture-korean-fixture.m4a"), save: { id, title, body in
                        self.host.saveNote(id: id, title: title, body: body)
                    })
                    host.reload()
                    guard host.notebook.markdown.contains("디지털"), host.notebook.notes.count == 2 else { throw self.message("Kotlin note save failed") }
                    try self.json(report).write(to: documents.appendingPathComponent("lecture-device-report.json"), atomically: true, encoding: .utf8)
                    NSLog("DEVICE TRANSCRIPTION COMPLETE %@", self.json(report))
                } catch { NSLog("DEVICE TRANSCRIPTION FAILED %@", error.localizedDescription) }
            }
            return
        }
        if ProcessInfo.processInfo.arguments.contains("--lecture-local-probe") && !probeStarted {
            probeStarted = true
            Task { @MainActor in
                do {
                    let documents = files.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    let report = try await LectureAudio.runLocalProbe(input: documents.appendingPathComponent("lecture-korean-fixture.m4a"), save: { id, title, body in
                        self.host.saveNote(id: id, title: title, body: body)
                    })
                    host.reload()
                    guard host.notebook.markdown.contains("디지털"), host.notebook.notes.count == 2 else { throw self.message("Kotlin note save failed") }
                    try self.json(report).write(to: documents.appendingPathComponent("lecture-local-report.json"), atomically: true, encoding: .utf8)
                    NSLog("LOCAL TRANSCRIPTION COMPLETE %@", self.json(report))
                } catch { NSLog("LOCAL TRANSCRIPTION FAILED %@", error.localizedDescription) }
            }
            return
        }
        if ProcessInfo.processInfo.arguments.contains("--lecture-transcription-probe") && !probeStarted {
            probeStarted = true
            Task { @MainActor in
                do {
                    guard #available(iOS 26.0, *) else { throw self.message("iOS 26 is required") }
                    let documents = files.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    let report = try await LectureAudio.runTranscriptionProbe(input: documents.appendingPathComponent("lecture-korean-fixture.aiff"), save: { id, title, body in
                        self.host.saveNote(id: id, title: title, body: body)
                    })
                    host.reload()
                    guard host.notebook.markdown.contains("직접 수정한 필기"), host.notebook.notes.count == 2 else {
                        throw self.message("Kotlin saved note content or idempotent update failed")
                    }
                    try self.json(report).write(to: documents.appendingPathComponent("lecture-transcription-report.json"), atomically: true, encoding: .utf8)
                    NSLog("TRANSCRIPTION COMPLETE %@", self.json(report))
                } catch { NSLog("TRANSCRIPTION FAILED %@", error.localizedDescription) }
            }
            return
        }
        if ProcessInfo.processInfo.arguments.contains("--lecture-probe") && !probeStarted {
            probeStarted = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                do {
                    let checks = try LectureAudio.runStorageProbe()
                    NSLog("LECTURE COMPLETE %@", self.json(["checks": checks, "failures": []]))
                } catch { NSLog("LECTURE FAILED %@", error.localizedDescription) }
                if #available(iOS 26.0, *) {
                    Task {
                        let supported = await SpeechTranscriber.supportedLocales.map(\.identifier)
                        let installed = await SpeechTranscriber.installedLocales.map(\.identifier)
                        NSLog("LECTURE SPEECH SUPPORT %@", self.json(["available": SpeechTranscriber.isAvailable,
                            "koreanSupported": supported.filter { $0.hasPrefix("ko") },
                            "koreanInstalled": installed.filter { $0.hasPrefix("ko") }]))
                    }
                }
                self.openRecordings()
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    guard let window = self.root?.presentedViewController?.view.window ?? self.root?.view.window else { return }
                    let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                        window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
                    }
                    let documents = self.files.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    try? image.pngData()?.write(to: documents.appendingPathComponent("lecture-library.png"))
                }
            }
            return
        }
        guard probeID != nil, !probeStarted else { return }
        probeStarted = true
        Task { @MainActor in await runNotebookProbe() }
    }
    func runNotebookProbe() async {
        let documents = files.urls(for: .documentDirectory, in: .userDomainMask)[0]
        var checks: [String] = []
        var failures: [String] = []
        func check(_ condition: Bool, _ name: String) {
            checks.append(name)
            if !condition { failures.append(name) }
            NSLog("NOTEBOOK %@ %@", condition ? "PASS" : "FAIL", name)
        }
        func waitFor(_ description: String, _ condition: () -> Bool) async throws {
            let deadline = Date().addingTimeInterval(90)
            while !condition() {
                if Date() > deadline { throw message("Timed out: " + description) }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
        }
        func capture(_ name: String) {
            guard let window = root?.view.window else { return }
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            try? image.pngData()?.write(to: documents.appendingPathComponent(name + ".png"))
        }
        do {
            try await waitFor("native library") { self.root?.view.window != nil }
            try await Task.sleep(nanoseconds: 1_000_000_000)
            check(host.notebook.ready && !engine.initialized, "Cold launch opens library without initializing Godot")
            if (Bundle.main.object(forInfoDictionaryKey: "StudyGoogleClientID") as? String ?? "").isEmpty {
                let result: String = await withCheckedContinuation { continuation in
                    authorizeDrive { continuation.resume(returning: $0) }
                }
                let response = try JSONSerialization.jsonObject(with: Data(result.utf8)) as? [String: Any]
                check((response?["error"] as? String)?.contains("OAuth") == true && response?["accessToken"] == nil,
                      "Unconfigured Google connection shows setup guidance without claiming authentication")
            }
            capture("notebook-library")
            host.sharing = true
            try await Task.sleep(nanoseconds: 800_000_000)
            capture("folder-share")
            host.sharing = false
            check(host.importDocument(kind: "notes", filename: "강의.md", content: "## 보안 수업\n\n**기밀성**은 허가받지 않은 열람을 막습니다.\n\n|항목|의미|\n|---|---|\n|무결성|변조 방지|") == nil, "Ordinary Markdown imports without quiz headings")
            let saved = host.notebook.markdown
            host.reload()
            check(host.notebook.markdown == saved, "Imported note persists on disk")
            doCopyText(text: "문제 생성 프롬프트 확인")
            check(UIPasteboard.general.string == "문제 생성 프롬프트 확인", "Native clipboard works")
            pickDocument(kind: "notes")
            try await Task.sleep(nanoseconds: 2_000_000_000)
            NSLog("NOTEBOOK picker root=%@ top=%@ presented=%@", String(describing: root), String(describing: presenter), String(describing: root?.presentedViewController))
            capture("notebook-file-picker")
            check(presenter is UIDocumentPickerViewController, "Native Files picker presents")
            presenter?.dismiss(animated: false)
            try await Task.sleep(nanoseconds: 500_000_000)
            importKind = nil
            check(host.importDocument(kind: "question-sets", filename: "invalid.json", content: "{}") != nil && host.quizzes.isEmpty, "Invalid question file rejected without saving")
            var previousSession = ""
            for round in 1...3 {
                let quiz: [String: Any] = ["schemaVersion": 1, "title": "보안 복습 \(round)", "sourceNoteId": "", "sourceNoteTitle": "보안 수업", "questions": (1...3).map { index in
                    ["id": "question-\(index)", "prompt": "보안 복습 \(round) — 사례 \(index): 허가받지 않은 열람을 막는 원칙은?", "choices": ["기밀성", "무결성", "가용성"], "correctIndex": 0, "explanation": "기밀성은 허가받지 않은 열람을 막는 원칙입니다.", "sourceQuote": "기밀성은 허가받지 않은 열람을 막습니다."] as [String: Any]
                }]
                check(host.importDocument(kind: "question-sets", filename: "quiz.json", content: json(quiz)) == nil, "Valid question file imports, round \(round)")
                try await Task.sleep(nanoseconds: 500_000_000)
                guard let selected = host.quizzes.first(where: { $0.document.title == "보안 복습 \(round)" }) else { throw message("Missing imported quiz") }
                try files.createDirectory(at: bridge, withIntermediateDirectories: true)
                try json(["round": round]).write(to: bridge.appendingPathComponent("probe-stage.json"), atomically: true, encoding: .utf8)
                let reportURL = documents.appendingPathComponent("notebook-engine-\(round).json")
                if files.fileExists(atPath: reportURL.path) { try files.removeItem(at: reportURL) }
                host.startLearning(id: selected.id)
                if round == 3 {
                    try await waitFor("third game ready") { self.game != nil && self.gameStartedAt == nil }
                    try await Task.sleep(nanoseconds: 500_000_000)
                    // Invoke the same action as the always-visible UIKit close button.
                    game?.onClose?()
                }
                try await waitFor("return to notebook, round \(round)") { self.game == nil }
                check(engine.initialized && !engine.rendering && !host.launching && mailboxTimer == nil, "Game returns to notebook; rendering and transport stop, round \(round)")
                if round < 3 {
                    guard let report = try JSONSerialization.jsonObject(with: Data(contentsOf: reportURL)) as? [String: Any] else { throw message("Invalid engine report") }
                    check((report["failures"] as? [String])?.isEmpty == true, "Seven continuous Godot gates and Kotlin grading complete, round \(round)")
                    guard let session = report["session"] as? [String: Any], let sessionID = session["sessionId"] as? String else { throw message("Engine report has no session") }
                    check(session["title"] as? String == "보안 복습 \(round)" && sessionID != previousSession, "Reentry uses selected file and fresh session, round \(round)")
                    previousSession = sessionID
                    check(host.recap?.answered == 7 && host.recap?.correct == (round == 1 ? 6 : 7) && host.recap?.reviewed == (round == 1 ? 1 : 0) && host.recap?.title == "보안 복습 \(round)", "Native recap persists, round \(round)")
                } else {
                    check(host.recap?.answered == 0, "Native early close ends the unfinished session")
                }
            }
            capture("notebook-return")
        } catch { failures.append(error.localizedDescription) }
        let report: [String: Any] = ["checks": checks, "failures": failures, "device": UIDevice.current.model, "system": UIDevice.current.systemVersion]
        try? json(report).write(to: documents.appendingPathComponent("notebook-report.json"), atomically: true, encoding: .utf8)
        NSLog("NOTEBOOK COMPLETE %@", json(report))
    }
}
#endif
