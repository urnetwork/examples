// Obtaining the client JWT and reading the caps (EMBED_CONTRACT.md,
// "Obtaining the client JWT" and "App lifecycle"), over a replaceable HTTP
// function: the app passes curlHttp (http_curl.hpp) and the self-test a
// stand-in server. fetchClientJwt is the one function to replace with your
// own sign-in: it posts this installation's instance-id to your backend and
// keeps the scoped client JWT that comes back. Nothing here prints a client
// JWT or the demo session.
#pragma once

#include <cctype>
#include <functional>
#include <optional>
#include <string>
#include <string_view>

#include <nlohmann/json.hpp>

#include "command.hpp"
#include "state.hpp"
#include "status.hpp"

namespace embed {

// One HTTP request. authorization is the bearer token, sent as
// "Authorization: Bearer <token>"; body, when set, is sent as
// application/json.
struct HttpRequest {
    std::string method;
    std::string url;
    std::string authorization;
    std::optional<std::string> body;
};

// One HTTP answer.
struct HttpResponse {
    long status = 0;
    std::string body;
};

// Sends one request. Empty with the reason in error when no answer arrived:
// the server is unreachable, the answer is too large, or a timeout.
using HttpFunction = std::function<std::optional<HttpResponse>(const HttpRequest& request, std::string& error)>;

inline constexpr const char* defaultApiUrl = "https://api.bringyour.com";
inline constexpr const char* clientTokenPath = "/urnetwork/client-token";
inline constexpr const char* clientDataCapPath = "/network/client-data-cap";

namespace detail {

// Whether text is a host name: letters, digits, dots and hyphens.
inline bool isHostName(std::string_view text) {
    if (text.empty() || 253 < text.size()) {
        return false;
    }
    for (char c : text) {
        if (!std::isalnum(static_cast<unsigned char>(c)) && c != '.' && c != '-') {
            return false;
        }
    }
    return true;
}

// Whether text is a bracketed IPv6 literal such as "[::1]".
inline bool isIpv6Literal(std::string_view text) {
    if (text.size() < 3 || text.front() != '[' || text.back() != ']') {
        return false;
    }
    for (char c : text.substr(1, text.size() - 2)) {
        if (!std::isxdigit(static_cast<unsigned char>(c)) && c != ':' && c != '.') {
            return false;
        }
    }
    return true;
}

}  // namespace detail

// Checks an origin, as the allocators do: an HTTPS origin, or explicit
// loopback HTTP (localhost, 127.0.0.1, [::1]) for local testing, optionally
// with a port, and no credentials, path beyond "/", query or fragment. Returns
// the origin with path appended; empty for any other text.
inline std::optional<std::string> originUrl(std::string_view origin, std::string_view path) {
    bool https = origin.substr(0, 8) == "https://";
    bool http = origin.substr(0, 7) == "http://";
    if (!https && !http) {
        return std::nullopt;
    }
    std::string_view rest = origin.substr(https ? 8 : 7);
    std::size_t authorityLength = rest.find_first_of("/?#");
    if (authorityLength == std::string_view::npos) {
        authorityLength = rest.size();
    }
    std::string_view authority = rest.substr(0, authorityLength);
    std::string_view after = rest.substr(authorityLength);
    // nothing after the authority but one optional "/"
    if (!after.empty() && after != "/") {
        return std::nullopt;
    }
    if (authority.find('@') != std::string_view::npos) {
        return std::nullopt;
    }
    // the host, then an optional ":port"
    std::size_t hostLength = authority.size();
    if (!authority.empty() && authority.front() == '[') {
        std::size_t close = authority.find(']');
        if (close == std::string_view::npos) {
            return std::nullopt;
        }
        hostLength = close + 1;
    } else if (std::size_t colon = authority.find(':'); colon != std::string_view::npos) {
        hostLength = colon;
    }
    std::string_view host = authority.substr(0, hostLength);
    std::string_view port = authority.substr(hostLength);
    if (!port.empty()) {
        if (port.front() != ':' || port.size() < 2 || 6 < port.size()) {
            return std::nullopt;
        }
        long number = 0;
        for (char c : port.substr(1)) {
            if (!std::isdigit(static_cast<unsigned char>(c))) {
                return std::nullopt;
            }
            number = number * 10 + (c - '0');
        }
        if (number < 1 || 65535 < number) {
            return std::nullopt;
        }
    }
    bool loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]";
    if (http && !loopback) {
        return std::nullopt;
    }
    if (!detail::isHostName(host) && !detail::isIpv6Literal(host)) {
        return std::nullopt;
    }
    return std::string(origin.substr(0, (https ? 8 : 7) + authorityLength)) + std::string(path);
}

// The outcome of a token fetch, with its exit code and GUI state
// (EMBED_CONTRACT.md, "Obtaining the client JWT").
enum class FetchResult {
    ok,
    // 401 unauthorized, 409 installation_limit or client_limit: exit 78, GUI
    // signed out
    refused,
    // unreachable, 5xx, or an invalid answer: exit 1, GUI stopped
    failed,
};

// The token server's answer, checked.
struct Token {
    std::string clientId;
    // never print it
    std::string clientJwt;
    std::optional<Cap> dataCap;
};

// A fetch's result, its token when ok, and the reason otherwise.
struct FetchOutcome {
    FetchResult result = FetchResult::failed;
    Token token;
    std::string error;
};

// Obtains this installation's client JWT from the token server: posts the
// instance-id with the demo session as the bearer token to
// POST /urnetwork/client-token, checks that by_client_jwt carries a client_id
// claim equal to client_id, and saves it as client.jwt. A 401 or 409 is a
// refusal; no answer, another status or an invalid answer is a failure. The
// token server's data_cap, when present, is the first cap reading.
inline FetchOutcome fetchClientJwt(const HttpFunction& http, const std::string& tokenServerUrl,
    const std::string& demoSession, const fs::path& stateDir, const std::string& instanceId) {
    FetchOutcome outcome;
    auto url = originUrl(tokenServerUrl, clientTokenPath);
    if (!url) {
        outcome.error = "the token server URL is not an HTTPS origin";
        return outcome;
    }
    HttpRequest request{"POST", *url, demoSession, nlohmann::json{{"installation_id", instanceId}}.dump()};
    std::string httpError;
    auto response = http(request, httpError);
    if (!response) {
        outcome.error = "the token server is unreachable: " + httpError;
        return outcome;
    }
    if (response->status == 401 || response->status == 409) {
        auto answer = nlohmann::json::parse(response->body, nullptr, false);
        std::string message = response->status == 401 ? "unauthorized" : "conflict";
        if (answer.is_object() && answer.contains("error") && answer["error"].is_object() &&
            answer["error"].contains("message") && answer["error"]["message"].is_string()) {
            message = answer["error"]["message"].get<std::string>();
        }
        outcome.result = FetchResult::refused;
        outcome.error = "the token server refused this installation: " + message;
        return outcome;
    }
    if (response->status != 200) {
        outcome.error = "the token server answered HTTP " + std::to_string(response->status);
        return outcome;
    }
    auto answer = nlohmann::json::parse(response->body, nullptr, false);
    if (!answer.is_object() || !answer.contains("client_id") || !answer["client_id"].is_string() ||
        !answer.contains("by_client_jwt") || !answer["by_client_jwt"].is_string()) {
        outcome.error = "the token server's answer is not valid";
        return outcome;
    }
    auto answerClientId = parseUuid(answer["client_id"].get<std::string>());
    std::string clientJwt = answer["by_client_jwt"].get<std::string>();
    if (!answerClientId) {
        outcome.error = "the token server's answer is not valid";
        return outcome;
    }
    std::string claimClientId;
    try {
        claimClientId = parseClientJwtClientId(clientJwt);
    } catch (const std::exception& e) {
        outcome.error = std::string("the token server's answer is not valid: ") + e.what();
        return outcome;
    }
    if (claimClientId != *answerClientId) {
        outcome.error = "the token server's client JWT belongs to another client";
        return outcome;
    }
    try {
        saveClientJwt(stateDir, clientJwt);
    } catch (const std::exception& e) {
        outcome.error = std::string("save ") + clientJwtFileName + ": " + e.what();
        return outcome;
    }
    outcome.result = FetchResult::ok;
    outcome.token.clientId = *answerClientId;
    outcome.token.clientJwt = clientJwt;
    if (answer.contains("data_cap")) {
        outcome.token.dataCap = parseCap(answer["data_cap"]);
    }
    return outcome;
}

// Reads this client's caps with its own client JWT:
// GET /network/client-data-cap at the api origin. Empty with the reason for no
// answer, another status (a server without the cap routes answers 404) or an
// answer that is not a cap object; the Embed-not-enabled refusal also sets
// notEnabled.
inline std::optional<Cap> readCaps(const HttpFunction& http, const std::string& apiUrl, const std::string& clientJwt,
    std::string& error, bool* notEnabled = nullptr) {
    auto url = originUrl(apiUrl, clientDataCapPath);
    if (!url) {
        error = "the API URL is not an HTTPS origin";
        return std::nullopt;
    }
    std::string httpError;
    auto response = http(HttpRequest{"GET", *url, clientJwt, std::nullopt}, httpError);
    if (!response) {
        error = httpError;
        return std::nullopt;
    }
    if (response->status < 200 || 300 <= response->status) {
        error = "HTTP " + std::to_string(response->status);
        return std::nullopt;
    }
    auto cap = parseCapText(response->body);
    if (!cap && capNotEnabled(response->body)) {
        error = embedNotEnabledMessage;
        if (notEnabled) {
            *notEnabled = true;
        }
    } else if (!cap) {
        error = "the answer is not a cap object";
    }
    return cap;
}

// The settings of a run, as the environment gives them; empty when unset.
struct Settings {
    std::optional<std::string> stateDir;
    std::optional<std::string> tokenServerUrl;
    std::optional<std::string> demoSession;
    std::optional<std::string> apiUrl;
};

// The configuration of a run, loaded at start.
struct Config {
    fs::path stateDir;
    // the run loop replaces it when the sdk refreshes the token
    std::string clientJwt;
    std::string clientId;
    std::string instanceId;
    // the cap read's origin, checked
    std::string apiUrl;
    // the token server's data_cap, the first cap reading
    std::optional<Cap> firstCap;
};

namespace detail {

// Whether a setting is set: not empty.
inline bool isSet(const std::optional<std::string>& value) {
    return value && !value->empty();
}

// Whether the demo session can go in an Authorization header: printable ascii
// without spaces.
inline bool isBearerToken(std::string_view text) {
    if (text.empty()) {
        return false;
    }
    for (char c : text) {
        if (c <= ' ' || '~' < c) {
            return false;
        }
    }
    return true;
}

}  // namespace detail

// Loads the configuration of a run: checks the state directory and the URLs,
// creates instance-id on first run, and obtains the client JWT from the token
// server when one is configured, otherwise from client.jwt. Returns 0 when
// loaded, or the exit code with the reason in error: 78 for a configuration or
// credential problem, 1 for a token server failure.
inline int loadConfig(const Settings& settings, const HttpFunction& http, Config& config, std::string& error) {
    config = Config{};
    try {
        config.stateDir = fs::path(settings.stateDir.value_or(""));
        checkStateDir(config.stateDir);
        auto apiUrl = originUrl(detail::isSet(settings.apiUrl) ? *settings.apiUrl : defaultApiUrl, "");
        if (!apiUrl) {
            throw ConfigError("URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for local testing");
        }
        config.apiUrl = *apiUrl;
        bool tokenServer = detail::isSet(settings.tokenServerUrl);
        if (tokenServer != detail::isSet(settings.demoSession)) {
            throw ConfigError("set both URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or neither");
        }
        std::optional<std::string> tokenServerUrl;
        if (tokenServer) {
            tokenServerUrl = originUrl(*settings.tokenServerUrl, "");
            if (!tokenServerUrl) {
                throw ConfigError(
                    "URNETWORK_TOKEN_SERVER_URL must be an HTTPS origin, or loopback HTTP for local testing");
            }
            if (!detail::isBearerToken(*settings.demoSession)) {
                throw ConfigError("URNETWORK_DEMO_SESSION must hold the demo session token");
            }
        }
        config.instanceId = loadOrCreateInstanceId(config.stateDir);
        if (tokenServer) {
            auto outcome =
                fetchClientJwt(http, *tokenServerUrl, *settings.demoSession, config.stateDir, config.instanceId);
            if (outcome.result != FetchResult::ok) {
                error = outcome.error;
                return outcome.result == FetchResult::refused ? exitConfig : exitFailure;
            }
            config.clientJwt = outcome.token.clientJwt;
            config.clientId = outcome.token.clientId;
            config.firstCap = outcome.token.dataCap;
            return exitStopped;
        }
        auto stored = loadClientJwt(config.stateDir);
        if (!stored) {
            throw ConfigError(std::string("no token server and no ") + clientJwtFileName +
                ": set URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or write " + clientJwtFileName +
                " with your backend tool's provision");
        }
        config.clientJwt = stored->first;
        config.clientId = stored->second;
        return exitStopped;
    } catch (const ConfigError& e) {
        error = e.what();
        return exitConfig;
    }
}

}  // namespace embed
