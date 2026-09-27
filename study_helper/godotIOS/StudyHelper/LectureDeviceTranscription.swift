import AVFoundation
import CryptoKit
import Foundation
import StudyWhisper

/// Offline Whisper. Only model installation uses the network; audio never does.
final class LectureDeviceTranscription: @unchecked Sendable {
    static let modelName = "ggml-small-q5_1.bin"
    static let digest = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    static var folder: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WhisperModels")
    }
    static var model: URL { folder.appendingPathComponent(modelName) }
    static var ready: Bool { FileManager.default.fileExists(atPath: model.path) }
    private let lock = NSLock()
    private var job: OpaquePointer?
    private var cancelled = false
    var progress: Int { lock.lock(); defer { lock.unlock() }; return Int(study_stt_progress(job)) }
    func cancel() { lock.lock(); cancelled = true; study_stt_cancel(job); lock.unlock() }
    private var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    private func failure(_ text: String) -> NSError { NSError(domain: "DeviceWhisper", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }

    static func installModel() async throws {
        let source = URL(string: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin")!
        let (download, response) = try await URLSession.shared.download(from: source)
        defer { try? FileManager.default.removeItem(at: download) }
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        try await Task.detached(priority: .utility) { try importModel(download) }.value
        try Task.checkCancellation()
    }
    static func importModel(_ source: URL) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        var hash = SHA256()
        while let data = try input.read(upToCount: 1024 * 1024), !data.isEmpty { hash.update(data: data) }
        guard hash.finalize().map({ String(format: "%02x", $0) }).joined() == digest else {
            throw NSError(domain: "DeviceWhisper", code: 2, userInfo: [NSLocalizedDescriptionKey: "모델 파일 검증에 실패했어요. 다시 다운로드해 주세요."])
        }
        var directory = folder
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        if ready { return }
        let staged = folder.appendingPathComponent(UUID().uuidString + ".part")
        defer { try? FileManager.default.removeItem(at: staged) }
        try FileManager.default.copyItem(at: source, to: staged)
        try FileManager.default.moveItem(at: staged, to: model)
    }

    func transcribe(file: URL) async throws -> String {
        // A fresh instance is created for every job, so an early cancellation is retained.
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                DispatchQueue.global(qos: .userInitiated).async {
                    do { continuation.resume(returning: try self.perform(file)) }
                    catch { continuation.resume(throwing: error) }
                }
            }
        }, onCancel: { self.cancel() })
    }
    private func perform(_ file: URL) throws -> String {
        guard Self.ready else { throw failure("먼저 오프라인 모델을 다운로드해 주세요.") }
        let work = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }
        let pcm = work.appendingPathComponent("audio.f32")
        do { try decode(file, to: pcm) } catch { throw failure("음성 준비: \(error)") }
        if isCancelled { throw CancellationError() }
        let pointer = study_stt_create()
        lock.lock(); job = pointer; if cancelled { study_stt_cancel(job) }; lock.unlock()
        defer { lock.lock(); job = nil; study_stt_destroy(pointer); lock.unlock() }
        let output = work.appendingPathComponent("text.txt")
        let result = study_stt_run(pointer, Self.model.path, pcm.path, output.path, 1)
        if result == 1 || isCancelled { throw CancellationError() }
        guard result == 0 else { throw failure("기기에서 변환하지 못했어요. 다른 앱을 닫고 다시 시도해 주세요.") }
        let text = try String(contentsOf: output, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw failure("인식된 음성이 없어요. 기존 받아쓰기는 유지됩니다.") }
        return text
    }
    private func decode(_ file: URL, to output: URL) throws {
        let input: AVAudioFile
        do { input = try AVAudioFile(forReading: file) } catch { throw failure("녹음 열기: \(error)") }
        guard Double(input.length) / input.processingFormat.sampleRate <= 7200 else { throw failure("기기 내 변환은 2시간 이내 녹음을 지원합니다.") }
        let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!
        guard let converter = AVAudioConverter(from: input.processingFormat, to: format),
              let source = AVAudioPCMBuffer(pcmFormat: input.processingFormat, frameCapacity: 8192),
              let destination = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8192) else { throw failure("녹음 형식을 변환하지 못했어요.") }
        FileManager.default.createFile(atPath: output.path, contents: nil)
        let handle: FileHandle
        do { handle = try FileHandle(forWritingTo: output) } catch { throw failure("PCM 파일 열기: \(error)") }
        defer { try? handle.close() }
        var ended = false
        while true {
            if isCancelled { throw CancellationError() }
            var conversionError: NSError?
            var readError: Error?
            let status = converter.convert(to: destination, error: &conversionError) { count, state in
                if ended { state.pointee = .endOfStream; return nil }
                do {
                    let remaining = input.length - input.framePosition
                    if remaining <= 0 { ended = true; state.pointee = .endOfStream; return nil }
                    let requested = min(max(count, 1), source.frameCapacity, AVAudioFrameCount(remaining))
                    try input.read(into: source, frameCount: requested)
                    if source.frameLength == 0 { ended = true; state.pointee = .endOfStream; return nil }
                    state.pointee = .haveData; return source
                } catch { readError = error; state.pointee = .endOfStream; return nil }
            }
            if let readError { throw failure("PCM 읽기: \(readError)") }
            if let conversionError { throw failure("리샘플링: \(conversionError)") }
            if destination.frameLength > 0 {
                do { try handle.write(contentsOf: Data(bytes: destination.floatChannelData![0], count: Int(destination.frameLength) * MemoryLayout<Float>.size)) }
                catch { throw failure("PCM 쓰기: \(error)") }
            }
            if status == .endOfStream { break }
            if status == .error { throw failure("음성 디코딩에 실패했어요.") }
        }
    }
}
