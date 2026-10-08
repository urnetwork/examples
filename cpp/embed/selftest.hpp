// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks
// the status text and its rules, the data fields, the cap object, the client
// JWT claim, the token fetch and the cap read against a stand-in server, the
// installation state files and the configuration errors, without a network,
// credentials or a device, and without calling the sdk or libcurl:
// `embed --self-test` runs it in the app, and `make self-test` runs it in a
// binary that links neither library (selftest_main.cpp). The data is
// synthetic.
#pragma once

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>
#include <urnetwork_sdk.hpp>

#include "command.hpp"
#include "fetch.hpp"
#include "state.hpp"
#include "status.hpp"

#ifndef _WIN32
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace embed {

inline constexpr const char* clientId1 = "11111111-1111-1111-1111-111111111111";
inline constexpr const char* clientId2 = "22222222-2222-2222-2222-222222222222";
// jwt payloads (base64url): {"client_id":clientId1}, {"client_id":clientId2},
// a network JWT's {"network_id":...}, {"client_id":"not-a-uuid"} and an
// uppercase client id; the header and signature are placeholders
inline constexpr const char* clientJwt1 =
    "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ.sig";
inline constexpr const char* clientJwt2 =
    "e30.eyJjbGllbnRfaWQiOiIyMjIyMjIyMi0yMjIyLTIyMjItMjIyMi0yMjIyMjIyMjIyMjIifQ.sig";
inline constexpr const char* networkJwt =
    "e30.eyJuZXR3b3JrX2lkIjoiMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMzIn0.sig";
inline constexpr const char* invalidClientIdJwt = "e30.eyJjbGllbnRfaWQiOiJub3QtYS11dWlkIn0.sig";
inline constexpr const char* uppercaseClientIdJwt =
    "e30.eyJjbGllbnRfaWQiOiJBQUFBQUFBQS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ.sig";

inline constexpr const char* testTokenServerUrl = "http://127.0.0.1:8790";
inline constexpr const char* testDemoSession = "demo-session-0123456789abcdef0123456789";
inline constexpr const char* testApiUrl = "http://127.0.0.1:8791";

// A failed check.
class SelfTestError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Throws the message unless ok.
inline void expect(bool ok, const std::string& message) {
    if (!ok) {
        throw SelfTestError(message);
    }
}

// A server that gives its answers in order and keeps the requests; an empty
// answer is no answer at all, as when the server is unreachable.
struct StandIn {
    std::vector<std::optional<HttpResponse>> answers;
    std::size_t next = 0;
    std::vector<HttpRequest> requests;

    // The stand-in's HTTP function.
    HttpFunction http() {
        return [this](const HttpRequest& request, std::string& error) -> std::optional<HttpResponse> {
            requests.push_back(request);
            if (answers.size() <= next) {
                error = "the stand-in has no more answers";
                return std::nullopt;
            }
            auto answer = answers[next++];
            if (!answer) {
                error = "connection refused";
            }
            return answer;
        };
    }
};

// A new private temporary directory, removed when it goes out of scope.
struct TempDir {
    fs::path path;

    TempDir() {
#ifdef _WIN32
        path = fs::temp_directory_path() / ("ur-embed-self-test-" + hexString(randomBytes(8)));
        fs::create_directory(path);
#else
        std::string pattern = (fs::temp_directory_path() / "ur-embed-self-test-XXXXXX").string();
        // mkdtemp creates the directory with mode 0700
        if (!mkdtemp(pattern.data())) {
            throw SelfTestError("cannot make a temporary directory");
        }
        path = pattern;
#endif
    }

    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;

    ~TempDir() {
        std::error_code error;
        fs::remove_all(path, error);
    }
};

// Reads a whole file as text; empty when it cannot be read.
inline std::string readTextFile(const fs::path& path) {
    std::ifstream file(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
}

// Writes a whole file as text with the given POSIX mode.
inline void writeTextFile(const fs::path& path, const std::string& text, int mode) {
    {
        std::ofstream file(path, std::ios::binary | std::ios::trunc);
        file << text;
    }
#ifndef _WIN32
    chmod(path.c_str(), static_cast<mode_t>(mode));
#else
    (void)mode;
#endif
}

// A cap reading with the given caps; an empty limit is no cap.
inline Caps reading(std::optional<int64_t> monthlyLimit, int64_t monthlyUsed, std::optional<int64_t> totalLimit,
    int64_t totalUsed, bool capped, const std::string& reason, const std::string& periodEnd) {
    Cap cap;
    cap.monthlyByteLimit = monthlyLimit;
    cap.monthlyUsedByteCount = monthlyUsed;
    cap.totalByteLimit = totalLimit;
    cap.totalUsedByteCount = totalUsed;
    cap.capped = capped;
    cap.cappedReason = reason;
    cap.monthlyPeriodEnd = periodEnd;
    Caps caps;
    caps.apply(cap);
    return caps;
}

// Data amounts use decimal units with one decimal, ties to even.
inline void checkFormatByteCount() {
    const std::vector<std::pair<int64_t, std::string>> cases = {
        {0, "0 B"},
        {999, "999 B"},
        {1000, "1.0 kB"},
        {999949, "999.9 kB"},
        {1250, "1.2 kB"},
        {1750, "1.8 kB"},
        {999999, "1.0 MB"},
        {1234567890, "1.2 GB"},
        {5000000000, "5.0 GB"},
        {10000000000, "10.0 GB"},
        {3000000000000, "3.0 TB"},
        {std::numeric_limits<int64_t>::max(), "9.2 EB"},
    };
    for (const auto& [byteCount, text] : cases) {
        expect(formatByteCount(byteCount) == text,
            "byte count " + std::to_string(byteCount) + " shows \"" + formatByteCount(byteCount) + "\", want \"" +
                text + "\"");
    }
}

// The monthly reset time is in UTC, rounded up to the next minute.
inline void checkResetText() {
    const std::vector<std::pair<std::string, std::string>> cases = {
        {"2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"},
        {"2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"},
        {"2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"},
        {"2026-12-31T23:59:30Z", "resets 2027-01-01 00:00 UTC"},
        {"2028-02-29T12:00:00+01:00", "resets 2028-02-29 11:00 UTC"},
        {"2026-11-01T00:00:00.000Z", "resets 2026-11-01 00:00 UTC"},
    };
    for (const auto& [periodEnd, text] : cases) {
        expect(resetText(periodEnd) == text, "period end " + periodEnd + " shows another reset text");
    }
    for (const char* invalid : {"", "soon", "2026-11-01", "2026-11-01T00:00:00", "2026-13-01T00:00:00Z",
             "2027-02-29T00:00:00Z", "2026-11-01T24:00:00Z", "2026-11-01T00:00:00+0500", "2026-11-01T00:00:00.Z",
             "2026-11-01T00:00:00Zjunk"}) {
        expect(!resetText(invalid), std::string("period end \"") + invalid + "\" parsed");
    }
}

// The client limit text names the rounded-up retry time.
inline void checkClientLimitText() {
    expect(clientLimitText(1791313500000) == "client limit, retry at 19:05 UTC", "retry 1791313500000");
    expect(clientLimitText(1791313440001) == "client limit, retry at 19:05 UTC", "retry 1791313440001");
    expect(clientLimitText(0) == "client limit", "retry 0");
}

// The console status line matches the contract's golden lines.
inline void checkStatusLines() {
    Caps checking;
    Caps unavailable;
    unavailable.apply(std::nullopt);
    struct Case {
        std::string clientLimitStatus;
        int64_t retryTime;
        Caps caps;
        int64_t providersAdded;
        std::string line;
    };
    const std::vector<Case> cases = {
        {"", 0, checking, 0, "status: connecting | data this month: checking | data total: checking"},
        {"", 0, reading(5000000000, 1234567890, std::nullopt, 0, false, "", ""), 1,
            "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap"},
        {"", 0, unavailable, 1, "status: connected | data this month: unavailable | data total: unavailable"},
        {"", 0, reading(5000000000, 5000000000, std::nullopt, 0, true, "monthly", "2026-11-01T00:00:00Z"), 1,
            "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data "
            "total: no cap"},
        {"", 0, reading(std::nullopt, 0, 10000000000, 10000000000, true, "total", ""), 1,
            "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB"},
        {"", 0, reading(0, 0, std::nullopt, 0, true, "monthly", ""), 1,
            "status: paused | data this month: 0 B of 0 B | data total: no cap"},
        {clientLimitStatusExceeded, 1791313500000, reading(5000000000, 0, std::nullopt, 0, false, "", ""), 1,
            "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap"},
    };
    for (std::size_t i = 0; i < cases.size(); i += 1) {
        StatusInput input;
        input.clientLimitStatus = cases[i].clientLimitStatus;
        input.clientLimitRetryTime = cases[i].retryTime;
        input.caps = cases[i].caps;
        input.providersAdded = cases[i].providersAdded;
        std::string line = statusLine(input);
        expect(line == cases[i].line,
            "status line case " + std::to_string(i) + " is \"" + line + "\", want \"" + cases[i].line + "\"");
    }
}

// The status rules apply in the contract's order.
inline void checkStatusRules() {
    Caps checking;
    struct Case {
        bool started;
        bool signedOut;
        std::string clientLimitStatus;
        int64_t retryTime;
        Caps caps;
        int64_t providersAdded;
        std::string status;
    };
    const std::vector<Case> cases = {
        {false, false, "", 0, checking, 0, "stopped"},
        {false, true, "", 0, checking, 0, "signed out"},
        {true, false, clientLimitStatusExceeded, 1791313500000, reading(0, 0, std::nullopt, 0, true, "monthly", ""), 3,
            "client limit, retry at 19:05 UTC"},
        {true, false, "", 0, reading(0, 0, std::nullopt, 0, true, "monthly", ""), 3, "paused"},
        {true, false, "", 0, reading(5000000000, 0, 0, 0, true, "total", ""), 3, "paused"},
        {true, false, "", 0, reading(5000000000, 5000000000, std::nullopt, 0, true, "monthly", "2026-11-01T00:00:00Z"),
            3, "data cap reached, resets 2026-11-01 00:00 UTC"},
        {true, false, "", 0, reading(std::nullopt, 0, 10000000000, 10000000000, true, "total", ""), 0,
            "data cap reached"},
        {true, false, "", 0, reading(5000000000, 0, std::nullopt, 0, false, "", ""), 1, "connected"},
        {true, false, "", 0, checking, 0, "connecting"},
    };
    for (std::size_t i = 0; i < cases.size(); i += 1) {
        StatusInput input;
        input.started = cases[i].started;
        input.signedOut = cases[i].signedOut;
        input.clientLimitStatus = cases[i].clientLimitStatus;
        input.clientLimitRetryTime = cases[i].retryTime;
        input.caps = cases[i].caps;
        input.providersAdded = cases[i].providersAdded;
        std::string status = statusText(input);
        expect(status == cases[i].status,
            "status rule case " + std::to_string(i) + " is \"" + status + "\", want \"" + cases[i].status + "\"");
    }
}

// The data fields: checking, unavailable, a later failure keeping the last
// value, no cap for a null limit even with a used count, and used of limit.
inline void checkDataFields() {
    Caps caps;
    expect(dataField(caps, true) == "checking", "before a reading");
    caps.apply(std::nullopt);
    expect(dataField(caps, false) == "unavailable", "after a failed first reading");
    auto cap = parseCapText(
        R"({"monthly_byte_limit":null,"monthly_used_byte_count":500,"total_byte_limit":2000,"total_used_byte_count":1250})");
    expect(cap.has_value(), "a cap object did not parse");
    caps.apply(cap);
    expect(dataField(caps, true) == "no cap", "a null limit with a used count");
    expect(dataField(caps, false) == "1.2 kB of 2.0 kB", "used of limit");
    caps.apply(std::nullopt);
    expect(dataField(caps, false) == "1.2 kB of 2.0 kB", "a later failure");
}

// The cap object: null or absent limits, capped and its reason, an unknown
// reason read as capped without a reset time, and refused answers.
inline void checkCapObject() {
    auto full = parseCapText(std::string(R"({"client_id":")") + clientId1 +
        R"(","monthly_byte_limit":5000000000,"monthly_used_byte_count":1234567890,)"
        R"("monthly_period_start":"2026-10-01T00:00:00Z","monthly_period_end":"2026-11-01T00:00:00Z",)"
        R"("total_byte_limit":null,"total_used_byte_count":99,"total_period_start":"2026-09-15T00:00:00Z",)"
        R"("capped":false,"capped_reason":""})");
    expect(full && full->monthlyByteLimit == int64_t{5000000000} && full->monthlyUsedByteCount == 1234567890 &&
            full->monthlyPeriodEnd == "2026-11-01T00:00:00Z" && !full->totalByteLimit &&
            full->totalUsedByteCount == 99 && !full->capped && full->cappedReason.empty(),
        "the full cap object reads differently");
    auto absent = parseCapText(R"({"capped":false})");
    expect(absent && !absent->monthlyByteLimit && !absent->totalByteLimit && absent->monthlyUsedByteCount == 0,
        "absent limits read as caps");
    // an unknown reason is capped, without a reset time and without a pause
    auto unknown = parseCapText(
        R"({"monthly_byte_limit":0,"capped":true,"capped_reason":"weekly","monthly_period_end":"2026-11-01T00:00:00Z"})");
    expect(unknown && unknown->capped, "an unknown reason did not read as capped");
    StatusInput input;
    input.caps.apply(unknown);
    input.providersAdded = 1;
    expect(statusText(input) == "data cap reached", "an unknown reason shows " + statusText(input));
    // a monthly cap whose period end does not parse
    input.caps.apply(parseCapText(
        R"({"monthly_byte_limit":5,"monthly_used_byte_count":5,"monthly_period_end":"soon","capped":true,"capped_reason":"monthly"})"));
    expect(statusText(input) == "data cap reached", "an unreadable period end shows " + statusText(input));
    for (const char* refused : {R"({"error":{"message":"no"}})", R"({"monthly_byte_limit":"5"})",
             R"({"monthly_used_byte_count":1.5})", R"({"capped":"yes"})", R"({"capped_reason":7})",
             R"({"total_byte_limit":9223372036854775808})", "[]", "null", "not json"}) {
        expect(!parseCapText(refused), std::string(refused) + " read as a cap object");
    }
}

// Only a JWT with a valid client_id claim is a client credential.
inline void checkClientJwtClaims() {
    expect(parseClientJwtClientId(clientJwt1) == clientId1, "a client JWT was refused");
    expect(parseClientJwtClientId(uppercaseClientIdJwt) == "aaaaaaaa-1111-1111-1111-111111111111",
        "an uppercase client id is not canonical");
    for (const char* refused : {networkJwt, invalidClientIdJwt, "", "not-a-jwt", "a..b", "a.b.c.d", "e30.!!!.sig",
             "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ."}) {
        bool accepted = true;
        try {
            parseClientJwtClientId(refused);
        } catch (const ConfigError&) {
            accepted = false;
        }
        expect(!accepted, std::string("JWT \"") + refused + "\" was accepted");
    }
}

// Origins follow the allocators' rules.
inline void checkOriginUrl() {
    const std::vector<std::pair<std::string, std::string>> accepted = {
        {"https://api.bringyour.com", "https://api.bringyour.com/x"},
        {"https://api.bringyour.com/", "https://api.bringyour.com/x"},
        {"https://example.com:8443", "https://example.com:8443/x"},
        {"http://localhost:8790", "http://localhost:8790/x"},
        {"http://127.0.0.1", "http://127.0.0.1/x"},
        {"http://[::1]:8790", "http://[::1]:8790/x"},
        {"https://[2001:db8::1]", "https://[2001:db8::1]/x"},
    };
    for (const auto& [origin, url] : accepted) {
        expect(originUrl(origin, "/x") == url, "origin " + origin + " was refused or changed");
    }
    for (const char* refused : {"http://example.com", "https://example.com/path", "https://user@example.com",
             "https://example.com?x=1", "https://example.com#f", "ftp://example.com", "https://",
             "https://exa mple.com", "https://example.com:0", "https://example.com:99999", "https://example.com:",
             "localhost:8790", "http://127.0.0.2", "http://[::2]"}) {
        expect(!originUrl(refused, "/x"), std::string("origin ") + refused + " was accepted");
    }
}

// The settings for a temporary state directory with the token server.
inline Settings tokenSettings(const fs::path& stateDir) {
    return Settings{stateDir.string(), std::string(testTokenServerUrl), std::string(testDemoSession),
        std::string(testApiUrl)};
}

// The token fetch against a stand-in server: the request, saving client.jwt,
// a mismatched client refused, and the answers mapped to exit codes.
inline void checkTokenFetch() {
    TempDir dir;
    Settings settings = tokenSettings(dir.path);
    Config config;
    std::string error;
    StandIn server;
    server.answers.push_back(HttpResponse{200,
        std::string(R"({"client_id":")") + clientId1 + R"(","by_client_jwt":")" + clientJwt1 +
            R"(","data_cap":{"monthly_byte_limit":5000000000,"monthly_used_byte_count":1234567890,"capped":false}})"});
    int code = loadConfig(settings, server.http(), config, error);
    expect(code == 0, "a token answer gave exit " + std::to_string(code) + ": " + error);
    expect(server.requests.size() == 1, "the token server got another number of requests");
    const HttpRequest& request = server.requests[0];
    auto body = nlohmann::json::parse(request.body.value_or(""), nullptr, false);
    std::string instanceId(trimSpace(readTextFile(dir.path / instanceIdFileName)));
    expect(request.method == "POST" && request.url == std::string(testTokenServerUrl) + "/urnetwork/client-token" &&
            request.authorization == testDemoSession && body.is_object() && body.size() == 1 &&
            body.value("installation_id", "") == instanceId && instanceId == config.instanceId,
        "the token request is not the contract's");
    expect(readTextFile(dir.path / clientJwtFileName) == std::string(clientJwt1) + "\n" &&
            config.clientId == clientId1 && config.clientJwt == clientJwt1 && config.firstCap &&
            config.firstCap->monthlyByteLimit == int64_t{5000000000},
        "the token answer was not saved or kept");
#ifndef _WIN32
    struct stat info;
    expect(stat((dir.path / clientJwtFileName).c_str(), &info) == 0 && !(info.st_mode & 077),
        "client.jwt is not private");
    std::size_t entries = 0;
    for ([[maybe_unused]] const auto& entry : fs::directory_iterator(dir.path)) {
        entries += 1;
    }
    expect(entries == 2, "the state directory has leftover files");
#endif
    // answers that are refusals (78) or failures (1); client.jwt stays
    struct Answer {
        std::optional<HttpResponse> answer;
        int code;
    };
    const std::vector<Answer> answers = {
        {HttpResponse{200, std::string(R"({"client_id":")") + clientId2 + R"(","by_client_jwt":")" + clientJwt1 +
                R"(","data_cap":null})"},
            exitFailure},
        {HttpResponse{200, std::string(R"({"client_id":")") + clientId1 + R"(","by_client_jwt":")" + networkJwt + R"("})"},
            exitFailure},
        {HttpResponse{200, "not json"}, exitFailure},
        {HttpResponse{401, R"({"error":{"code":"unauthorized","message":"unknown demo session"}})"}, exitConfig},
        {HttpResponse{409, R"({"error":{"code":"installation_limit","message":"this user has 5 installations"}})"},
            exitConfig},
        {HttpResponse{409,
             R"({"error":{"code":"client_limit","message":"the network is at its client limit; see https://ur.io/services"}})"},
            exitConfig},
        {HttpResponse{503, R"({"error":{"code":"busy","message":"retry"}})"}, exitFailure},
        {HttpResponse{500, ""}, exitFailure},
        {HttpResponse{400, R"({"error":{"code":"invalid_request"}})"}, exitFailure},
        {std::nullopt, exitFailure},
    };
    for (std::size_t i = 0; i < answers.size(); i += 1) {
        StandIn refusing;
        refusing.answers.push_back(answers[i].answer);
        code = loadConfig(settings, refusing.http(), config, error);
        expect(code == answers[i].code,
            "token answer case " + std::to_string(i) + " gave exit " + std::to_string(code) + ", want " +
                std::to_string(answers[i].code));
        if (i == 3) {
            expect(error.find("unknown demo session") != std::string::npos, "a refusal does not show its message");
        }
        expect(readTextFile(dir.path / clientJwtFileName) == std::string(clientJwt1) + "\n",
            "token answer case " + std::to_string(i) + " replaced client.jwt");
    }
    // a null data_cap is no first reading
    StandIn noCap;
    noCap.answers.push_back(HttpResponse{200,
        std::string(R"({"client_id":")") + clientId1 + R"(","by_client_jwt":")" + clientJwt1 + R"(","data_cap":null})"});
    expect(loadConfig(settings, noCap.http(), config, error) == 0 && !config.firstCap,
        "a null data_cap gave a first reading");
}

// The cap read: the client JWT as the bearer, and failed readings.
inline void checkCapRead() {
    std::string error;
    StandIn server;
    server.answers.push_back(HttpResponse{200,
        std::string(R"({"client_id":")") + clientId1 +
            R"(","monthly_byte_limit":0,"monthly_used_byte_count":0,"capped":true,"capped_reason":"monthly"})"});
    auto cap = readCaps(server.http(), testApiUrl, clientJwt1, error);
    expect(cap && cap->capped && cap->cappedReason == "monthly", "a cap answer was not read: " + error);
    const HttpRequest& request = server.requests[0];
    expect(request.method == "GET" && request.url == std::string(testApiUrl) + "/network/client-data-cap" &&
            request.authorization == clientJwt1 && !request.body,
        "the cap request is not the contract's");
    const std::vector<std::optional<HttpResponse>> failed = {
        HttpResponse{404, "404 page not found"},
        HttpResponse{200, R"({"error":{"message":"no such client"}})"},
        HttpResponse{401, ""},
        std::nullopt,
    };
    for (std::size_t i = 0; i < failed.size(); i += 1) {
        StandIn failing;
        failing.answers.push_back(failed[i]);
        expect(!readCaps(failing.http(), testApiUrl, clientJwt1, error),
            "failed cap answer " + std::to_string(i) + " was read");
    }
}

// State files are private, atomic and created once; a symlink is refused.
inline void checkStateFiles() {
    TempDir dir;
    checkStateDir(dir.path);
    fs::path path = dir.path / "file";
    writePrivateFile(path, "one");
    writePrivateFile(path, "two");
    expect(readPrivateFile(path) == std::string("two"), "a state file was not replaced");
    std::string first = loadOrCreateInstanceId(dir.path);
    expect(loadOrCreateInstanceId(dir.path) == first, "instance-id was not created once and reused");
#ifndef _WIN32
    struct stat info;
    expect(stat(path.c_str(), &info) == 0 && !(info.st_mode & 077), "a state file is not private");
    std::size_t entries = 0;
    for ([[maybe_unused]] const auto& entry : fs::directory_iterator(dir.path)) {
        entries += 1;
    }
    expect(entries == 2, "a replacement left a temporary file");
    writeTextFile(dir.path / "shared", "x", 0644);
    bool sharedRead = true;
    try {
        readPrivateFile(dir.path / "shared");
    } catch (const std::exception&) {
        sharedRead = false;
    }
    expect(!sharedRead, "a file open to others was read");
    fs::create_symlink(path, dir.path / "link");
    bool linkRead = true;
    try {
        readPrivateFile(dir.path / "link");
    } catch (const std::exception&) {
        linkRead = false;
    }
    expect(!linkRead, "a symlinked state file was read");
    fs::permissions(dir.path, fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
            fs::perms::others_read | fs::perms::others_exec);
    bool sharedDirAccepted = true;
    try {
        checkStateDir(dir.path);
    } catch (const ConfigError&) {
        sharedDirAccepted = false;
    }
    fs::permissions(dir.path, fs::perms::owner_all);
    expect(!sharedDirAccepted, "a state directory open to others was accepted");
#endif
    expect(!readPrivateFile(dir.path / "missing"), "a missing file did not read as missing");
}

// A missing or relative state directory, no token server and no client.jwt,
// a network JWT and invalid settings are configuration errors (78).
inline void checkConfig() {
    TempDir dir;
    std::string error;
    Config config;
    StandIn none;
    std::string stateDir = dir.path.string();
    const std::vector<Settings> refused = {
        {std::nullopt, std::nullopt, std::nullopt, std::nullopt},
        {std::string(""), std::nullopt, std::nullopt, std::nullopt},
        {std::string("relative/state"), std::nullopt, std::nullopt, std::nullopt},
        // no token server and no client.jwt
        {stateDir, std::nullopt, std::nullopt, std::nullopt},
        {stateDir, std::string(testTokenServerUrl), std::nullopt, std::nullopt},
        {stateDir, std::nullopt, std::string(testDemoSession), std::nullopt},
        {stateDir, std::string("http://example.com"), std::string(testDemoSession), std::nullopt},
        {stateDir, std::string(testTokenServerUrl), std::string("has space"), std::nullopt},
        {stateDir, std::nullopt, std::nullopt, std::string("https://example.com/path")},
    };
    for (std::size_t i = 0; i < refused.size(); i += 1) {
        int code = loadConfig(refused[i], none.http(), config, error);
        expect(code == exitConfig,
            "configuration case " + std::to_string(i) + " gave exit " + std::to_string(code) + ", want 78");
    }
    expect(none.requests.empty(), "a configuration error reached the token server");
    Settings fromFile{stateDir, std::nullopt, std::nullopt, std::nullopt};
    writeTextFile(dir.path / clientJwtFileName, std::string(networkJwt) + "\n", 0600);
    expect(loadConfig(fromFile, none.http(), config, error) == exitConfig, "a network JWT in client.jwt was accepted");
    writeTextFile(dir.path / clientJwtFileName, std::string("  ") + clientJwt1 + "\n\n", 0600);
    expect(loadConfig(fromFile, none.http(), config, error) == 0 && config.clientId == clientId1 &&
            config.clientJwt == clientJwt1 && config.apiUrl == defaultApiUrl && !config.firstCap,
        "client.jwt from the backend tool did not load");
}

// The status rules' client limit value equals the sdk header's.
inline void checkSdkValues() {
    expect(std::string(clientLimitStatusExceeded) == urnet::ClientLimitStatusExceeded,
        "the client limit status differs from urnetwork_sdk.hpp");
}

// Unknown arguments are a usage error with exit code 78.
inline void checkUsage() {
    struct Case {
        std::vector<const char*> argv;
        Command command;
    };
    const std::vector<Case> cases = {
        {{"embed"}, Command::run},
        {{"embed", "run"}, Command::run},
        {{"embed", "--self-test"}, Command::selfTest},
        {{"embed", "--licenses"}, Command::licenses},
        {{"embed", "--version"}, Command::version},
        {{"embed", "--unknown"}, Command::usage},
        {{"embed", "run", "--unknown"}, Command::usage},
    };
    for (const auto& c : cases) {
        expect(parseCommand(static_cast<int>(c.argv.size()), c.argv.data()) == c.command,
            "command line with " + std::to_string(c.argv.size()) + " arguments parses differently");
    }
    expect(exitUsage == 78 && exitConfig == 78 && exitFailure == 1 && exitStopped == 0,
        "the exit codes are not 0, 1 and 78");
}

// Runs every check; throws the first failure.
inline void runSelfTest() {
    checkFormatByteCount();
    checkResetText();
    checkClientLimitText();
    checkStatusLines();
    checkStatusRules();
    checkDataFields();
    checkCapObject();
    checkClientJwtClaims();
    checkOriginUrl();
    checkTokenFetch();
    checkCapRead();
    checkStateFiles();
    checkConfig();
    checkSdkValues();
    checkUsage();
}

// Runs the self-test and prints one line: exit code 0 when it passed, 1 when
// not.
inline int selfTestMain() {
    try {
        runSelfTest();
    } catch (const std::exception& e) {
        std::cerr << "embed self-test failed: " << e.what() << std::endl;
        return exitFailure;
    }
    printLine("embed self-test passed");
    return exitStopped;
}

}  // namespace embed
