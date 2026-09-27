import AVFoundation
import Speech

/// Final results replace by audio position; provisional revisions are never appended to the note.
struct LectureTranscript {
    private var finalized: [Int64: String] = [:]
    private(set) var provisional = ""
    var text: String { finalized.keys.sorted().compactMap { finalized[$0] }.joined(separator: "\n") }
    mutating func receive(position: Int64, text: String, final: Bool) {
        if final { finalized[position] = text; provisional = "" }
        else if finalized[position] == nil { provisional = text }
    }
}

protocol LectureCaptureSession: AnyObject {
    var seconds: Double { get }
    func start() throws
    func stopAudio() -> Double
    func finishTranscription() async
}

@available(iOS 26.0, *)
final class LectureSpeechSession {
    private let transcriber = SpeechTranscriber(locale: Locale(identifier: "ko-KR"), preset: .progressiveTranscription)
    private let analyzer: SpeechAnalyzer
    private let stream: AsyncStream<AnalyzerInput>
    private let continuation: AsyncStream<AnalyzerInput>.Continuation
    private var resultsTask: Task<Void, Never>?
    private var converter: AVAudioConverter?
    private var targetFormat: AVAudioFormat?
    private var started = false
    private var failed = false
    private var transcript = LectureTranscript()
    // Buffer conversion and submission happen only on the capture queue.
    private let update: @MainActor (String, String) -> Void
    private let failure: @MainActor (String) -> Void

    init(update: @escaping @MainActor (String, String) -> Void, failure: @escaping @MainActor (String) -> Void) {
        self.update = update; self.failure = failure
        analyzer = SpeechAnalyzer(modules: [transcriber])
        let pair = AsyncStream<AnalyzerInput>.makeStream(bufferingPolicy: .bufferingOldest(160))
        stream = pair.stream; continuation = pair.continuation
    }
    func prepare(format: AVAudioFormat) async throws {
        guard SpeechTranscriber.isAvailable,
              await SpeechTranscriber.installedLocales.contains(where: { $0.identifier.replacingOccurrences(of: "_", with: "-") == "ko-KR" }) else {
            throw NSError(domain: "LectureSpeech", code: 1, userInfo: [NSLocalizedDescriptionKey: "한국어 기기 내 인식 모델을 사용할 수 없어 음성만 녹음합니다."])
        }
        guard let target = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber], considering: format),
              let converter = AVAudioConverter(from: format, to: target) else {
            throw NSError(domain: "LectureSpeech", code: 2, userInfo: [NSLocalizedDescriptionKey: "오디오 형식을 인식기에 연결하지 못해 음성만 녹음합니다."])
        }
        targetFormat = target; self.converter = converter
        try await analyzer.prepareToAnalyze(in: target)
        resultsTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await result in transcriber.results {
                    let position = Int64((CMTimeGetSeconds(result.range.start) * 1000).rounded())
                    transcript.receive(position: position, text: String(result.text.characters), final: result.isFinal)
                    await update(transcript.text, transcript.provisional)
                }
            } catch {
                if !Task.isCancelled { await failure("받아쓰기가 중단되었어요. 음성 녹음은 계속합니다.") }
            }
        }
        try await analyzer.start(inputSequence: stream)
        started = true
    }
    func append(_ buffer: AVAudioPCMBuffer) {
        guard !failed, let converter, let targetFormat else { return }
        let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * targetFormat.sampleRate / buffer.format.sampleRate)) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, state in
            if supplied { state.pointee = .noDataNow; return nil }
            supplied = true; state.pointee = .haveData; return buffer
        }
        guard status != .error, error == nil else { fail(); return }
        if output.frameLength > 0 {
            if case .dropped = continuation.yield(AnalyzerInput(buffer: output)) { fail() }
        }
    }
    private func fail() {
        guard !failed else { return }; failed = true; continuation.finish()
        Task { @MainActor in failure("받아쓰기 처리가 지연되거나 중단되었어요. 원본 음성은 계속 저장합니다.") }
    }
    func finish() async {
        continuation.finish()
        guard started else { await analyzer.cancelAndFinishNow(); resultsTask?.cancel(); return }
        // Bound finalization so a stalled recognizer cannot keep the recording controls locked.
        let analyzer = analyzer
        let timeout = Task {
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            if !Task.isCancelled { await analyzer.cancelAndFinishNow() }
        }
        defer { timeout.cancel(); resultsTask?.cancel(); resultsTask = nil }
        do { try await analyzer.finalizeAndFinishThroughEndOfInput() }
        catch { await failure("받아쓰기 마무리를 완료하지 못했어요. 확정된 문장과 녹음은 보존했습니다.") }
        await resultsTask?.value
    }
}

@available(iOS 26.0, *)
final class LectureLiveCapture: LectureCaptureSession {
    private let engine = AVAudioEngine()
    private let queue = DispatchQueue(label: "StudyHelper.lecture.capture", qos: .userInitiated)
    private let slots = DispatchSemaphore(value: 32)
    private var file: AVAudioFile?
    private var speech: LectureSpeechSession?
    private var frames: AVAudioFramePosition = 0
    private var tapped = false
    private var accepting = true
    private let format: AVAudioFormat
    private let failed: @MainActor (String) -> Void
    var seconds: Double { queue.sync { Double(frames) / format.sampleRate } }

    init(url: URL, failed: @escaping @MainActor (String) -> Void) throws {
        self.failed = failed
        format = engine.inputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw NSError(domain: "LectureCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "사용 가능한 마이크 입력이 없어요."])
        }
        file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: format.sampleRate, AVNumberOfChannelsKey: format.channelCount, AVEncoderBitRateKey: 64000],
            commonFormat: format.commonFormat, interleaved: format.isInterleaved)
    }
    func prepareSpeech(update: @escaping @MainActor (String, String) -> Void, failure: @escaping @MainActor (String) -> Void) async {
        let session = LectureSpeechSession(update: update, failure: failure)
        do { try await session.prepare(format: format); speech = session }
        catch { await session.finish(); await failure(error.localizedDescription) }
    }
    func start() throws {
        engine.inputNode.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            guard let self else { return }
            guard slots.wait(timeout: .now()) == .success else {
                Task { @MainActor in self.failed("오디오 저장 처리가 지연되어 녹음을 종료했어요.") }; return
            }
            guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
                slots.signal()
                Task { @MainActor in self.failed("오디오 버퍼를 준비하지 못해 녹음을 종료했어요.") }; return
            }
            copy.frameLength = buffer.frameLength
            let source = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList)
            let destination = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
            for index in source.indices { memcpy(destination[index].mData!, source[index].mData!, Int(source[index].mDataByteSize)) }
            queue.async {
                defer { self.slots.signal() }
                guard self.accepting else { return }
                do {
                    try self.file?.write(from: copy)
                    self.frames += AVAudioFramePosition(copy.frameLength)
                    self.speech?.append(copy)
                } catch {
                    self.accepting = false
                    Task { @MainActor in self.failed("오디오 파일 저장 오류로 녹음을 종료했어요. 저장 공간을 확인해 주세요.") }
                }
            }
        }
        tapped = true
        engine.prepare()
        try engine.start()
    }
    func stopAudio() -> Double {
        engine.stop(); if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
        return queue.sync {
            accepting = false; file = nil // Flush AAC before the saved recording is shown.
            return Double(frames) / format.sampleRate
        }
    }
    func finishTranscription() async { await speech?.finish(); speech = nil }
}
