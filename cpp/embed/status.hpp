// The status that every embed example shows, with the exact text rules of
// EMBED_CONTRACT.md ("Status"): the status, the data used this month and the
// running total. These are plain values and functions over nlohmann/json, so
// the self-test checks them without the sdk, a network or credentials.
#pragma once

#include <cstdint>
#include <cstdio>
#include <iostream>
#include <limits>
#include <optional>
#include <string>
#include <string_view>

#include <nlohmann/json.hpp>

namespace embed {

// The status field's texts. GUI apps show the first two; a console app never
// prints them.
inline constexpr const char* statusSignedOut = "signed out";
inline constexpr const char* statusStopped = "stopped";
inline constexpr const char* statusClientLimit = "client limit";
inline constexpr const char* statusPaused = "paused";
inline constexpr const char* statusDataCapReached = "data cap reached";
inline constexpr const char* statusConnected = "connected";
inline constexpr const char* statusConnecting = "connecting";

// The data fields' texts before and without a reading.
inline constexpr const char* dataChecking = "checking";
inline constexpr const char* dataUnavailable = "unavailable";
inline constexpr const char* dataNoCap = "no cap";

// The capped_reason values of the cap object.
inline constexpr const char* cappedReasonMonthly = "monthly";
inline constexpr const char* cappedReasonTotal = "total";

// The sdk's client limit status while the platform holds this client off
// (urnet::ClientLimitStatusExceeded); kept here so the status rules need no
// sdk, and checked against the header by the self-test.
inline constexpr const char* clientLimitStatusExceeded = "client_limit_exceeded";

// Prints one line on stdout at once, also when stdout is a file or a pipe.
inline void printLine(std::string_view line) {
    std::cout << line << std::endl;
}

// Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "12.4 GB". Data
// plans and the Embed plan's monthly data budget are sold in these units. A
// value that rounds to 1000.0 moves to the next unit. The arithmetic is exact
// integer arithmetic; a tie rounds to even, as Go's %.1f does.
inline std::string formatByteCount(int64_t byteCount) {
    static constexpr const char* units[] = {"kB", "MB", "GB", "TB", "PB", "EB"};
    if (byteCount < 1000) {
        return std::to_string(byteCount) + " B";
    }
    auto count = static_cast<uint64_t>(byteCount);
    uint64_t divisor = 1000;
    std::size_t unitIndex = 0;
    while (unitIndex + 1 < std::size(units)) {
        // the value rounds to 1000.0 or more from 999.95 up; at exactly
        // 999.95 the tie rounds to the even 1000.0
        uint64_t whole = count / divisor;
        uint64_t remainder = count % divisor;
        if (whole < 999 || (whole == 999 && 20 * remainder < 19 * divisor)) {
            break;
        }
        divisor *= 1000;
        unitIndex += 1;
    }
    uint64_t scaled = count % divisor * 10;
    uint64_t tenths = count / divisor * 10 + scaled / divisor;
    uint64_t remainder = scaled % divisor;
    if (divisor < 2 * remainder || (divisor == 2 * remainder && tenths % 2 == 1)) {
        tenths += 1;
    }
    return std::to_string(tenths / 10) + "." + std::to_string(tenths % 10) + " " + units[unitIndex];
}

namespace detail {

// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's
// days_from_civil).
inline int64_t daysFromCivil(int64_t year, int month, int day) {
    year -= month <= 2;
    int64_t era = (year >= 0 ? year : year - 399) / 400;
    int64_t yearOfEra = year - era * 400;
    int64_t dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
    int64_t dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
    return era * 146097 + dayOfEra - 719468;
}

// The date of a day since 1970-01-01 (Howard Hinnant's civil_from_days).
inline void civilFromDays(int64_t days, int64_t& year, int& month, int& day) {
    days += 719468;
    int64_t era = (days >= 0 ? days : days - 146096) / 146097;
    int64_t dayOfEra = days - era * 146097;
    int64_t yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365;
    int64_t dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
    int64_t monthIndex = (5 * dayOfYear + 2) / 153;
    day = static_cast<int>(dayOfYear - (153 * monthIndex + 2) / 5 + 1);
    month = static_cast<int>(monthIndex < 10 ? monthIndex + 3 : monthIndex - 9);
    year = yearOfEra + era * 400 + (month <= 2);
}

// Reads count decimal digits at the start of text.
inline std::optional<int> readDigits(std::string_view text, std::size_t count) {
    if (text.size() < count) {
        return std::nullopt;
    }
    int value = 0;
    for (std::size_t i = 0; i < count; i += 1) {
        if (text[i] < '0' || '9' < text[i]) {
            return std::nullopt;
        }
        value = value * 10 + (text[i] - '0');
    }
    return value;
}

// An RFC 3339 time as unix seconds in UTC, with whether its fraction is above
// zero: "2026-11-01T00:00:00Z", or with a fraction and an offset such as
// "2026-10-31T19:00:00.5-05:00".
inline std::optional<std::pair<int64_t, bool>> parseRfc3339(std::string_view text) {
    if (text.size() < 20 || text[4] != '-' || text[7] != '-' || (text[10] != 'T' && text[10] != 't') ||
        text[13] != ':' || text[16] != ':') {
        return std::nullopt;
    }
    auto year = readDigits(text, 4), month = readDigits(text.substr(5), 2), day = readDigits(text.substr(8), 2),
         hour = readDigits(text.substr(11), 2), minute = readDigits(text.substr(14), 2),
         second = readDigits(text.substr(17), 2);
    if (!year || !month || !day || !hour || !minute || !second) {
        return std::nullopt;
    }
    static constexpr int monthDays[] = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    bool leap = *year % 4 == 0 && (*year % 100 != 0 || *year % 400 == 0);
    if (*month < 1 || 12 < *month || *day < 1 || monthDays[*month - 1] < *day || (*month == 2 && *day == 29 && !leap) ||
        23 < *hour || 59 < *minute || 59 < *second) {
        return std::nullopt;
    }
    std::string_view rest = text.substr(19);
    bool hasFraction = false;
    if (!rest.empty() && rest.front() == '.') {
        rest.remove_prefix(1);
        if (rest.empty() || rest.front() < '0' || '9' < rest.front()) {
            return std::nullopt;
        }
        while (!rest.empty() && '0' <= rest.front() && rest.front() <= '9') {
            hasFraction = hasFraction || rest.front() != '0';
            rest.remove_prefix(1);
        }
    }
    int64_t offsetSeconds = 0;
    if (rest == "Z" || rest == "z") {
        offsetSeconds = 0;
    } else if (rest.size() == 6 && (rest[0] == '+' || rest[0] == '-') && rest[3] == ':') {
        auto offsetHour = readDigits(rest.substr(1), 2), offsetMinute = readDigits(rest.substr(4), 2);
        if (!offsetHour || !offsetMinute || 23 < *offsetHour || 59 < *offsetMinute) {
            return std::nullopt;
        }
        offsetSeconds = int64_t{*offsetHour} * 3600 + *offsetMinute * 60;
        if (rest[0] == '-') {
            offsetSeconds = -offsetSeconds;
        }
    } else {
        return std::nullopt;
    }
    int64_t unixSeconds = daysFromCivil(*year, *month, *day) * 86400 + int64_t{*hour} * 3600 + *minute * 60 + *second -
        offsetSeconds;
    return std::make_pair(unixSeconds, hasFraction);
}

// Two digits, zero padded.
inline std::string twoDigits(int value) {
    return (value < 10 ? "0" : "") + std::to_string(value);
}

}  // namespace detail

// "resets YYYY-MM-DD HH:MM UTC" for the monthly cap's period end, in UTC with
// the seconds rounded up to the next whole minute, so the shown time is never
// before the real reset. Empty when the time does not parse.
inline std::optional<std::string> resetText(std::string_view periodEnd) {
    auto parsed = detail::parseRfc3339(periodEnd);
    if (!parsed) {
        return std::nullopt;
    }
    auto [unixSeconds, hasFraction] = *parsed;
    int64_t minutes = unixSeconds / 60 - (unixSeconds % 60 < 0);
    if (unixSeconds - minutes * 60 != 0 || hasFraction) {
        minutes += 1;
    }
    int64_t days = minutes / 1440 - (minutes % 1440 < 0);
    int minuteOfDay = static_cast<int>(minutes - days * 1440);
    int64_t year = 0;
    int month = 0, day = 0;
    detail::civilFromDays(days, year, month, day);
    std::string yearText = std::to_string(year);
    while (yearText.size() < 4) {
        yearText = "0" + yearText;
    }
    return "resets " + yearText + "-" + detail::twoDigits(month) + "-" + detail::twoDigits(day) + " " +
        detail::twoDigits(minuteOfDay / 60) + ":" + detail::twoDigits(minuteOfDay % 60) + " UTC";
}

// "client limit, retry at HH:MM UTC": the retry time (unix milliseconds)
// rounded up to the next whole minute in UTC; a retry time of 0 shows "client
// limit". Unix time has no leap seconds, so every day is 1440 minutes.
inline std::string clientLimitText(int64_t retryTime) {
    if (retryTime <= 0) {
        return statusClientLimit;
    }
    constexpr int64_t minuteMillis = 60 * 1000;
    int64_t retryMinute = retryTime / minuteMillis + (retryTime % minuteMillis != 0);
    int minuteOfDay = static_cast<int>(retryMinute % (24 * 60));
    return std::string(statusClientLimit) + ", retry at " + detail::twoDigits(minuteOfDay / 60) + ":" +
        detail::twoDigits(minuteOfDay % 60) + " UTC";
}

// One cap object (EMBED_CONTRACT.md, "The cap object"), read. An empty limit
// is no cap; an absent used count is 0.
struct Cap {
    std::optional<int64_t> monthlyByteLimit;
    int64_t monthlyUsedByteCount = 0;
    // RFC 3339, as the server wrote it; empty when absent
    std::string monthlyPeriodEnd;
    std::optional<int64_t> totalByteLimit;
    int64_t totalUsedByteCount = 0;
    bool capped = false;
    // monthly, total, empty, or another value
    std::string cappedReason;
};

namespace detail {

// A json integer within int64; empty for any other value.
inline std::optional<int64_t> int64Of(const nlohmann::json& value) {
    if (value.is_number_unsigned()) {
        auto number = value.get<uint64_t>();
        if (static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) < number) {
            return std::nullopt;
        }
        return static_cast<int64_t>(number);
    }
    if (value.is_number_integer()) {
        return value.get<int64_t>();
    }
    return std::nullopt;
}

}  // namespace detail

// Reads a cap object. Empty for anything else: not an object, an error answer,
// or a field of the wrong type.
inline std::optional<Cap> parseCap(const nlohmann::json& value) {
    if (!value.is_object()) {
        return std::nullopt;
    }
    if (value.contains("error") && !value["error"].is_null()) {
        return std::nullopt;
    }
    Cap cap;
    // a limit: a number, or null or absent for no cap
    auto readLimit = [&](const char* name, std::optional<int64_t>& limit) {
        if (!value.contains(name) || value[name].is_null()) {
            return true;
        }
        limit = detail::int64Of(value[name]);
        return limit.has_value();
    };
    // a used count: a number, or 0 when null or absent
    auto readUsed = [&](const char* name, int64_t& used) {
        if (!value.contains(name) || value[name].is_null()) {
            return true;
        }
        auto number = detail::int64Of(value[name]);
        used = number.value_or(0);
        return number.has_value();
    };
    // a string: empty when null or absent
    auto readText = [&](const char* name, std::string& text) {
        if (!value.contains(name) || value[name].is_null()) {
            return true;
        }
        if (!value[name].is_string()) {
            return false;
        }
        text = value[name].get<std::string>();
        return true;
    };
    if (value.contains("capped") && !value["capped"].is_null()) {
        if (!value["capped"].is_boolean()) {
            return std::nullopt;
        }
        cap.capped = value["capped"].get<bool>();
    }
    if (!readLimit("monthly_byte_limit", cap.monthlyByteLimit) ||
        !readUsed("monthly_used_byte_count", cap.monthlyUsedByteCount) ||
        !readText("monthly_period_end", cap.monthlyPeriodEnd) ||
        !readLimit("total_byte_limit", cap.totalByteLimit) ||
        !readUsed("total_used_byte_count", cap.totalUsedByteCount) ||
        !readText("capped_reason", cap.cappedReason)) {
        return std::nullopt;
    }
    return cap;
}

// Reads a cap object from its json text.
inline std::optional<Cap> parseCapText(std::string_view text) {
    auto value = nlohmann::json::parse(text.begin(), text.end(), nullptr, false);
    if (value.is_discarded()) {
        return std::nullopt;
    }
    return parseCap(value);
}

// What the data fields show: no reading yet, the first reading failed, or the
// latest reading.
enum class CapsState { checking, unavailable, read };

// The cap readings. A reading replaces the last one; a failure before any
// reading shows unavailable, and a later failure keeps the last reading.
struct Caps {
    CapsState state = CapsState::checking;
    // the latest reading, when state is read
    Cap cap;

    // Applies one reading; empty is a failed reading.
    void apply(const std::optional<Cap>& reading) {
        if (reading) {
            state = CapsState::read;
            cap = *reading;
        } else if (state == CapsState::checking) {
            state = CapsState::unavailable;
        }
    }
};

// One data field: checking, unavailable, no cap for an empty limit (never a
// used count), or "<used> of <limit>".
inline std::string dataField(const Caps& caps, bool monthly) {
    if (caps.state == CapsState::checking) {
        return dataChecking;
    }
    if (caps.state == CapsState::unavailable) {
        return dataUnavailable;
    }
    const auto& limit = monthly ? caps.cap.monthlyByteLimit : caps.cap.totalByteLimit;
    if (!limit) {
        return dataNoCap;
    }
    int64_t used = monthly ? caps.cap.monthlyUsedByteCount : caps.cap.totalUsedByteCount;
    return formatByteCount(used) + " of " + formatByteCount(*limit);
}

// The inputs of the status rules.
struct StatusInput {
    // GUI apps: before start or after stop; a console app is always started
    bool started = true;
    // GUI apps: after an auth logout or a token server refusal
    bool signedOut = false;
    // "" or clientLimitStatusExceeded
    std::string clientLimitStatus;
    // the end of the client limit hold in unix milliseconds, 0 without one
    int64_t clientLimitRetryTime = 0;
    Caps caps;
    // ProviderStateAdded of the window status, 0 before the window exists
    int64_t providersAdded = 0;
};

// The status: the first rule that applies, in the contract's order.
inline std::string statusText(const StatusInput& input) {
    if (input.signedOut) {
        return statusSignedOut;
    }
    if (!input.started) {
        return statusStopped;
    }
    if (input.clientLimitStatus == clientLimitStatusExceeded) {
        return clientLimitText(input.clientLimitRetryTime);
    }
    if (input.caps.state == CapsState::read && input.caps.cap.capped) {
        const Cap& cap = input.caps.cap;
        // a cap of 0, the one capped_reason names, is a pause
        bool paused = (cap.cappedReason == cappedReasonMonthly && cap.monthlyByteLimit == int64_t{0}) ||
            (cap.cappedReason == cappedReasonTotal && cap.totalByteLimit == int64_t{0});
        if (paused) {
            return statusPaused;
        }
        if (cap.cappedReason == cappedReasonMonthly) {
            if (auto reset = resetText(cap.monthlyPeriodEnd)) {
                return std::string(statusDataCapReached) + ", " + *reset;
            }
        }
        // a total cap, a monthly cap without a readable period end, or an
        // unknown reason
        return statusDataCapReached;
    }
    return 1 <= input.providersAdded ? statusConnected : statusConnecting;
}

// The console status line, for example "status: connected | data this month:
// 1.2 GB of 5.0 GB | data total: no cap".
inline std::string statusLine(const StatusInput& input) {
    return "status: " + statusText(input) + " | data this month: " + dataField(input.caps, true) +
        " | data total: " + dataField(input.caps, false);
}

}  // namespace embed
