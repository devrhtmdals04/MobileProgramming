// Compile appended to StudyHelper/FolderFiles.swift for a Foundation-only storage regression.
let fm = FileManager.default
let sandbox = fm.temporaryDirectory.appendingPathComponent("folder-path-test-" + UUID().uuidString)
let actual = sandbox.appendingPathComponent("physical-storage")
let alias = sandbox.appendingPathComponent("alias")
try fm.createDirectory(at: actual, withIntermediateDirectories: true)
try fm.createSymbolicLink(at: alias, withDestinationURL: actual)
defer { try? fm.removeItem(at: sandbox) }
let storage = SharedFolderFiles(appRoot: alias)
_ = try storage.execute(["action":"prepare", "key":"account_folder"])
let path = "과목/01주차/노트/수업.md"
let body = "# 수업\n\n본문이 사라지면 안 됩니다."
_ = try storage.execute(["action":"writeText", "path":path, "text":body, "expected":""])
let scan = try storage.execute(["action":"scan"])
let rows = scan["files"] as! [[String:Any]]
precondition(rows.count == 1)
precondition(rows[0]["path"] as! String == path, "Scan must return a real relative path through symlinked roots")
let text = try storage.execute(["action":"text", "path":rows[0]["path"] as! String])
precondition(text["text"] as! String == body)
precondition(text["hash"] as! String == rows[0]["hash"] as! String)
print("SWIFT SYMLINK PATH AND BODY ROUNDTRIP PASSED")
