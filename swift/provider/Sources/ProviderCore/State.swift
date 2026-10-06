// Installation state for one provider install, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"):
//
// - client.jwt: the scoped client credential that the developer's backend
//   issued for this installation. The developer writes it; the app rewrites it
//   whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run.
// - identity.json: the provider identity (client key seed, provide TLS
//   certificate and key, extender seed), created on first run so the provider
//   keeps one identity across restarts.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others; Windows
// relies on the access control of the user's profile directory.

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
public let identityFileName = "identity.json"

/// The largest state file the app reads.
let stateFileByteLimit = 64 * 1024

public let providerIdentityVersion = 1

/// A missing or invalid installation state: a configuration or credential
/// problem that restarting does not fix (exit code 78).
public struct ProviderConfigError: Error, CustomStringConvertible {
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

/// identity.json. Byte fields are standard base64 in JSON. An identity belongs
/// to one client: a newly provisioned client gets a new identity.
public struct ProviderIdentity: Codable, Equatable {
  public var version: Int
  public var clientId: String
  public var clientKeySeed: Data
  public var provideTlsCertificatePem: Data
  public var provideTlsPrivateKeyPem: Data
  /// empty when the device keeps none, and then left out of the file
  public var extenderKeySeed: Data

  /// The file's field names.
  enum CodingKeys: String, CodingKey {
    case version
    case clientId = "client_id"
    case clientKeySeed = "client_key_seed"
    case provideTlsCertificatePem = "provide_tls_certificate_pem"
    case provideTlsPrivateKeyPem = "provide_tls_private_key_pem"
    case extenderKeySeed = "extender_key_seed"
  }

  /// An identity with the given values.
  public init(
    version: Int, clientId: String, clientKeySeed: Data, provideTlsCertificatePem: Data,
    provideTlsPrivateKeyPem: Data, extenderKeySeed: Data
  ) {
    self.version = version
    self.clientId = clientId
    self.clientKeySeed = clientKeySeed
    self.provideTlsCertificatePem = provideTlsCertificatePem
    self.provideTlsPrivateKeyPem = provideTlsPrivateKeyPem
    self.extenderKeySeed = extenderKeySeed
  }

  /// Decodes the file; a missing or null field is empty, which loadProviderIdentity
  /// then checks.
  public init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    version = try container.decodeIfPresent(Int.self, forKey: .version) ?? 0
    clientId = try container.decodeIfPresent(String.self, forKey: .clientId) ?? ""
    clientKeySeed = try container.decodeIfPresent(Data.self, forKey: .clientKeySeed) ?? Data()
    provideTlsCertificatePem =
      try container.decodeIfPresent(Data.self, forKey: .provideTlsCertificatePem) ?? Data()
    provideTlsPrivateKeyPem =
      try container.decodeIfPresent(Data.self, forKey: .provideTlsPrivateKeyPem) ?? Data()
    extenderKeySeed = try container.decodeIfPresent(Data.self, forKey: .extenderKeySeed) ?? Data()
  }

  /// Encodes the file, leaving out an empty extender seed.
  public func encode(to encoder: Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(version, forKey: .version)
    try container.encode(clientId, forKey: .clientId)
    try container.encode(clientKeySeed, forKey: .clientKeySeed)
    try container.encode(provideTlsCertificatePem, forKey: .provideTlsCertificatePem)
    try container.encode(provideTlsPrivateKeyPem, forKey: .provideTlsPrivateKeyPem)
    if !extenderKeySeed.isEmpty {
      try container.encode(extenderKeySeed, forKey: .extenderKeySeed)
    }
  }
}

/// The byte values that recreate a device's identity: the arguments of
/// urnet_new_device_local_key_material and
/// urnet_device_local_key_material_set_extender_key_seed.
public struct ProviderKeyMaterial: Equatable {
  public var clientKeySeed: [UInt8]
  public var provideTlsCertificatePem: [UInt8]
  public var provideTlsPrivateKeyPem: [UInt8]
  /// empty when the identity has none
  public var extenderKeySeed: [UInt8]
}

/// The key material that recreates the identity. nil without an identity (the
/// first run, or another client's identity): the device then gets key material
/// handle 0 and makes a new identity, which the app saves.
public func providerKeyMaterial(_ identity: ProviderIdentity?) -> ProviderKeyMaterial? {
  guard let identity else {
    return nil
  }
  return ProviderKeyMaterial(
    clientKeySeed: [UInt8](identity.clientKeySeed),
    provideTlsCertificatePem: [UInt8](identity.provideTlsCertificatePem),
    provideTlsPrivateKeyPem: [UInt8](identity.provideTlsPrivateKeyPem),
    extenderKeySeed: [UInt8](identity.extenderKeySeed))
}

/// The installation state loaded at start.
public struct ProviderConfig {
  public let stateDir: String
  public let clientJwt: String
  /// the client_id claim of clientJwt
  public let clientId: String
  public let instanceId: String
  /// nil on first run, and when the stored identity belongs to another client
  public let identity: ProviderIdentity?
}

/// Loads the installation state, creating instance-id on first run. Every error
/// is a ProviderConfigError: restarting does not fix it.
public func loadProviderConfig(stateDir: String?) throws -> ProviderConfig {
  let stateDir = stateDir ?? ""
  try checkStateDir(stateDir)
  let clientJwtData: Data
  do {
    clientJwtData = try readPrivateFile(joinPath(stateDir, clientJwtFileName))
  } catch {
    throw ProviderConfigError("read \(clientJwtFileName) from the state directory: \(error)")
  }
  let clientJwt = String(decoding: clientJwtData, as: UTF8.self).trimmingCharacters(
    in: .whitespacesAndNewlines)
  let clientId = try parseClientJwtClientId(clientJwt)
  let instanceId = try loadOrCreateInstanceId(stateDir: stateDir)
  let identity = try loadProviderIdentity(stateDir: stateDir, clientId: clientId)
  return ProviderConfig(
    stateDir: stateDir, clientJwt: clientJwt, clientId: clientId, instanceId: instanceId,
    identity: identity)
}

/// The state directory must be an existing absolute directory, private to its
/// owner on POSIX.
public func checkStateDir(_ stateDir: String) throws {
  if stateDir.isEmpty {
    throw ProviderConfigError(
      "set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory")
  }
  if !isAbsolutePath(stateDir) {
    throw ProviderConfigError("URNETWORK_PROVIDER_STATE_DIR must be an absolute path")
  }
  var isDirectory: ObjCBool = false
  if !FileManager.default.fileExists(atPath: stateDir, isDirectory: &isDirectory) {
    throw ProviderConfigError("state directory: \(stateDir) does not exist")
  }
  if !isDirectory.boolValue {
    throw ProviderConfigError("URNETWORK_PROVIDER_STATE_DIR is not a directory")
  }
  #if !os(Windows)
    // the directory the path names, following a symlink as fileExists does
    var info = stat()
    if stat(stateDir, &info) != 0 {
      throw ProviderConfigError("state directory: \(stateDir) cannot be read")
    }
    if UInt32(info.st_mode) & 0o077 != 0 {
      throw ProviderConfigError("the state directory must be private to its owner (chmod 700)")
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
func readPrivateFile(_ path: String) throws -> Data {
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
  let notJwt = ProviderConfigError(
    "client.jwt does not hold a JWT; write the scoped client JWT from your backend")
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
    throw ProviderConfigError(
      "client.jwt has no client_id claim; write a scoped client JWT, not a network JWT")
  }
  guard let clientId = UUID(uuidString: clientIdText) else {
    throw ProviderConfigError("client.jwt has an invalid client_id claim")
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
      throw ProviderConfigError("\(instanceIdFileName) does not hold a UUID")
    }
    return instanceId.uuidString.lowercased()
  } catch let error as StateFileError where error.notFound {
    let instanceId = UUID().uuidString.lowercased()
    do {
      try writePrivateFile(path, Data((instanceId + "\n").utf8))
    } catch {
      throw ProviderConfigError("write \(instanceIdFileName): \(error)")
    }
    return instanceId
  } catch let error as StateFileError {
    throw ProviderConfigError("read \(instanceIdFileName) from the state directory: \(error)")
  }
}

/// Reads identity.json for clientId. A missing file, or an identity of another
/// client, returns nil: the device then creates a new identity, which the app
/// saves.
public func loadProviderIdentity(stateDir: String, clientId: String) throws -> ProviderIdentity? {
  let data: Data
  do {
    data = try readPrivateFile(joinPath(stateDir, identityFileName))
  } catch let error as StateFileError where error.notFound {
    return nil
  } catch {
    throw ProviderConfigError("read \(identityFileName) from the state directory: \(error)")
  }
  guard let identity = try? JSONDecoder().decode(ProviderIdentity.self, from: data),
    identity.version == providerIdentityVersion, identity.clientKeySeed.count == 32
  else {
    throw ProviderConfigError(
      "\(identityFileName) is not a valid provider identity; remove it to create a new one")
  }
  if identity.clientId != clientId {
    return nil
  }
  return identity
}

/// Writes identity.json.
public func saveProviderIdentity(stateDir: String, identity: ProviderIdentity) throws {
  let encoder = JSONEncoder()
  encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
  try writePrivateFile(joinPath(stateDir, identityFileName), try encoder.encode(identity))
}
