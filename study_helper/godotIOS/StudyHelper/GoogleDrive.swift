import Foundation
import UIKit
import AuthenticationServices
import CryptoKit
import Security

/// Installed-app PKCE; refresh credentials stay in the device Keychain, never UserDefaults.
final class GoogleDriveConnection: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var webSession: ASWebAuthenticationSession?
    private var token = ""
    private var expiry = Date.distantPast
    private let keychainService = "com.example.studyhelper.google-drive"
    var window: (() -> UIWindow?)?

    private var clientID: String { Bundle.main.object(forInfoDictionaryKey: "StudyGoogleClientID") as? String ?? "" }
    private func json(_ value: Any) -> String { String(data: try! JSONSerialization.data(withJSONObject: value), encoding: .utf8)! }
    private func error(_ text: String, _ completion: @escaping (String) -> Void) { completion(json(["error": text])) }
    private func form(_ values: [String: String]) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return values.map { $0.key.addingPercentEncoding(withAllowedCharacters: allowed)! + "=" + $0.value.addingPercentEncoding(withAllowedCharacters: allowed)! }.joined(separator: "&")
    }
    private func random() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return UUID().uuidString + UUID().uuidString }
        return base64(Data(bytes))
    }
    private func base64(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    private var keyQuery: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: keychainService, kSecAttrAccount as String: clientID] }
    private func refreshToken() -> String? {
        var query = keyQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &value) == errSecSuccess, let data = value as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    private func saveRefreshToken(_ token: String) throws {
        var query = keyQuery
        let data = Data(token.utf8)
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            query[kSecValueData as String] = data
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw NSError(domain: "Drive", code: 1, userInfo: [NSLocalizedDescriptionKey: "Google 인증을 안전하게 저장하지 못했어요."]) }
        } else if update != errSecSuccess { throw NSError(domain: "Drive", code: Int(update)) }
    }
    func disconnect() {
        webSession?.cancel(); webSession = nil; token = ""; expiry = .distantPast
        SecItemDelete(keyQuery as CFDictionary)
    }
    func authorize(_ completion: @escaping (String) -> Void) {
        guard !clientID.isEmpty else {
            error("Google OAuth 설정이 필요합니다. Google Cloud에서 iOS 클라이언트를 만들고 STUDY_GOOGLE_IOS_CLIENT_ID로 앱을 빌드해 주세요. docs/GOOGLE_DRIVE_SYNC.md를 참고하세요.", completion); return
        }
        if expiry > Date(), !token.isEmpty { completion(json(["accessToken": token])); return }
        if let refresh = refreshToken() {
            exchange(["client_id": clientID, "grant_type": "refresh_token", "refresh_token": refresh]) { [weak self] response in
                guard let self else { return }
                if response["access_token"] != nil { self.accept(response, completion) }
                else { SecItemDelete(self.keyQuery as CFDictionary); self.login(completion) }
            }
        } else { login(completion) }
    }
    private func login(_ completion: @escaping (String) -> Void) {
        guard webSession == nil else { error("Google 로그인이 진행 중이에요.", completion); return }
        let verifier = random(), state = random()
        let scheme = clientID.split(separator: ".").reversed().joined(separator: ".")
        let redirect = scheme + ":/oauthredirect"
        let challenge = base64(Data(SHA256.hash(data: Data(verifier.utf8))))
        let url = URL(string: "https://accounts.google.com/o/oauth2/v2/auth?" + form([
            "client_id": clientID, "redirect_uri": redirect, "response_type": "code",
            "scope": "https://www.googleapis.com/auth/drive", "code_challenge": challenge,
            "code_challenge_method": "S256", "state": state, "access_type": "offline", "prompt": "consent"
        ]))!
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme) { [weak self] callback, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.webSession = nil
                guard let callback, callback.scheme == scheme,
                      let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems,
                      items.first(where: { $0.name == "state" })?.value == state,
                      let code = items.first(where: { $0.name == "code" })?.value else {
                    self.error("Google 연결이 취소됐거나 인증 응답이 올바르지 않아요.", completion); return
                }
                self.exchange(["client_id": self.clientID, "code": code, "code_verifier": verifier,
                               "redirect_uri": redirect, "grant_type": "authorization_code"]) { self.accept($0, completion) }
            }
        }
        session.presentationContextProvider = self
        webSession = session
        if !session.start() { webSession = nil; error("Google 로그인 창을 열지 못했어요.", completion) }
    }
    private func exchange(_ fields: [String: String], _ completion: @escaping ([String: Any]) -> Void) {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!, timeoutInterval: 40)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(form(fields).utf8)
        URLSession.shared.dataTask(with: request) { data, response, _ in
            let value = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
            DispatchQueue.main.async { completion((response as? HTTPURLResponse)?.statusCode == 200 ? value : [:]) }
        }.resume()
    }
    private func accept(_ response: [String: Any], _ completion: @escaping (String) -> Void) {
        guard let token = response["access_token"] as? String else { error("Google 인증에 실패했어요. OAuth 설정과 테스트 사용자를 확인해 주세요.", completion); return }
        do {
            if let refresh = response["refresh_token"] as? String { try saveRefreshToken(refresh) }
            self.token = token
            expiry = Date().addingTimeInterval((response["expires_in"] as? Double ?? 3600) - 120)
            completion(json(["accessToken": token]))
        } catch { self.error(error.localizedDescription, completion) }
    }
    func request(_ text: String, _ completion: @escaping (String) -> Void) {
        guard let input = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let address = input["url"] as? String, let url = URL(string: address),
              url.scheme == "https", url.host == "www.googleapis.com" else { error("잘못된 Drive 요청입니다.", completion); return }
        var request = URLRequest(url: url, timeoutInterval: 45)
        request.httpMethod = input["method"] as? String
        for (key, value) in input["headers"] as? [String: String] ?? [:] { request.setValue(value, forHTTPHeaderField: key) }
        if let body = input["body"] as? String, !body.isEmpty { request.httpBody = Data(body.utf8) }
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self else { return }
                guard error == nil, let response = response as? HTTPURLResponse, let data else { self.error("네트워크 연결을 확인하고 다시 동기화해 주세요.", completion); return }
                guard data.count <= 4_000_000, let body = String(data: data, encoding: .utf8) else { self.error("응답이 너무 크거나 UTF-8 텍스트가 아닙니다.", completion); return }
                if response.statusCode == 401 { self.expiry = .distantPast }
                completion(self.json(["status": response.statusCode, "body": body]))
            }
        }.resume()
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        window?() ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? UIWindow()
    }
}
