import Foundation
import CryptoKit

final class SharedFolderFiles {
    let appRoot: URL
    private var key = ""
    private let fm = FileManager.default
    init(appRoot: URL) { self.appRoot = appRoot }
    private func fail(_ text: String) -> NSError { NSError(domain: "FolderSync", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
    private func root() throws -> URL {
        guard !key.isEmpty else { throw fail("먼저 공유 폴더를 선택해 주세요.") }
        return appRoot.appendingPathComponent("shared-folders").appendingPathComponent(key)
    }
    func resolve(_ path: String) throws -> URL {
        let base = try root()
        if path.isEmpty { return base }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        guard path.count <= 2000, parts.allSatisfy({ !$0.isEmpty && !$0.hasPrefix(".") && $0.rangeOfCharacter(from: CharacterSet(charactersIn: "\\:*?\"<>|").union(.controlCharacters)) == nil }) else { throw fail("올바르지 않은 파일 경로입니다.") }
        var target = base
        for part in parts {
            target.appendPathComponent(String(part))
            if (try? target.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true { throw fail("심볼릭 링크는 공유하지 않습니다.") }
        }
        guard target.resolvingSymlinksInPath().path.hasPrefix(base.resolvingSymlinksInPath().path + "/") else { throw fail("잘못된 경로입니다.") }
        return target
    }
    private func hash(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var hash = Insecure.MD5()
        while let data = try handle.read(upToCount: 65536), !data.isEmpty { hash.update(data: data) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    private func expected(_ url: URL, _ value: String) throws {
        let actual = fm.fileExists(atPath: url.path) ? try hash(url) : ""
        guard actual == value else { throw fail("파일이 다른 곳에서 변경됐어요: \(url.lastPathComponent)") }
    }
    private func backup(_ url: URL) throws {
        guard fm.fileExists(atPath: url.path) else { return }
        let target = appRoot.appendingPathComponent("folder-history/\(UUID().uuidString)/\(url.lastPathComponent)")
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.copyItem(at: url, to: target)
    }
    private func replace(_ temp: URL, _ target: URL, _ value: String) throws {
        try expected(target,value); try backup(target)
        if fm.fileExists(atPath: target.path) { _ = try fm.replaceItemAt(target, withItemAt: temp) }
        else { try fm.moveItem(at: temp, to: target) }
    }
    private func request(_ address: String, _ input: [String:String], _ method: String) throws -> URLRequest {
        guard let url = URL(string: address), url.host == "www.googleapis.com", url.scheme == "https" else { throw fail("잘못된 Drive 주소입니다.") }
        var r = URLRequest(url: url, timeoutInterval: 180); r.httpMethod = method
        r.setValue("Bearer \(input["token"] ?? "")", forHTTPHeaderField: "Authorization")
        if let etag=input["etag"], !etag.isEmpty { r.setValue(etag, forHTTPHeaderField: "If-Match") }
        return r
    }
    private func transfer(_ request: URLRequest, upload: URL? = nil) throws -> URL {
        let target = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let sem = DispatchSemaphore(value: 0)
        var result: Result<URL, Error> = .failure(fail("Drive 응답이 없습니다."))
        if let upload {
            URLSession.shared.uploadTask(with: request, fromFile: upload) { data,response,error in
                defer { sem.signal() }
                do {
                    if let error { throw error }
                    let status=(response as? HTTPURLResponse)?.statusCode ?? 0
                    guard (200...299).contains(status) else { throw self.fail("Drive 전송 실패 (HTTP \(status)). 다시 변경사항을 확인해 주세요.") }
                    try (data ?? Data()).write(to: target); result = .success(target)
                } catch { result = .failure(error) }
            }.resume()
        } else {
            URLSession.shared.downloadTask(with: request) { url,response,error in
                defer { sem.signal() }
                do {
                    if let error { throw error }
                    let status=(response as? HTTPURLResponse)?.statusCode ?? 0
                    guard (200...299).contains(status), let url else { throw self.fail("Drive 전송 실패 (HTTP \(status)). 다시 변경사항을 확인해 주세요.") }
                    try self.fm.moveItem(at: url,to: target); result = .success(target)
                } catch { result = .failure(error) }
            }.resume()
        }
        sem.wait() // Only invoked on a background queue; URLSession has its own callback queue.
        return try result.get()
    }
    func execute(_ input: [String:String]) throws -> [String:Any] {
        let action=input["action"] ?? "", path=input["path"] ?? ""
        if action == "prepare" {
            let value=input["key"] ?? ""
            guard !value.isEmpty, value.range(of: "^[A-Za-z0-9_-]+$",options: .regularExpression) != nil else { throw fail("잘못된 공유 폴더입니다.") }
            key=value; try fm.createDirectory(at: root(),withIntermediateDirectories: true)
        }
        if action == "status" || action == "prepare" { return ["desktop":false,"label":(try? root().path) ?? ""] }
        switch action {
        case "assert":
            try expected(resolve(path),input["expected"] ?? ""); return [:]
        case "scan":
            let base=try root(); var rows=[[String:Any]](); var seen=Set<String>()
            let extensions=Set(["md","json","pdf","png","jpg","jpeg","svg","gif","webp","m4a","wav","mp3","aac","flac","txt"])
            var scanError: Error?
            guard let iterator=fm.enumerator(at:base,includingPropertiesForKeys:[.isRegularFileKey,.isSymbolicLinkKey,.fileSizeKey],options:[.skipsHiddenFiles],errorHandler: { _,error in scanError=error; return false }) else { throw fail("공유 폴더를 읽지 못했어요.") }
            for case let url as URL in iterator {
                let attrs=try url.resourceValues(forKeys:[.isRegularFileKey,.isSymbolicLinkKey,.fileSizeKey])
                if attrs.isSymbolicLink == true { iterator.skipDescendants(); continue }
                guard attrs.isRegularFile == true, extensions.contains(url.pathExtension.lowercased()) else { continue }
                let rel=String(url.path.dropFirst(base.path.count+1)).precomposedStringWithCanonicalMapping
                _ = try resolve(rel)
                guard seen.insert(rel.lowercased()).inserted, rows.count<10000 else { throw fail("중복된 파일 이름이 있거나 파일이 너무 많습니다.") }
                rows.append(["path":rel,"hash":try hash(url),"size":attrs.fileSize ?? 0])
            }
            if let scanError { throw scanError }
            return ["files":rows]
        case "text":
            let url=try resolve(path)
            guard fm.fileExists(atPath:url.path) else { return ["text":"","hash":""] }
            guard (try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)<=400400 else { throw fail("노트가 너무 큽니다.") }
            return ["text":try String(contentsOf:url,encoding:.utf8),"hash":try hash(url)]
        case "writeText":
            let url=try resolve(path); try fm.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
            let temp=url.deletingLastPathComponent().appendingPathComponent(".sync-\(UUID().uuidString)")
            defer { try? fm.removeItem(at:temp) }
            try Data((input["text"] ?? "").utf8).write(to:temp)
            try replace(temp,url,input["expected"] ?? ""); return [:]
        case "archive":
            let url=try resolve(path); try expected(url,input["expected"] ?? ""); try backup(url)
            if fm.fileExists(atPath:url.path) { try fm.removeItem(at:url) }; return [:]
        case "archiveNote":
            let kind=input["kind"] ?? "", id=input["id"] ?? ""
            guard ["notes","question-sets"].contains(kind), UUID(uuidString:id) != nil else { throw fail("잘못된 노트입니다.") }
            let url=appRoot.appendingPathComponent("\(kind)/\(id).\(kind == "notes" ? "md" : "json")")
            try backup(url); if fm.fileExists(atPath:url.path) { try fm.removeItem(at:url) }; return [:]
        case "download":
            let id=input["id"] ?? ""; guard id.range(of:"^[A-Za-z0-9_-]+$",options:.regularExpression) != nil else { throw fail("잘못된 Drive 파일입니다.") }
            let url=try resolve(path); try expected(url,input["expected"] ?? "")
            // The metadata ETag is not a media validator; the checksum below guards content.
            var media=try request("https://www.googleapis.com/drive/v2/files/\(id)?alt=media",input,"GET")
            media.setValue(nil, forHTTPHeaderField: "If-Match")
            let temp=try transfer(media)
            defer { try? fm.removeItem(at:temp) }
            guard try hash(temp)==input["hash"] else { throw fail("다운로드한 파일의 내용이 변경됐어요.") }
            try fm.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
            try replace(temp,url,input["expected"] ?? ""); return [:]
        case "upload":
            let url=try resolve(path); try expected(url,input["expected"] ?? "")
            let id=input["id"] ?? ""
            guard id.isEmpty || id.range(of:"^[A-Za-z0-9_-]+$",options:.regularExpression) != nil else { throw fail("잘못된 Drive 파일입니다.") }
            let size=try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
            let address="https://www.googleapis.com/upload/drive/v2/files" + (id.isEmpty ? "" : "/"+id) + "?uploadType=resumable&fields=id,md5Checksum"
            var initial=try request(address,input,id.isEmpty ? "POST" : "PUT")
            initial.setValue("application/json; charset=UTF-8",forHTTPHeaderField:"Content-Type")
            initial.setValue("application/octet-stream",forHTTPHeaderField:"X-Upload-Content-Type")
            initial.setValue(String(size),forHTTPHeaderField:"X-Upload-Content-Length")
            initial.httpBody=Data((id.isEmpty ? input["metadata"] ?? "{}" : "{}").utf8)
            let sem=DispatchSemaphore(value:0)
            var location:Result<String,Error> = .failure(fail("Drive 업로드 세션이 없습니다."))
            URLSession.shared.dataTask(with:initial) { _,response,error in
                defer {sem.signal()}
                if let error {location = .failure(error);return}
                if let response=response as? HTTPURLResponse, (200...299).contains(response.statusCode), let address=response.value(forHTTPHeaderField:"Location") {
                    location = .success(address)
                } else {location = .failure(self.fail("Drive 업로드를 시작하지 못했어요. 다시 변경사항을 확인해 주세요."))}
            }.resume()
            sem.wait()
            var req=try request(location.get(),input,"PUT")
            req.setValue("application/octet-stream",forHTTPHeaderField:"Content-Type")
            req.setValue(String(size),forHTTPHeaderField:"Content-Length")
            let upload=url
            let response=try transfer(req,upload:upload); defer { try? fm.removeItem(at:response) }
            return try JSONSerialization.jsonObject(with:Data(contentsOf:response)) as? [String:Any] ?? [:]
        case "collectRecordings":
            let recordings=appRoot.appendingPathComponent("recordings"); var count=0
            for source in (try? fm.contentsOfDirectory(at:recordings,includingPropertiesForKeys:nil)) ?? [] where source.pathExtension == "m4a" {
                guard fm.fileExists(atPath:source.deletingPathExtension().appendingPathExtension("json").path) else { continue }
                let marker=appRoot.appendingPathComponent("recording-exports/\(key)/\(source.lastPathComponent).hash")
                let fingerprint=try hash(source)
                if (try? String(contentsOf:marker,encoding:.utf8)) == fingerprint { continue }
                let metadata=try? JSONSerialization.jsonObject(with:Data(contentsOf:source.deletingPathExtension().appendingPathExtension("json"))) as? [String:Any]
                let title=(metadata?["title"] as? String ?? "강의 녹음").components(separatedBy:CharacterSet(charactersIn:"\\/:*?\"<>|")).joined(separator:"_")
                let target=try resolve("녹음/\(String(title.prefix(60)))--\(source.lastPathComponent)")
                if fm.fileExists(atPath:target.path) {
                    guard try hash(target)==fingerprint else { throw fail("같은 이름의 녹음이 이미 있습니다.") }
                } else {
                    try fm.createDirectory(at:target.deletingLastPathComponent(),withIntermediateDirectories:true); try fm.copyItem(at:source,to:target)
                }
                try fm.createDirectory(at:marker.deletingLastPathComponent(),withIntermediateDirectories:true)
                try fingerprint.write(to:marker,atomically:true,encoding:.utf8); count+=1
            }
            return ["copied":count]
        default: throw fail("지원하지 않는 폴더 작업입니다.")
        }
    }
}
