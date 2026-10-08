// Obtaining the client JWT and reading the caps (EMBED_CONTRACT.md,
// "Obtaining the client JWT" and "App lifecycle"), over a replaceable HTTP
// function: the app passes urlSessionHttp and the self-test a stand-in server.
// fetchClientJwt is the one function to replace with your own sign-in: it posts
// this installation's instance-id to your backend and keeps the scoped client
// JWT that comes back. Nothing here prints a client JWT or the demo session.

import Foundation

#if canImport(FoundationNetworking)
  import FoundationNetworking
#endif

/// One HTTP request. authorization is the bearer token, sent as
/// "Authorization: Bearer <token>"; body, when set, is sent as application/json.
public struct HttpRequest: Equatable {
  public var method: String
  public var url: String
  public var authorization: String
  public var body: Data?

  /// A request with the given fields.
  public init(method: String, url: String, authorization: String, body: Data?) {
    self.method = method
    self.url = url
    self.authorization = authorization
    self.body = body
  }
}

/// One HTTP answer.
public struct HttpResponse: Equatable {
  public var status: Int
  public var body: Data

  /// An answer with the given status and body.
  public init(status: Int, body: Data) {
    self.status = status
    self.body = body
  }
}

/// No answer arrived: the server is unreachable, the answer is too large, or a
/// timeout.
public struct HttpError: Error, CustomStringConvertible {
  public let description: String

  /// An error with the given text.
  public init(_ description: String) {
    self.description = description
  }
}

/// Sends one request; throws HttpError when no answer arrived.
public typealias HttpFunction = (HttpRequest) throws -> HttpResponse

public let defaultApiUrl = "https://api.bringyour.com"
public let clientTokenPath = "/urnetwork/client-token"
public let clientDataCapPath = "/network/client-data-cap"

/// Whether text is a host name: letters, digits, dots and hyphens.
func isHostName(_ text: Substring) -> Bool {
  return !text.isEmpty && text.count <= 253
    && text.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-") }
}

/// Whether text is a bracketed IPv6 literal such as "[::1]".
func isIpv6Literal(_ text: Substring) -> Bool {
  guard text.count >= 3, text.first == "[", text.last == "]" else {
    return false
  }
  return text.dropFirst().dropLast().allSatisfy { $0.isHexDigit || $0 == ":" || $0 == "." }
}

/// Checks an origin, as the allocators do: an HTTPS origin, or explicit loopback
/// HTTP (localhost, 127.0.0.1, [::1]) for local testing, optionally with a
/// port, and no credentials, path beyond "/", query or fragment. Returns the
/// origin with path appended; nil for any other text.
public func originUrl(_ origin: String, path: String) -> String? {
  let https = origin.hasPrefix("https://")
  let http = origin.hasPrefix("http://")
  if !https && !http {
    return nil
  }
  let schemeLength = https ? 8 : 7
  let rest = origin.dropFirst(schemeLength)
  let authorityEnd = rest.firstIndex(where: { "/?#".contains($0) }) ?? rest.endIndex
  let authority = rest[..<authorityEnd]
  let after = rest[authorityEnd...]
  // nothing after the authority but one optional "/"
  if !after.isEmpty && after != "/" {
    return nil
  }
  if authority.contains("@") {
    return nil
  }
  // the host, then an optional ":port"
  var hostEnd = authority.endIndex
  if authority.first == "[" {
    guard let close = authority.firstIndex(of: "]") else {
      return nil
    }
    hostEnd = authority.index(after: close)
  } else if let colon = authority.firstIndex(of: ":") {
    hostEnd = colon
  }
  let host = authority[..<hostEnd]
  let port = authority[hostEnd...]
  if !port.isEmpty {
    let digits = port.dropFirst()
    guard port.first == ":", (1...5).contains(digits.count),
      digits.allSatisfy({ $0.isASCII && $0.isNumber }), let number = Int(digits),
      (1...65535).contains(number)
    else {
      return nil
    }
  }
  let loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
  if http && !loopback {
    return nil
  }
  if !isHostName(host) && !isIpv6Literal(host) {
    return nil
  }
  return String(origin.prefix(schemeLength + authority.count)) + path
}

/// The outcome of a token fetch, with its exit code and GUI state
/// (EMBED_CONTRACT.md, "Obtaining the client JWT").
public enum FetchResult: Equatable {
  case ok
  /// 401 unauthorized, 409 installation_limit or client_limit: exit 78, GUI
  /// signed out
  case refused
  /// unreachable, 5xx, or an invalid answer: exit 1, GUI stopped
  case failed
}

/// The token server's answer, checked.
public struct Token: Equatable {
  public var clientId: String
  /// never print it
  public var clientJwt: String
  public var dataCap: Cap?
}

/// A fetch's result, its token when ok, and the reason otherwise.
public struct FetchOutcome {
  public var result: FetchResult
  public var token: Token?
  public var error: String
}

/// The token server's answer JSON.
struct TokenAnswer: Decodable {
  var clientId: String
  var byClientJwt: String

  /// The token server's field names.
  enum CodingKeys: String, CodingKey {
    case clientId = "client_id"
    case byClientJwt = "by_client_jwt"
  }
}

/// A refusal's {"error": {"message": ...}}.
struct RefusalAnswer: Decodable {
  struct Body: Decodable {
    var message: String?
  }
  var error: Body?
}

/// Obtains this installation's client JWT from the token server: posts the
/// instance-id with the demo session as the bearer token to
/// POST /urnetwork/client-token, checks that by_client_jwt carries a client_id
/// claim equal to client_id, and saves it as client.jwt. A 401 or 409 is a
/// refusal; no answer, another status or an invalid answer is a failure. The
/// token server's data_cap, when present, is the first cap reading.
public func fetchClientJwt(
  http: HttpFunction, tokenServerUrl: String, demoSession: String, stateDir: String,
  instanceId: String
) -> FetchOutcome {
  func failed(_ error: String) -> FetchOutcome {
    return FetchOutcome(result: .failed, token: nil, error: error)
  }
  guard let url = originUrl(tokenServerUrl, path: clientTokenPath) else {
    return failed("the token server URL is not an HTTPS origin")
  }
  // the instance id is a canonical uuid, which needs no json escaping
  let body = Data(#"{"installation_id":"\#(instanceId)"}"#.utf8)
  let response: HttpResponse
  do {
    response = try http(
      HttpRequest(method: "POST", url: url, authorization: demoSession, body: body))
  } catch {
    return failed("the token server is unreachable: \(error)")
  }
  if response.status == 401 || response.status == 409 {
    let message =
      (try? JSONDecoder().decode(RefusalAnswer.self, from: response.body))?.error?.message
      ?? (response.status == 401 ? "unauthorized" : "conflict")
    return FetchOutcome(
      result: .refused, token: nil, error: "the token server refused this installation: \(message)")
  }
  if response.status != 200 {
    return failed("the token server answered HTTP \(response.status)")
  }
  guard let answer = try? JSONDecoder().decode(TokenAnswer.self, from: response.body),
    let answerClientId = UUID(uuidString: answer.clientId)?.uuidString.lowercased()
  else {
    return failed("the token server's answer is not valid")
  }
  let claimClientId: String
  do {
    claimClientId = try parseClientJwtClientId(answer.byClientJwt)
  } catch {
    return failed("the token server's answer is not valid: \(error)")
  }
  if claimClientId != answerClientId {
    return failed("the token server's client JWT belongs to another client")
  }
  do {
    try saveClientJwt(stateDir: stateDir, clientJwt: answer.byClientJwt)
  } catch {
    return failed("save \(clientJwtFileName): \(error)")
  }
  // data_cap: a cap object, or null when the token server could not read it
  var dataCap: Cap?
  if let object = try? JSONSerialization.jsonObject(with: response.body) as? [String: Any],
    let value = object["data_cap"], !(value is NSNull),
    let capData = try? JSONSerialization.data(withJSONObject: value)
  {
    dataCap = parseCap(capData)
  }
  return FetchOutcome(
    result: .ok,
    token: Token(clientId: answerClientId, clientJwt: answer.byClientJwt, dataCap: dataCap),
    error: "")
}

/// A failed cap read.
public struct CapReadError: Error, CustomStringConvertible {
  public let description: String
}

/// Reads this client's caps with its own client JWT:
/// GET /network/client-data-cap at the api origin. Throws CapReadError for no
/// answer, another status (a server without the cap routes answers 404) or an
/// answer that is not a cap object.
public func readCaps(http: HttpFunction, apiUrl: String, clientJwt: String) throws -> Cap {
  guard let url = originUrl(apiUrl, path: clientDataCapPath) else {
    throw CapReadError(description: "the API URL is not an HTTPS origin")
  }
  let response: HttpResponse
  do {
    response = try http(HttpRequest(method: "GET", url: url, authorization: clientJwt, body: nil))
  } catch {
    throw CapReadError(description: "\(error)")
  }
  if !(200..<300).contains(response.status) {
    throw CapReadError(description: "HTTP \(response.status)")
  }
  guard let cap = parseCap(response.body) else {
    throw CapReadError(description: "the answer is not a cap object")
  }
  return cap
}

/// The settings of a run, as the environment gives them; nil when unset.
public struct EmbedSettings {
  public var stateDir: String?
  public var tokenServerUrl: String?
  public var demoSession: String?
  public var apiUrl: String?

  /// The settings with the given values.
  public init(stateDir: String?, tokenServerUrl: String?, demoSession: String?, apiUrl: String?) {
    self.stateDir = stateDir
    self.tokenServerUrl = tokenServerUrl
    self.demoSession = demoSession
    self.apiUrl = apiUrl
  }

  /// The settings from the environment.
  public static func fromEnvironment() -> EmbedSettings {
    let environment = ProcessInfo.processInfo.environment
    return EmbedSettings(
      stateDir: environment["URNETWORK_EMBED_STATE_DIR"],
      tokenServerUrl: environment["URNETWORK_TOKEN_SERVER_URL"],
      demoSession: environment["URNETWORK_DEMO_SESSION"],
      apiUrl: environment["URNETWORK_API_URL"])
  }
}

/// The configuration of a run, loaded at start.
public struct EmbedConfig {
  public var stateDir: String
  /// the run loop replaces it when the SDK refreshes the token
  public var clientJwt: String
  public var clientId: String
  public var instanceId: String
  /// the cap read's origin, checked
  public var apiUrl: String
  /// the token server's data_cap, the first cap reading
  public var firstCap: Cap?
}

/// A configuration, or the exit code and the reason it did not load.
public enum EmbedConfigResult {
  case loaded(EmbedConfig)
  case failed(exitCode: Int32, error: String)
}

/// Whether the demo session can go in an Authorization header: printable ascii
/// without spaces.
func isBearerToken(_ text: String) -> Bool {
  return !text.isEmpty && text.utf8.allSatisfy { $0 > 0x20 && $0 <= 0x7e }
}

/// Loads the configuration of a run: checks the state directory and the URLs,
/// creates instance-id on first run, and obtains the client JWT from the token
/// server when one is configured, otherwise from client.jwt. A configuration or
/// credential problem is exit code 78, a token server failure 1.
public func loadEmbedConfig(settings: EmbedSettings, http: HttpFunction) -> EmbedConfigResult {
  func isSet(_ value: String?) -> Bool {
    return !(value ?? "").isEmpty
  }
  do {
    let stateDir = settings.stateDir ?? ""
    try checkStateDir(stateDir)
    guard
      let apiUrl = originUrl(isSet(settings.apiUrl) ? settings.apiUrl! : defaultApiUrl, path: "")
    else {
      throw EmbedConfigError(
        "URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for local testing")
    }
    let tokenServer = isSet(settings.tokenServerUrl)
    if tokenServer != isSet(settings.demoSession) {
      throw EmbedConfigError(
        "set both URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or neither")
    }
    var tokenServerUrl: String?
    if tokenServer {
      tokenServerUrl = originUrl(settings.tokenServerUrl!, path: "")
      if tokenServerUrl == nil {
        throw EmbedConfigError(
          "URNETWORK_TOKEN_SERVER_URL must be an HTTPS origin, or loopback HTTP for local testing")
      }
      if !isBearerToken(settings.demoSession!) {
        throw EmbedConfigError("URNETWORK_DEMO_SESSION must hold the demo session token")
      }
    }
    let instanceId = try loadOrCreateInstanceId(stateDir: stateDir)
    if let tokenServerUrl {
      let outcome = fetchClientJwt(
        http: http, tokenServerUrl: tokenServerUrl, demoSession: settings.demoSession!,
        stateDir: stateDir, instanceId: instanceId)
      guard outcome.result == .ok, let token = outcome.token else {
        return .failed(
          exitCode: outcome.result == .refused ? EmbedExitCode.config : EmbedExitCode.failure,
          error: outcome.error)
      }
      return .loaded(
        EmbedConfig(
          stateDir: stateDir, clientJwt: token.clientJwt, clientId: token.clientId,
          instanceId: instanceId, apiUrl: apiUrl, firstCap: token.dataCap))
    }
    guard let stored = try loadClientJwt(stateDir: stateDir) else {
      throw EmbedConfigError(
        "no token server and no \(clientJwtFileName): set URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or write \(clientJwtFileName) with your backend tool's provision"
      )
    }
    return .loaded(
      EmbedConfig(
        stateDir: stateDir, clientJwt: stored.clientJwt, clientId: stored.clientId,
        instanceId: instanceId, apiUrl: apiUrl, firstCap: nil))
  } catch let error as EmbedConfigError {
    return .failed(exitCode: EmbedExitCode.config, error: error.description)
  } catch {
    return .failed(exitCode: EmbedExitCode.failure, error: "\(error)")
  }
}

/// The largest answer the app reads.
let responseByteLimit = 1024 * 1024

/// Refuses redirects: the app talks to exactly the origin it was given.
final class NoRedirect: NSObject, URLSessionTaskDelegate {
  /// Answers every redirect with no new request.
  func urlSession(
    _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
  ) {
    completionHandler(nil)
  }
}

/// The app's HTTP function on URLSession: one request, no redirects, an
/// ephemeral session without cookies or caches, a bounded answer and a
/// timeout. It blocks the calling thread until the answer arrives.
public func urlSessionHttp(_ request: HttpRequest) throws -> HttpResponse {
  guard let url = URL(string: request.url) else {
    throw HttpError("invalid URL")
  }
  var urlRequest = URLRequest(url: url, timeoutInterval: 15)
  urlRequest.httpMethod = request.method
  urlRequest.setValue("Bearer " + request.authorization, forHTTPHeaderField: "Authorization")
  urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
  if let body = request.body {
    urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
    urlRequest.httpBody = body
  }
  let configuration = URLSessionConfiguration.ephemeral
  configuration.timeoutIntervalForRequest = 15
  configuration.timeoutIntervalForResource = 15
  let session = URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
  defer { session.finishTasksAndInvalidate() }
  let done = DispatchSemaphore(value: 0)
  /// The task's outcome, written once before done is signaled.
  final class Outcome: @unchecked Sendable {
    var data: Data?
    var response: URLResponse?
    var error: Error?
  }
  let outcome = Outcome()
  let task = session.dataTask(with: urlRequest) { data, response, error in
    outcome.data = data
    outcome.response = response
    outcome.error = error
    done.signal()
  }
  task.resume()
  done.wait()
  if let error = outcome.error {
    throw HttpError(error.localizedDescription)
  }
  guard let http = outcome.response as? HTTPURLResponse else {
    throw HttpError("no HTTP answer")
  }
  let data = outcome.data ?? Data()
  if responseByteLimit < data.count {
    throw HttpError("the answer is too large")
  }
  return HttpResponse(status: http.statusCode, body: data)
}
