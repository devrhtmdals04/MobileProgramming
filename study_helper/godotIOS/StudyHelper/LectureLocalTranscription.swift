import Foundation
import Security

/// Only talks to the paired Mac on the local network. No third-party service.
final class LectureLocalTranscription: NSObject, URLSessionTaskDelegate {
    struct Pairing: Codable { let endpoint: String; let token: String }
    struct Result: Decodable { let text: String }
    private let service = "com.example.studyhelper.local-stt"
    private func error(_ message: String) -> NSError {
        NSError(domain: "LocalTranscription", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
    private func validate(_ pairing: Pairing) throws -> URL {
        guard let url = URL(string: pairing.endpoint), url.scheme == "http",
              let host = url.host, host.hasSuffix(".local"), url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, url.path.isEmpty || url.path == "/",
              pairing.token.count >= 32 else { throw error("올바르지 않은 Mac 연결 설정입니다.") }
        return url
    }
    func pairing() throws -> Pairing? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: "mac"]
        let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("local-stt-pairing.json")
        if FileManager.default.fileExists(atPath: file.path) {
            let data = try Data(contentsOf: file)
            let config = try JSONDecoder().decode(Pairing.self, from: data)
            _ = try validate(config)
            let update = [kSecValueData as String: data]
            var status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
            if status == errSecItemNotFound {
                var item = query
                item[kSecValueData as String] = data
                item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
                status = SecItemAdd(item as CFDictionary, nil)
            }
            guard status == errSecSuccess else { throw error("Mac 인증 정보를 저장하지 못했습니다.") }
            try FileManager.default.removeItem(at: file)
            return config
        }
        var lookup = query
        lookup[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw error("Mac 인증 정보를 읽지 못했습니다.") }
        let config = try JSONDecoder().decode(Pairing.self, from: data)
        _ = try validate(config)
        return config
    }
    func transcribe(file: URL) async throws -> String {
        guard let config = try pairing() else { throw error("먼저 Mac 서버와 연결해 주세요.") }
        let endpoint = try validate(config).appendingPathComponent("transcribe")
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0 && size <= 128 * 1024 * 1024 else { throw error("녹음 파일은 128MB 이내여야 합니다.") }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer " + config.token, forHTTPHeaderField: "Authorization")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 7500
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 7500
        configuration.timeoutIntervalForResource = 7500
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.upload(for: request, fromFile: file)
        guard let http = response as? HTTPURLResponse else { throw error("Mac 응답을 읽지 못했습니다.") }
        guard http.statusCode == 200 else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw error(message ?? "Mac 변환 오류 (\(http.statusCode))")
        }
        let result = try JSONDecoder().decode(Result.self, from: data)
        guard !result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw error("인식된 내용이 없습니다.") }
        return result.text
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
