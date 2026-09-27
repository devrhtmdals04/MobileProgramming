import AVFoundation
import UIKit
import Shared

/// Audio and file ownership stays native. The library and controls are shared Kotlin UI.
final class LectureAudio: NSObject, LecturePlatform, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    struct Entry: Codable {
        let id: String
        var title: String
        let date: String
        var seconds: Int
        var transcript: String? = nil
    }
    lazy var host = LectureHost(platform: self)
    private let folder: URL
    private var capture: LectureCaptureSession?
    private var provisional = ""
    private var speechStatus = ""
    private var editorId = ""
    private var editorTitle = ""
    private var editorText = ""
    private var startupCancelled = false
    var saveNote: ((String, String, String) -> String?)?
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var current: Entry?
    private var entries: [Entry] = []
    private var playing = ""
    private var pending = false
    private var message = ""
    private var timer: Timer?
    private var observers: [NSObjectProtocol] = []
    var close: (() -> Void)?
    var isRecording: Bool { recorder != nil || capture != nil || pending }

    init(folder: URL) {
        self.folder = folder
        super.init()
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            if (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue {
                self?.interrupt("통화 또는 다른 오디오 사용으로 중단되어 저장했어요.")
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            if (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                self?.interrupt("오디오 기기 연결이 끊겨 녹음을 저장했어요.")
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            self?.interrupt("오디오 시스템이 재시작되어 녹음을 중단했어요. 저장된 파일을 확인해 주세요.")
        })
        reload()
    }
    deinit {
        timer?.invalidate()
        observers.forEach(NotificationCenter.default.removeObserver)
    }
    private func url(_ id: String, _ ext: String) throws -> URL {
        guard UUID(uuidString: id) != nil else { throw failure("올바르지 않은 녹음 ID입니다.") }
        return folder.appendingPathComponent(id).appendingPathExtension(ext)
    }
    private func failure(_ text: String) -> NSError { NSError(domain: "StudyAudio", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
    private func write(_ entry: Entry) throws {
        try JSONEncoder().encode(entry).write(to: url(entry.id, "json"), options: .atomic)
    }
    private func reload() {
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            entries = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
                .compactMap { file -> Entry? in
                    guard var entry = try? JSONDecoder().decode(Entry.self, from: Data(contentsOf: file)),
                          let audio = try? url(entry.id, "m4a"), FileManager.default.fileExists(atPath: audio.path),
                          entry.id != current?.id else { return nil }
                    if entry.seconds == 0, let recovered = try? AVAudioPlayer(contentsOf: audio) {
                        entry.seconds = Int(recovered.duration.rounded(.up))
                        try? write(entry)
                    }
                    return entry
                }.sorted { $0.date > $1.date }
        } catch { message = "녹음 목록을 읽지 못했어요. \(error.localizedDescription)" }
        publish()
    }
    private func publish() {
        let rows = entries.map { ["id": $0.id, "title": $0.title, "date": $0.date, "seconds": $0.seconds, "hasTranscript": $0.transcript != nil] as [String: Any] }
        let data: [String: Any] = ["recordings": rows, "recording": recorder != nil || capture != nil, "pending": pending,
            "seconds": Int(capture?.seconds ?? recorder?.currentTime ?? 0), "playing": playing,
            "playbackSeconds": Int(player?.currentTime ?? 0), "message": message,
            "transcript": String((current?.transcript ?? "").suffix(4000)), "provisional": provisional,
            "speechStatus": speechStatus, "editorId": editorId, "editorTitle": editorTitle, "editorText": editorText]
        if let json = try? JSONSerialization.data(withJSONObject: data), let text = String(data: json, encoding: .utf8) {
            host.updateState(json: text)
        }
    }
    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.publish() }
    }
    func audioCommand(action: String, id: String, title: String) {
        guard !pending else { return }
        if (recorder != nil || capture != nil) && ["start", "play", "delete", "rename", "openTranscript", "saveTranscript"].contains(action) { return }
        do {
            switch action {
            case "refresh": reload()
            case "start": requestRecording(title)
            case "stop": finish()
            case "play":
                stopPlayback()
                guard entries.contains(where: { $0.id == id }) else { throw failure("녹음을 찾지 못했어요.") }
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default)
                try session.setActive(true)
                let audio = try AVAudioPlayer(contentsOf: url(id, "m4a"))
                audio.delegate = self
                guard audio.play() else { throw failure("녹음을 재생할 수 없어요.") }
                player = audio; playing = id; message = ""; startTimer()
            case "stopPlayback": stopPlayback()
            case "backward": if let player { player.currentTime = max(0, player.currentTime - 15) }
            case "forward": if let player { player.currentTime = min(player.duration, player.currentTime + 15) }
            case "rename":
                guard var entry = entries.first(where: { $0.id == id }), !title.isEmpty else { return }
                entry.title = String(title.prefix(120)); try write(entry); reload()
            case "delete":
                guard entries.contains(where: { $0.id == id }) else { return }
                if playing == id { stopPlayback() }
                try FileManager.default.removeItem(at: url(id, "m4a"))
                try FileManager.default.removeItem(at: url(id, "json")); reload()
            case "openTranscript":
                guard let entry = entries.first(where: { $0.id == id }), let text = entry.transcript else { return }
                editorId = id; editorTitle = entry.title; editorText = text
            case "closeTranscript": editorId = ""; editorText = ""
            case "saveTranscript":
                guard var entry = entries.first(where: { $0.id == id }), !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let saveNote else { throw failure("노트로 저장할 받아쓰기 내용이 없어요.") }
                entry.transcript = title; try write(entry)
                if let error = saveNote(id, String(entry.title.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ").prefix(60)), "## 강의 받아쓰기\n\n" + title) { throw failure(error) }
                editorId = ""; editorText = ""; message = "받아쓰기를 노트 서재에 저장했어요. 다시 저장하면 같은 노트를 갱신합니다."
                reload()
            case "close": stopPlayback(); close?()
            default: break
            }
        } catch { message = error.localizedDescription; if recorder == nil && capture == nil { deactivate() } }
        publish()
    }
    private func requestRecording(_ title: String) {
        stopPlayback(); pending = true; message = ""; publish()
        AVAudioApplication.requestRecordPermission { [weak self] granted in
            DispatchQueue.main.async {
                guard let self else { return }
                self.pending = false
                guard granted else {
                    self.message = "마이크 권한이 꺼져 있어요. iPhone 설정 → Study Helper에서 마이크를 허용해 주세요."
                    self.publish(); return
                }
                guard UIApplication.shared.applicationState == .active else {
                    self.message = "앱 화면에서 녹음 시작을 다시 눌러 주세요."; self.publish(); return
                }
                self.begin(title)
            }
        }
    }
    private func begin(_ title: String) {
        if #available(iOS 26.0, *) {
            pending = true; startupCancelled = false; speechStatus = "한국어 받아쓰기 준비 중…"; provisional = ""
            publish()
            Task { @MainActor in await beginLive(title) }
            return
        }
        speechStatus = "실시간 받아쓰기는 iOS 26 이상에서 지원합니다. 음성은 저장합니다."
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)
            let date = DateFormatter.localizedString(from: Date(), dateStyle: .medium, timeStyle: .short)
            let entry = Entry(id: UUID().uuidString.lowercased(), title: title.isEmpty ? "강의 \(date)" : title, date: ISO8601DateFormatter().string(from: Date()), seconds: 0)
            try write(entry)
            let audio = try AVAudioRecorder(url: url(entry.id, "m4a"), settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100,
                AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
            audio.delegate = self
            guard audio.prepareToRecord(), audio.record() else { throw failure("녹음을 시작하지 못했어요. 마이크를 확인해 주세요.") }
            current = entry; recorder = audio; startTimer()
        } catch { message = error.localizedDescription; deactivate() }
        publish()
    }
    @available(iOS 26.0, *)
    private func beginLive(_ title: String) async {
        var preparing: LectureLiveCapture?
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .default); try session.setActive(true)
            let date = DateFormatter.localizedString(from: Date(), dateStyle: .medium, timeStyle: .short)
            let entry = Entry(id: UUID().uuidString.lowercased(), title: title.isEmpty ? "강의 \(date)" : title,
                date: ISO8601DateFormatter().string(from: Date()), seconds: 0, transcript: "")
            try write(entry); current = entry
            let live = try LectureLiveCapture(url: url(entry.id, "m4a"), failed: { [weak self] reason in
                if self?.current?.id == entry.id { self?.interrupt(reason) }
            })
            preparing = live
            await live.prepareSpeech(update: { [weak self] text, partial in
                guard let self, self.current?.id == entry.id else { return }
                let changed = self.current?.transcript != text
                self.current?.transcript = text; self.provisional = partial
                if changed, let entry = self.current {
                    do { try self.write(entry) } catch { self.message = "받아쓰기 중간 저장에 실패했어요. 저장 공간을 확인해 주세요." }
                }
                self.publish()
            }, failure: { [weak self] reason in self?.speechStatus = reason; self?.publish() })
            guard !startupCancelled, UIApplication.shared.applicationState == .active else {
                _ = live.stopAudio(); await live.finishTranscription()
                current = nil; pending = false; message = "앱 화면에서 녹음 시작을 다시 눌러 주세요."
                deactivate(); reload(); return
            }
            try live.start(); capture = live; preparing = nil; pending = false
            if speechStatus == "한국어 받아쓰기 준비 중…" { speechStatus = "한국어 · 기기 내 실시간 받아쓰기" }
            startTimer(); publish()
        } catch {
            if let preparing { _ = preparing.stopAudio(); await preparing.finishTranscription() }
            current = nil; capture = nil; pending = false; message = error.localizedDescription
            deactivate(); reload()
        }
    }
    private func finish() {
        if let live = capture {
            let duration = live.stopAudio(); capture = nil; pending = true
            current?.seconds = Int(duration.rounded(.up))
            timer?.invalidate(); timer = nil; message = "녹음을 저장하고 마지막 문장을 확정하고 있어요…"
            if var entry = current {
                do {
                    let saved = try AVAudioPlayer(contentsOf: url(entry.id, "m4a"))
                    entry.seconds = Int(saved.duration.rounded(.up)); current = entry
                    try write(entry)
                } catch { message = "녹음 파일을 확인하지 못했어요. 너무 짧은 녹음이거나 저장 공간이 부족할 수 있어요." }
            }
            deactivate(); publish()
            Task { @MainActor in
                await live.finishTranscription()
                if let current { do { try write(current) } catch { self.message = "받아쓰기 저장에 실패했어요." } }
                let hasText = !(self.current?.transcript?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
                self.current = nil; provisional = ""; pending = false
                if message == "녹음을 저장하고 마지막 문장을 확정하고 있어요…" {
                    message = hasText ? "녹음과 받아쓰기를 저장했어요." : "받아쓴 문장이 없어 음성만 저장했어요."
                }
                reload()
            }
            return
        }
        guard let audio = recorder, var entry = current else { return }
        entry.seconds = Int(audio.currentTime.rounded(.up))
        audio.delegate = nil; audio.stop(); recorder = nil; current = nil
        timer?.invalidate(); timer = nil
        do {
            // Final duration also confirms the m4a container is playable.
            let saved = try AVAudioPlayer(contentsOf: url(entry.id, "m4a"))
            entry.seconds = Int(saved.duration.rounded(.up))
            try write(entry)
            message = "녹음을 저장했어요."
        } catch { message = "녹음 저장을 완료하지 못했어요. 남은 파일을 보존했으니 재생 여부를 확인해 주세요." }
        deactivate(); reload()
    }
    private func stopPlayback() {
        player?.stop(); player = nil; playing = ""
        if recorder == nil && capture == nil { timer?.invalidate(); timer = nil; deactivate() }
    }
    private func deactivate() { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    private func interrupt(_ reason: String) {
        let wasRecording = recorder != nil || capture != nil
        if pending { startupCancelled = true }
        finish(); stopPlayback()
        if wasRecording { message = reason }
        publish()
    }
    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        interrupt(flag ? "녹음이 종료되어 저장했어요." : "녹음이 중단되었어요. 저장된 파일을 확인해 주세요.")
    }
    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        interrupt("녹음 중 오류가 발생했어요. 저장된 파일을 확인해 주세요.")
    }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { stopPlayback(); publish() }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        stopPlayback(); message = "이 녹음을 재생하지 못했어요."; publish()
    }
    #if DEBUG
    @available(iOS 26.0, *)
    @MainActor
    static func runTranscriptionProbe(input: URL, save: @escaping (String, String, String) -> String?) async throws -> [String: Any] {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("transcription-probe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let audio = LectureAudio(folder: folder)
        var checks: [String] = []
        func check(_ value: Bool, _ label: String) throws {
            guard value else { throw audio.failure(label) }; checks.append(label)
        }
        var merged = LectureTranscript()
        merged.receive(position: 0, text: "디지", final: false)
        merged.receive(position: 0, text: "디지털", final: false)
        try check(merged.text.isEmpty && merged.provisional == "디지털", "Provisional revisions replace without accumulating")
        merged.receive(position: 0, text: "디지털 시스템", final: true)
        merged.receive(position: 0, text: "디지털 시스템", final: true)
        try check(merged.text == "디지털 시스템" && merged.provisional.isEmpty, "Repeated final result does not duplicate text")
        var finalText = ""
        var speechError = ""
        let speech = LectureSpeechSession(update: { text, _ in finalText = text }, failure: { speechError = $0 })
        let source = try AVAudioFile(forReading: input)
        try await speech.prepare(format: source.processingFormat)
        let entry = Entry(id: UUID().uuidString.lowercased(), title: "디지털시스템입문 · 받아쓰기 검사",
            date: ISO8601DateFormatter().string(from: Date()), seconds: 0, transcript: "")
        do {
            let output = try AVAudioFile(forWriting: audio.url(entry.id, "m4a"), settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: source.processingFormat.sampleRate,
                AVNumberOfChannelsKey: source.processingFormat.channelCount, AVEncoderBitRateKey: 64000])
            while source.framePosition < source.length {
                let buffer = AVAudioPCMBuffer(pcmFormat: source.processingFormat, frameCapacity: 4096)!
                try source.read(into: buffer)
                try output.write(from: buffer)
                speech.append(buffer)
                // Exercise streaming delivery rather than file-only recognition.
                try await Task.sleep(nanoseconds: 10_000_000)
            }
        }
        await speech.finish()
        try check(speechError.isEmpty, "Streaming recognition finishes without errors: " + speechError)
        try check(finalText.contains("디지털") && finalText.contains("논리"), "Installed Korean model transcribes synthesized lecture words")
        var saved = entry; saved.transcript = finalText
        try audio.write(saved); audio.reload()
        let reopened = LectureAudio(folder: folder)
        try check(reopened.entries.first?.transcript == finalText, "Transcript survives reopening the recording library")
        audio.saveNote = save
        audio.audioCommand(action: "openTranscript", id: entry.id, title: "")
        try check(audio.editorText == finalText, "Transcript editor opens saved recognition text")
        let edited = finalText + "\n\n직접 수정한 필기입니다."
        audio.audioCommand(action: "saveTranscript", id: entry.id, title: edited)
        try check(audio.editorId.isEmpty && audio.message.contains("노트 서재"), "Edited transcript saves through the Kotlin notebook")
        audio.audioCommand(action: "openTranscript", id: entry.id, title: "")
        try check(audio.editorText == edited, "Edited transcription is also persisted with audio")
        audio.audioCommand(action: "saveTranscript", id: entry.id, title: edited)
        return ["checks": checks, "failures": [], "transcript": finalText, "noteId": entry.id, "microphoneUsed": false]
    }
    /// Exercises real AAC files without requesting microphone permission or touching lecture data.
    static func runStorageProbe() throws -> [String] {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("lecture-probe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let audio = LectureAudio(folder: folder)
        var checks: [String] = []
        func check(_ value: Bool, _ label: String) throws {
            guard value else { throw audio.failure(label) }
            checks.append(label)
        }
        let entry = Entry(id: UUID().uuidString.lowercased(), title: "강의 저장 검사", date: "2026-09-27T00:00:00Z", seconds: 0)
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 132300)!
        buffer.frameLength = 132300
        memset(buffer.floatChannelData![0], 0, Int(buffer.frameLength) * MemoryLayout<Float>.size)
        do {
            let file = try AVAudioFile(forWriting: audio.url(entry.id, "m4a"), settings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
            try file.write(from: buffer)
        }
        try audio.write(entry)
        audio.reload()
        try check(audio.entries.count == 1 && audio.entries[0].seconds >= 3, "Saved AAC file reloads and recovers duration")
        audio.audioCommand(action: "rename", id: entry.id, title: "디지털시스템입문")
        let reopened = LectureAudio(folder: folder)
        try check(reopened.entries.first?.title == "디지털시스템입문", "Renamed title survives reopening")
        audio.audioCommand(action: "play", id: entry.id, title: "")
        try check(audio.player?.isPlaying == true, "Stored AAC plays through native audio")
        audio.audioCommand(action: "forward", id: "", title: "")
        try check((audio.player?.currentTime ?? 0) <= (audio.player?.duration ?? 0), "Playback seek stays in bounds")
        audio.audioCommand(action: "stopPlayback", id: "", title: "")
        try check(audio.player == nil && audio.playing.isEmpty, "Playback stop releases audio")
        audio.audioCommand(action: "delete", id: entry.id, title: "")
        try check(audio.entries.isEmpty && !FileManager.default.fileExists(atPath: try audio.url(entry.id, "m4a").path), "Confirmed deletion removes audio and list entry")
        try check((try? audio.url("../invalid", "m4a")) == nil, "Invalid file IDs cannot escape recording storage")
        return checks
    }
    #endif

}
