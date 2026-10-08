// Installation state for one embed installation, kept in one private
// directory named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md,
// "Installation state"):
//
// - client.jwt: the scoped client JWT, written by the token fetch, by the
//   backend tool's provision or by the developer, and rewritten by the app
//   whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run. It is also the
//   installation ID the app sends to the token server.
// - logs/: the SDK's bounded log files.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others, and a
// symlinked file is refused; Windows relies on the access control of the
// user's profile directory. The file functions are the Swift provider
// example's.

import Foundation

#if canImport(Darwin)
  import Darwin
#elseif canImport(Glibc)
  import Glibc
#elseif canImport(Musl)
  import Musl
#elseif canImport(CRT)
  import CRT
#endif
#if os(Windows)
  import WinSDK
#endif

public let clientJwtFileName = "client.jwt"
public let instanceIdFileName = "instance-id"

/// The largest state file the app reads.
let stateFileByteLimit = 64 * 1024

/// A missing or invalid installation state: a configuration or credential
/// problem that restarting does not fix (exit code 78).
public struct EmbedConfigError: Error, CustomStringConvertible {
  public let description: String

  /// An error with the given text.
  public init(_ description: String) {
    self.description = description
  }
}

/// A state file that could not be read or written.
public struct StateFileError: Error, CustomStringConvertible {
  public let description: String
  /// the file does not exist
  public let notFound: Bool

  /// An error with the given text.
  init(_ description: String, notFound: Bool = false) {
    self.description = description
    self.notFound = notFound
  }
}

/// The state directory must be an existing absolute directory, private to its
/// owner on POSIX.
public func checkStateDir(_ stateDir: String) throws {
  if stateDir.isEmpty {
    throw EmbedConfigError(
      "set URNETWORK_EMBED_STATE_DIR to this installation's private state directory")
  }
  if !isAbsolutePath(stateDir) {
    throw EmbedConfigError("URNETWORK_EMBED_STATE_DIR must be an absolute path")
  }
  var isDirectory: ObjCBool = false
  if !FileManager.default.fileExists(atPath: stateDir, isDirectory: &isDirectory) {
    throw EmbedConfigError("state directory: \(stateDir) does not exist")
  }
  if !isDirectory.boolValue {
    throw EmbedConfigError("URNETWORK_EMBED_STATE_DIR is not a directory")
  }
  #if !os(Windows)
    // the directory the path names, following a symlink as fileExists does
    var info = stat()
    if stat(stateDir, &info) != 0 {
      throw EmbedConfigError("state directory: \(stateDir) cannot be read")
    }
    if UInt32(info.st_mode) & 0o077 != 0 {
      throw EmbedConfigError("the state directory must be private to its owner (chmod 700)")
    }
  #endif
}

/// Whether path is absolute: from the root on POSIX; with a drive letter (C:\)
/// or a UNC prefix (\\server) on Windows.
func isAbsolutePath(_ path: String) -> Bool {
  #if os(Windows)
    if path.hasPrefix("\\\\") || path.hasPrefix("//") {
      return true
    }
    let characters = Array(path)
    return 3 <= characters.count && characters[0].isASCII && characters[0].isLetter
      && characters[1] == ":" && (characters[2] == "\\" || characters[2] == "/")
  #else
    return path.hasPrefix("/")
  #endif
}

/// name inside directory, with the platform's separator.
public func joinPath(_ directory: String, _ name: String) -> String {
  #if os(Windows)
    let separator: Character = "\\"
    let hasSeparator = directory.hasSuffix("\\") || directory.hasSuffix("/")
  #else
    let separator: Character = "/"
    let hasSeparator = directory.hasSuffix("/")
  #endif
  return hasSeparator ? directory + name : directory + String(separator) + name
}

/// Reads a regular, private state file of bounded size. A symlink is refused so
/// that the credential cannot be redirected to another file.
public func readPrivateFile(_ path: String) throws -> Data {
  let attributes: [FileAttributeKey: Any]
  do {
    // the file itself: a symlink is not followed
    attributes = try FileManager.default.attributesOfItem(atPath: path)
  } catch let error as CocoaError
    where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile
  {
    throw StateFileError("no such file", notFound: true)
  } catch {
    throw StateFileError("cannot read the file: \(error.localizedDescription)")
  }
  if attributes[.type] as? FileAttributeType != .typeRegular {
    throw StateFileError("not a regular file")
  }
  if let byteCount = (attributes[.size] as? NSNumber)?.int64Value,
    Int64(stateFileByteLimit) < byteCount
  {
    throw StateFileError("file is too large")
  }
  #if !os(Windows)
    let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0o777
    if permissions & 0o077 != 0 {
      throw StateFileError("file must be private to its owner (chmod 600)")
    }
  #endif
  guard let data = FileManager.default.contents(atPath: path) else {
    throw StateFileError("cannot read the file")
  }
  return data
}

/// Replaces a state file atomically: a private temporary file in the same
/// directory is written, synced and renamed over the old file. A failed write
/// removes its temporary file and leaves the old file as it was.
public func writePrivateFile(_ path: String, _ data: Data) throws {
  #if os(Windows)
    let separatorIndex = path.lastIndex(where: { $0 == "\\" || $0 == "/" })
  #else
    let separatorIndex = path.lastIndex(of: "/")
  #endif
  guard let separatorIndex else {
    throw StateFileError("not a path in a directory")
  }
  let directory = String(path[..<separatorIndex])
  let name = String(path[path.index(after: separatorIndex)...])
  #if os(Windows)
    // the user's profile directory keeps the file private to the user
    let tempPath = joinPath(directory, ".\(name).\(UUID().uuidString)")
    if !FileManager.default.createFile(atPath: tempPath, contents: nil) {
      throw StateFileError("cannot create a temporary file")
    }
    do {
      guard let handle = FileHandle(forWritingAtPath: tempPath) else {
        throw StateFileError("cannot open the temporary file")
      }
      defer { try? handle.close() }
      try handle.write(contentsOf: data)
      try handle.synchronize()
    } catch {
      try? FileManager.default.removeItem(atPath: tempPath)
      throw error
    }
    let moved = tempPath.withCString(encodedAs: UTF16.self) { tempPathW in
      path.withCString(encodedAs: UTF16.self) { pathW in
        MoveFileExW(tempPathW, pathW, DWORD(MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
      }
    }
    if !moved {
      try? FileManager.default.removeItem(atPath: tempPath)
      throw StateFileError("cannot replace the file (error \(GetLastError()))")
    }
  #else
    // mkstemp creates the file with mode 0600 and a unique name
    var template = Array(joinPath(directory, ".\(name).XXXXXX").utf8CString)
    let fd = template.withUnsafeMutableBufferPointer { mkstemp($0.baseAddress!) }
    if fd < 0 {
      throw StateFileError("cannot create a temporary file (errno \(errno))")
    }
    let tempPath = template.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
    let failure = { (text: String) -> StateFileError in
      let error = StateFileError("\(text) (errno \(errno))")
      close(fd)
      unlink(tempPath)
      return error
    }
    if fchmod(fd, 0o600) != 0 {
      throw failure("cannot make the temporary file private")
    }
    let written = data.withUnsafeBytes { buffer -> Bool in
      var offset = 0
      while offset < buffer.count {
        let count = write(fd, buffer.baseAddress! + offset, buffer.count - offset)
        if count < 0 {
          if errno == EINTR {
            continue
          }
          return false
        }
        offset += count
      }
      return true
    }
    if !written {
      throw failure("cannot write the temporary file")
    }
    if fsync(fd) != 0 {
      throw failure("cannot sync the temporary file")
    }
    if close(fd) != 0 {
      let error = StateFileError("cannot close the temporary file (errno \(errno))")
      unlink(tempPath)
      throw error
    }
    if rename(tempPath, path) != 0 {
      let error = StateFileError("cannot replace the file (errno \(errno))")
      unlink(tempPath)
      throw error
    }
  #endif
}

/// The client_id claim of a scoped client JWT, in canonical form. This checks
/// the token's shape and claim only; the SDK and the server verify the token
/// itself.
public func parseClientJwtClientId(_ clientJwt: String) throws -> String {
  let notJwt = EmbedConfigError(
    "client.jwt does not hold a JWT; obtain the scoped client JWT from your backend")
  let parts = clientJwt.split(separator: ".", omittingEmptySubsequences: false)
  if parts.count != 3 || parts.contains(where: { $0.isEmpty }) {
    throw notJwt
  }
  // the payload is base64url without padding
  var payloadText = String(parts[1])
  while payloadText.hasSuffix("=") {
    payloadText.removeLast()
  }
  let base64UrlCharacter = { (character: Character) -> Bool in
    character.isASCII
      && (character.isLetter || character.isNumber || character == "-" || character == "_")
  }
  if payloadText.isEmpty || !payloadText.allSatisfy(base64UrlCharacter) {
    throw notJwt
  }
  var base64 = payloadText.replacingOccurrences(of: "-", with: "+").replacingOccurrences(
    of: "_", with: "/")
  base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
  guard let payload = Data(base64Encoded: base64) else {
    throw notJwt
  }
  /// The one claim the app reads.
  struct Claims: Decodable {
    var clientId: String?

    /// The claim's name.
    enum CodingKeys: String, CodingKey {
      case clientId = "client_id"
    }
  }
  guard let claims = try? JSONDecoder().decode(Claims.self, from: payload),
    let clientIdText = claims.clientId, !clientIdText.isEmpty
  else {
    throw EmbedConfigError(
      "client.jwt has no client_id claim; write a scoped client JWT, not a network JWT")
  }
  guard let clientId = UUID(uuidString: clientIdText) else {
    throw EmbedConfigError("client.jwt has an invalid client_id claim")
  }
  return clientId.uuidString.lowercased()
}

/// Reads instance-id, creating it on first run. An installation keeps one
/// instance id for its lifetime.
public func loadOrCreateInstanceId(stateDir: String) throws -> String {
  let path = joinPath(stateDir, instanceIdFileName)
  do {
    let data = try readPrivateFile(path)
    let text = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    guard let instanceId = UUID(uuidString: text) else {
      throw EmbedConfigError("\(instanceIdFileName) does not hold a UUID")
    }
    return instanceId.uuidString.lowercased()
  } catch let error as StateFileError where error.notFound {
    let instanceId = UUID().uuidString.lowercased()
    do {
      try writePrivateFile(path, Data((instanceId + "\n").utf8))
    } catch {
      throw EmbedConfigError("write \(instanceIdFileName): \(error)")
    }
    return instanceId
  } catch let error as StateFileError {
    throw EmbedConfigError("read \(instanceIdFileName) from the state directory: \(error)")
  }
}

/// Reads client.jwt and its client_id claim: nil when the file is missing. A
/// file that cannot be read, or holds no client JWT (a network JWT has no
/// client_id claim), throws EmbedConfigError. The token itself is never printed.
public func loadClientJwt(stateDir: String) throws -> (clientJwt: String, clientId: String)? {
  let data: Data
  do {
    data = try readPrivateFile(joinPath(stateDir, clientJwtFileName))
  } catch let error as StateFileError where error.notFound {
    return nil
  } catch {
    throw EmbedConfigError("read \(clientJwtFileName) from the state directory: \(error)")
  }
  let clientJwt = String(decoding: data, as: UTF8.self).trimmingCharacters(
    in: .whitespacesAndNewlines)
  return (clientJwt, try parseClientJwtClientId(clientJwt))
}

/// Replaces client.jwt atomically with the token and a line break.
public func saveClientJwt(stateDir: String, clientJwt: String) throws {
  try writePrivateFile(joinPath(stateDir, clientJwtFileName), Data((clientJwt + "\n").utf8))
}
