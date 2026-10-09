// The status that every embed example shows, with the exact text rules of
// EMBED_CONTRACT.md ("Status"): the status, the data used this month and the
// running total. These are plain values and functions, so the self-test checks
// them without a network, credentials or the native SDK.

import Foundation

/// The status field's texts. GUI apps show the first two; a console app never
/// prints them.
public let statusSignedOut = "signed out"
public let statusStopped = "stopped"
public let statusClientLimit = "client limit"
public let statusPaused = "paused"
public let statusDataCapReached = "data cap reached"
public let statusConnected = "connected"
public let statusConnecting = "connecting"

/// The data fields' texts before and without a reading.
public let dataChecking = "checking"
public let dataUnavailable = "unavailable"

/// The server refuses the cap read with this message while the team has not
/// enabled Embed for the network (EMBED_CONTRACT.md, "Embed enablement"). The
/// refusal clears the last reading, so both data fields read unavailable.
public let embedNotEnabledMessage = "Embed isn't enabled for this network."
public let dataNoCap = "no cap"

/// The capped_reason values of the cap object.
public let cappedReasonMonthly = "monthly"
public let cappedReasonTotal = "total"

/// The SDK's client limit status values (URNET_CLIENT_LIMIT_STATUS_*): no hold,
/// or the platform disconnected this client for its network's concurrent client
/// limit and the SDK holds off reconnecting until the retry time. Kept here so
/// the status rules need no native SDK; the executable's self-test checks them
/// against the header.
public let clientLimitStatusNone = ""
public let clientLimitStatusExceeded = "client_limit_exceeded"

/// "embed client <client_id>, installation <instance_id>", printed at start.
public func startLine(clientId: String, instanceId: String) -> String {
  return "embed client \(clientId), installation \(instanceId)"
}

/// The GetLicenses app kind that --licenses prints: "apple" on Apple platforms,
/// "windows" on Windows, "linux" elsewhere.
#if os(Windows)
  public let licenseApp = "windows"
#elseif canImport(Darwin)
  public let licenseApp = "apple"
#else
  public let licenseApp = "linux"
#endif

/// Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "12.4 GB". Data
/// plans and the Embed plan's monthly data budget are sold in these units. A
/// value that rounds to 1000.0 moves to the next unit. The arithmetic is exact
/// integer arithmetic, so the text does not depend on float formatting: a tie
/// rounds to even on the exact value, so 1050 bytes is "1.0 kB".
public func formatByteCount(_ byteCount: Int64) -> String {
  let units = ["kB", "MB", "GB", "TB", "PB", "EB"]
  if byteCount < 1000 {
    return "\(byteCount) B"
  }
  let count = UInt64(byteCount)
  var divisor: UInt64 = 1000
  var unitIndex = 0
  while unitIndex + 1 < units.count {
    // the value rounds to 1000.0 or more from 999.95 up; at exactly 999.95 the
    // tie rounds to the even 1000.0
    let whole = count / divisor
    let remainder = count % divisor
    if whole < 999 || (whole == 999 && 20 * remainder < 19 * divisor) {
      break
    }
    divisor *= 1000
    unitIndex += 1
  }
  let scaled = count % divisor * 10
  var tenths = count / divisor * 10 + scaled / divisor
  let remainder = scaled % divisor
  if divisor < 2 * remainder || (divisor == 2 * remainder && tenths % 2 == 1) {
    tenths += 1
  }
  return "\(tenths / 10).\(tenths % 10) \(units[unitIndex])"
}

/// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's
/// days_from_civil).
func daysFromCivil(year: Int64, month: Int64, day: Int64) -> Int64 {
  let y = year - (month <= 2 ? 1 : 0)
  let era = (y >= 0 ? y : y - 399) / 400
  let yearOfEra = y - era * 400
  let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
  let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
  return era * 146097 + dayOfEra - 719468
}

/// The date of a day since 1970-01-01 (Howard Hinnant's civil_from_days).
func civilFromDays(_ days: Int64) -> (year: Int64, month: Int64, day: Int64) {
  let z = days + 719468
  let era = (z >= 0 ? z : z - 146096) / 146097
  let dayOfEra = z - era * 146097
  let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
  let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
  let monthIndex = (5 * dayOfYear + 2) / 153
  let day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
  let month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9
  return (yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month, day)
}

/// An RFC 3339 time as unix seconds in UTC, with whether its fraction is above
/// zero: "2026-11-01T00:00:00Z", or with a fraction and an offset such as
/// "2026-10-31T19:00:00.5-05:00".
func parseRfc3339(_ text: String) -> (unixSeconds: Int64, hasFraction: Bool)? {
  let bytes = Array(text.utf8)
  func digits(_ start: Int, _ count: Int) -> Int64? {
    if bytes.count < start + count {
      return nil
    }
    var value: Int64 = 0
    for byte in bytes[start..<start + count] {
      guard UInt8(ascii: "0") <= byte && byte <= UInt8(ascii: "9") else {
        return nil
      }
      value = value * 10 + Int64(byte - UInt8(ascii: "0"))
    }
    return value
  }
  guard bytes.count >= 20, bytes[4] == UInt8(ascii: "-"), bytes[7] == UInt8(ascii: "-"),
    bytes[10] == UInt8(ascii: "T") || bytes[10] == UInt8(ascii: "t"),
    bytes[13] == UInt8(ascii: ":"), bytes[16] == UInt8(ascii: ":"),
    let year = digits(0, 4), let month = digits(5, 2), let day = digits(8, 2),
    let hour = digits(11, 2), let minute = digits(14, 2), let second = digits(17, 2)
  else {
    return nil
  }
  let monthDays: [Int64] = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
  if month < 1 || 12 < month || day < 1 || monthDays[Int(month) - 1] < day
    || (month == 2 && day == 29 && !leap) || 23 < hour || 59 < minute || 59 < second
  {
    return nil
  }
  var index = 19
  var hasFraction = false
  if index < bytes.count && bytes[index] == UInt8(ascii: ".") {
    index += 1
    guard index < bytes.count, UInt8(ascii: "0") <= bytes[index], bytes[index] <= UInt8(ascii: "9")
    else {
      return nil
    }
    while index < bytes.count && UInt8(ascii: "0") <= bytes[index]
      && bytes[index] <= UInt8(ascii: "9")
    {
      if bytes[index] != UInt8(ascii: "0") {
        hasFraction = true
      }
      index += 1
    }
  }
  let rest = bytes[index...]
  var offsetSeconds: Int64 = 0
  if rest.count == 1 && (rest.first == UInt8(ascii: "Z") || rest.first == UInt8(ascii: "z")) {
    offsetSeconds = 0
  } else if rest.count == 6, rest.first == UInt8(ascii: "+") || rest.first == UInt8(ascii: "-"),
    bytes[index + 3] == UInt8(ascii: ":"), let offsetHour = digits(index + 1, 2),
    let offsetMinute = digits(index + 4, 2), offsetHour <= 23, offsetMinute <= 59
  {
    offsetSeconds = offsetHour * 3600 + offsetMinute * 60
    if rest.first == UInt8(ascii: "-") {
      offsetSeconds = -offsetSeconds
    }
  } else {
    return nil
  }
  let unixSeconds =
    daysFromCivil(year: year, month: month, day: day) * 86400 + hour * 3600 + minute * 60 + second
    - offsetSeconds
  return (unixSeconds, hasFraction)
}

/// Two digits, zero padded.
func twoDigits(_ value: Int64) -> String {
  return value < 10 ? "0\(value)" : "\(value)"
}

/// "resets YYYY-MM-DD HH:MM UTC" for the monthly cap's period end, in UTC with
/// the seconds rounded up to the next whole minute, so the shown time is never
/// before the real reset. nil when the time does not parse.
public func resetText(_ periodEnd: String) -> String? {
  guard let parsed = parseRfc3339(periodEnd) else {
    return nil
  }
  let (unixSeconds, hasFraction) = parsed
  var minutes = unixSeconds / 60 - (unixSeconds % 60 < 0 ? 1 : 0)
  if unixSeconds - minutes * 60 != 0 || hasFraction {
    minutes += 1
  }
  let days = minutes / 1440 - (minutes % 1440 < 0 ? 1 : 0)
  let minuteOfDay = minutes - days * 1440
  let (year, month, day) = civilFromDays(days)
  var yearText = "\(year)"
  while yearText.count < 4 {
    yearText = "0" + yearText
  }
  return
    "resets \(yearText)-\(twoDigits(month))-\(twoDigits(day)) \(twoDigits(minuteOfDay / 60)):\(twoDigits(minuteOfDay % 60)) UTC"
}

/// "client limit, retry at HH:MM UTC": the retry time (unix milliseconds)
/// rounded up to the next whole minute in UTC; a retry time of 0 shows "client
/// limit". Unix time has no leap seconds, so every day is 1440 minutes.
public func clientLimitText(retryTime: Int64) -> String {
  if retryTime <= 0 {
    return statusClientLimit
  }
  let minuteMillis: Int64 = 60 * 1000
  let retryMinute = retryTime / minuteMillis + (retryTime % minuteMillis != 0 ? 1 : 0)
  let minuteOfDay = retryMinute % (24 * 60)
  return "\(statusClientLimit), retry at \(twoDigits(minuteOfDay / 60)):\(twoDigits(minuteOfDay % 60)) UTC"
}

/// One cap object (EMBED_CONTRACT.md, "The cap object"), read. A nil limit is no
/// cap; an absent used count is 0.
public struct Cap: Equatable {
  public var monthlyByteLimit: Int64?
  public var monthlyUsedByteCount: Int64
  /// RFC 3339, as the server wrote it; empty when absent
  public var monthlyPeriodEnd: String
  public var totalByteLimit: Int64?
  public var totalUsedByteCount: Int64
  public var capped: Bool
  /// monthly, total, empty, or another value
  public var cappedReason: String

  /// A cap object with the given fields.
  public init(
    monthlyByteLimit: Int64? = nil, monthlyUsedByteCount: Int64 = 0, monthlyPeriodEnd: String = "",
    totalByteLimit: Int64? = nil, totalUsedByteCount: Int64 = 0, capped: Bool = false,
    cappedReason: String = ""
  ) {
    self.monthlyByteLimit = monthlyByteLimit
    self.monthlyUsedByteCount = monthlyUsedByteCount
    self.monthlyPeriodEnd = monthlyPeriodEnd
    self.totalByteLimit = totalByteLimit
    self.totalUsedByteCount = totalUsedByteCount
    self.capped = capped
    self.cappedReason = cappedReason
  }
}

/// A JSON value of any kind whose content is not read: decoding it only shows
/// that the member is present and not null.
struct PresentValue: Decodable {
  /// Accepts any value.
  init(from decoder: Decoder) throws {}
}

/// The cap object's JSON. A field of the wrong type fails to decode; null and
/// absent are the same.
struct CapObject: Decodable {
  var monthlyByteLimit: Int64?
  var monthlyUsedByteCount: Int64?
  var monthlyPeriodEnd: String?
  var totalByteLimit: Int64?
  var totalUsedByteCount: Int64?
  var capped: Bool?
  var cappedReason: String?
  var error: PresentValue?

  /// The API's field names.
  enum CodingKeys: String, CodingKey {
    case monthlyByteLimit = "monthly_byte_limit"
    case monthlyUsedByteCount = "monthly_used_byte_count"
    case monthlyPeriodEnd = "monthly_period_end"
    case totalByteLimit = "total_byte_limit"
    case totalUsedByteCount = "total_used_byte_count"
    case capped
    case cappedReason = "capped_reason"
    case error
  }

  /// The cap it holds; nil for an error answer.
  var cap: Cap? {
    if error != nil {
      return nil
    }
    return Cap(
      monthlyByteLimit: monthlyByteLimit, monthlyUsedByteCount: monthlyUsedByteCount ?? 0,
      monthlyPeriodEnd: monthlyPeriodEnd ?? "", totalByteLimit: totalByteLimit,
      totalUsedByteCount: totalUsedByteCount ?? 0, capped: capped ?? false,
      cappedReason: cappedReason ?? "")
  }
}

/// Reads a cap object. nil for anything else: not an object, an error answer,
/// or a field of the wrong type.
public func parseCap(_ data: Data) -> Cap? {
  guard let object = try? JSONDecoder().decode(CapObject.self, from: data) else {
    return nil
  }
  return object.cap
}

/// Whether an answer is the Embed-not-enabled refusal,
/// {"error": {"message": "Embed isn't enabled for this network."}}.
public func capNotEnabled(_ data: Data) -> Bool {
  struct Refusal: Decodable {
    struct Body: Decodable {
      var message: String?
    }
    var error: Body?
  }
  return (try? JSONDecoder().decode(Refusal.self, from: data))?.error?.message == embedNotEnabledMessage
}

/// What the data fields show: no reading yet, the first reading failed (or the
/// Embed-not-enabled refusal cleared the reading), or the latest reading.
public enum CapsState: Equatable {
  case checking
  case unavailable
  case read(Cap)
}

/// The cap readings. A reading replaces the last one; a failure before any
/// reading shows unavailable, and a later failure keeps the last reading.
public struct Caps: Equatable {
  public private(set) var state: CapsState = .checking

  /// No reading yet.
  public init() {}

  /// Applies one reading; nil is a failed reading.
  public mutating func apply(_ reading: Cap?) {
    if let reading {
      state = .read(reading)
    } else if state == .checking {
      state = .unavailable
    }
  }

  /// The Embed-not-enabled refusal clears the last reading: both data fields
  /// read unavailable, and the status rules see no cap reading.
  public mutating func clear() {
    state = .unavailable
  }

  /// Applies the outcome of a cap read: its reading, the Embed-not-enabled refusal, which clears
  /// the last reading, or another failure, which keeps it.
  public mutating func record(_ reading: Cap?, notEnabled: Bool) {
    if notEnabled {
      clear()
    } else {
      apply(reading)
    }
  }

  /// The latest reading, if any.
  public var cap: Cap? {
    if case .read(let cap) = state {
      return cap
    }
    return nil
  }
}

/// One data field: checking, unavailable, no cap for a nil limit (never a used
/// count), or "<used> of <limit>".
public func dataField(_ caps: Caps, monthly: Bool) -> String {
  switch caps.state {
  case .checking:
    return dataChecking
  case .unavailable:
    return dataUnavailable
  case .read(let cap):
    guard let limit = monthly ? cap.monthlyByteLimit : cap.totalByteLimit else {
      return dataNoCap
    }
    let used = monthly ? cap.monthlyUsedByteCount : cap.totalUsedByteCount
    return "\(formatByteCount(used)) of \(formatByteCount(limit))"
  }
}

/// The inputs of the status rules.
public struct StatusInput {
  /// GUI apps: before start or after stop; a console app is always started
  public var started = true
  /// GUI apps: after an auth logout or a token server refusal
  public var signedOut = false
  /// clientLimitStatusNone or clientLimitStatusExceeded
  public var clientLimitStatus = clientLimitStatusNone
  /// the end of the client limit hold in unix milliseconds, 0 without one
  public var clientLimitRetryTime: Int64 = 0
  public var caps = Caps()
  /// ProviderStateAdded of the window status, 0 before the window exists
  public var providersAdded: Int64 = 0

  /// The inputs with the given values.
  public init(
    started: Bool = true, signedOut: Bool = false, clientLimitStatus: String = clientLimitStatusNone,
    clientLimitRetryTime: Int64 = 0, caps: Caps = Caps(), providersAdded: Int64 = 0
  ) {
    self.started = started
    self.signedOut = signedOut
    self.clientLimitStatus = clientLimitStatus
    self.clientLimitRetryTime = clientLimitRetryTime
    self.caps = caps
    self.providersAdded = providersAdded
  }
}

/// The status: the first rule that applies, in the contract's order.
public func statusText(_ input: StatusInput) -> String {
  if input.signedOut {
    return statusSignedOut
  }
  if !input.started {
    return statusStopped
  }
  if input.clientLimitStatus == clientLimitStatusExceeded {
    return clientLimitText(retryTime: input.clientLimitRetryTime)
  }
  if let cap = input.caps.cap, cap.capped {
    // a cap of 0, the one capped_reason names, is a pause
    let paused =
      (cap.cappedReason == cappedReasonMonthly && cap.monthlyByteLimit == 0)
      || (cap.cappedReason == cappedReasonTotal && cap.totalByteLimit == 0)
    if paused {
      return statusPaused
    }
    if cap.cappedReason == cappedReasonMonthly, let reset = resetText(cap.monthlyPeriodEnd) {
      return "\(statusDataCapReached), \(reset)"
    }
    // a total cap, a monthly cap without a readable period end, or an unknown
    // reason
    return statusDataCapReached
  }
  return input.providersAdded >= 1 ? statusConnected : statusConnecting
}

/// The console status line, for example
/// "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap".
public func statusLine(_ input: StatusInput) -> String {
  return
    "status: \(statusText(input)) | data this month: \(dataField(input.caps, monthly: true)) | data total: \(dataField(input.caps, monthly: false))"
}
