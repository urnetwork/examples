// SERVER ONLY: the C++ embed backend tool (EMBED_CONTRACT.md, "Backend tools").
//
//   embed-server provision <key> <client-jwt-file>
//   embed-server cap <key> [--monthly <bytes>|--monthly null]
//                          [--total <bytes>|--total null] [--reset-total]
//   embed-server usage <key>
//   embed-server usage-all
//   embed-server remove <key>
//   embed-server --self-test
//
// It extends the C++ integration allocator (../../integration/server): the
// same settings (URNETWORK_ROOT_JWT, an API key or a network JWT;
// URNETWORK_CLIENT_MAP, an absolute path in an existing private,
// service-owned directory; optional URNETWORK_API_URL), map format, key
// pattern, lock and response checks. Your service authenticates its user
// first and supplies the key internally, as
// user:<service-user-id>:<installation-id>, never a raw request field or a
// URnetwork client ID. Exit codes: 0 success, 78 configuration or credential
// problem, 1 any other failure, with one stderr line that never holds a
// secret. This POSIX CLI uses libcurl + nlohmann/json. Confirm a crash-left
// .lock is stale before removing it.
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <curl/curl.h>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <limits>
#include <memory>
#include <nlohmann/json.hpp>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
using Json = nlohmann::json;
namespace fs = std::filesystem;
constexpr size_t limit = 1 << 20;
constexpr int exitOk = 0, exitFailure = 1, exitConfig = 78;
const char* const description = "embed client";
const char* const deviceSpec = "urnetwork-examples/cpp-embed-server";
const char* const usageText =
    "usage: embed-server provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|null] "
    "[--total <bytes>|null] [--reset-total] | usage <key> | usage-all | remove <key> | --self-test";
const char* const clientLimitText =
    "client limit reached: your network is at its client limit; see https://ur.io/services";
const std::regex keys("user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}");
const std::regex ids("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

// A failure with its exit code and its one stderr line.
struct Failure : std::runtime_error {
    int code;
    Failure(int exitCode, const std::string& message) : std::runtime_error(message), code(exitCode) {}
};

// One API answer.
struct ApiAnswer {
    long status = 0;
    std::string body;
};

// One call to the URnetwork API: the method, the path with its query, and an
// optional json body. Empty when no answer arrived.
using Call = std::function<std::optional<ApiAnswer>(
    const std::string& method, const std::string& path, const std::optional<Json>& body)>;

// The tool's map, API and output streams.
struct Tool {
    fs::path mapPath;
    Call call;
    std::ostream& out;
};

std::string urlPart(CURLU* u, CURLUPart part) {
    char* raw = nullptr;
    if (curl_url_get(u, part, &raw, 0) != CURLUE_OK)
        return {};
    std::unique_ptr<char, decltype(&curl_free)> owned(raw, curl_free);
    return raw;
}

// The API origin with the allocators' rules: HTTPS, or explicit loopback HTTP
// for local tests and mocks; no credentials, path beyond "/", query or
// fragment. Empty when invalid.
std::string origin(const std::string& base) {
    std::unique_ptr<CURLU, decltype(&curl_url_cleanup)> url(curl_url(), curl_url_cleanup);
    if (!url || curl_url_set(url.get(), CURLUPART_URL, base.c_str(), 0) != CURLUE_OK)
        return {};
    auto scheme = urlPart(url.get(), CURLUPART_SCHEME), host = urlPart(url.get(), CURLUPART_HOST),
         path = urlPart(url.get(), CURLUPART_PATH);
    bool local = host == "localhost" || host == "127.0.0.1" || host == "[::1]";
    if (host.empty() || !(scheme == "https" || (scheme == "http" && local)) || !(path.empty() || path == "/") ||
        !urlPart(url.get(), CURLUPART_USER).empty() || !urlPart(url.get(), CURLUPART_PASSWORD).empty() ||
        !urlPart(url.get(), CURLUPART_QUERY).empty() || !urlPart(url.get(), CURLUPART_FRAGMENT).empty())
        return {};
    if (curl_url_set(url.get(), CURLUPART_PATH, "", 0) != CURLUE_OK)
        return {};
    auto raw = urlPart(url.get(), CURLUPART_URL);
    while (!raw.empty() && raw.back() == '/')
        raw.pop_back();
    return raw;
}

std::string base64urlDecode(const std::string& value) {
    const std::string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string output;
    unsigned int bits = 0;
    int count = 0;
    if (value.empty() || value.size() % 4 == 1)
        return {};
    for (unsigned char c : value) {
        auto index = alphabet.find(c);
        if (index == std::string::npos)
            return {};
        bits = (bits << 6) | unsigned(index);
        count += 6;
        if (count >= 8) {
            count -= 8;
            output.push_back(char((bits >> count) & 255));
        }
    }
    return output;
}

// Whether a client JWT's client_id claim equals id. This checks consistency;
// it is not local signature verification.
bool claimMatches(const std::string& jwt, const std::string& id) {
    auto a = jwt.find('.'), b = jwt.find('.', a == std::string::npos ? 0 : a + 1);
    if (a == std::string::npos || a == 0 || b == std::string::npos || b <= a + 1 || b + 1 >= jwt.size() ||
        jwt.find('.', b + 1) != std::string::npos)
        return false;
    auto claims = Json::parse(base64urlDecode(jwt.substr(a + 1, b - a - 1)), nullptr, false);
    return claims.is_object() && claims.contains("client_id") && claims["client_id"] == id;
}

// Loads the private map: a missing file is an empty map. Only the allocators'
// fields are accepted, so a map with other fields (such as the token server's
// pending_caps) is refused and never rewritten by this tool.
Json loadMap(const fs::path& file) {
    struct stat st {};
    if (lstat(file.c_str(), &st) != 0) {
        if (errno != ENOENT)
            throw Failure(exitConfig, "the client map is not a valid private map");
        return {{"version", 1}, {"clients", Json::object()}};
    }
    if (!S_ISREG(st.st_mode) || (st.st_mode & 0077) || st.st_size > long(limit))
        throw Failure(exitConfig, "the client map is not a valid private map");
    std::ifstream input(file);
    Json map = Json::parse(input, nullptr, false);
    bool valid = map.is_object() && map.size() == 2 && map.contains("version") && map["version"].is_number_integer() &&
        map["version"] == 1 && map.contains("clients") && map["clients"].is_object();
    std::set<std::string> seen;
    if (valid) {
        for (auto& [key, value] : map["clients"].items()) {
            valid = valid && std::regex_match(key, keys) && value.is_string() &&
                std::regex_match(value.get<std::string>(), ids) && seen.insert(value.get<std::string>()).second;
        }
    }
    if (!valid)
        throw Failure(exitConfig, "the client map is not a valid private map");
    return map;
}

// Writes text to path atomically, owner-only: a private temporary file in the
// same directory, synced, then renamed over the old file.
void writePrivate(const fs::path& file, const std::string& raw) {
    std::string pattern = file.string() + ".XXXXXX";
    std::vector<char> temp(pattern.begin(), pattern.end());
    temp.push_back(0);
    int fd = mkstemp(temp.data());
    bool ok = fd >= 0 && fchmod(fd, 0600) == 0;
    size_t offset = 0;
    while (ok && offset < raw.size()) {
        ssize_t n = write(fd, raw.data() + offset, raw.size() - offset);
        if (n < 0 && errno == EINTR)
            continue;
        ok = n > 0;
        if (ok)
            offset += size_t(n);
    }
    ok = ok && fsync(fd) == 0;
    if (fd >= 0 && close(fd) != 0)
        ok = false;
    ok = ok && rename(temp.data(), file.c_str()) == 0;
    if (!ok) {
        if (fd >= 0)
            unlink(temp.data());
        throw Failure(exitFailure, "could not write " + file.filename().string());
    }
}

// The exclusive <map>.lock directory, held through the remote call and the
// map update.
struct Lock {
    fs::path path;
    explicit Lock(const fs::path& file) : path(file.string() + ".lock") {
        if (mkdir(path.c_str(), 0700) != 0)
            throw Failure(exitFailure, "the client map is locked by another run; retry");
    }
    ~Lock() { rmdir(path.c_str()); }
};

// The client mapped to key, or empty.
std::string mappedClient(const Json& map, const std::string& key) {
    return map["clients"].value(key, std::string{});
}

// The kind of an API answer.
enum class Kind { ok, refused, unauthorized, failed };

// Calls the API and reads its answer as a json object.
Kind callApi(const Tool& tool, const std::string& method, const std::string& path, const std::optional<Json>& body,
    Json& answer, long& status) {
    answer = Json();
    auto response = tool.call(method, path, body);
    status = response ? response->status : 0;
    if (!response)
        return Kind::failed;
    if (status == 401 || status == 403)
        return Kind::unauthorized;
    if (status < 200 || status >= 300)
        return Kind::failed;
    answer = Json::parse(response->body, nullptr, false);
    if (!answer.is_object())
        return Kind::failed;
    if (answer.contains("error") && !answer["error"].is_null())
        return Kind::refused;
    return Kind::ok;
}

// The message of a refusal, or "".
std::string refusalMessage(const Json& answer) {
    if (answer.contains("error") && answer["error"].is_object() && answer["error"].contains("message") &&
        answer["error"]["message"].is_string())
        return answer["error"]["message"].get<std::string>();
    return {};
}

// Whether a refusal is either client limit flag.
bool clientLimitRefusal(const Json& answer) {
    const Json& error = answer["error"];
    return error.is_object() &&
        ((error.contains("client_limit_exceeded") && error["client_limit_exceeded"] == true) ||
            (error.contains("upgrade_required") && error["upgrade_required"] == true));
}

// The key's mapped client, or a configuration failure.
std::string requireClient(const Json& map, const std::string& key) {
    auto client = mappedClient(map, key);
    if (client.empty())
        throw Failure(exitConfig, "no client is mapped for " + key);
    return client;
}

void requireKey(const std::string& key, const std::string& command) {
    if (!std::regex_match(key, keys))
        throw Failure(exitConfig, command + ": invalid key");
}

// provision <key> <client-jwt-file>: reissues the key's client, or provisions
// a new one; on "Client does not exist." it drops the mapping and provisions a
// new client. The client JWT goes only to the file.
int provision(const Tool& tool, const std::string& key, const std::string& jwtFile) {
    requireKey(key, "provision");
    if (jwtFile.empty())
        throw Failure(exitConfig, "provision: invalid file");
    Lock lock(tool.mapPath);
    Json map = loadMap(tool.mapPath), answer;
    std::string old = mappedClient(map, key);
    for (int attempt = 0; attempt < 2; attempt += 1) {
        Json body = {{"description", description}, {"device_spec", deviceSpec}};
        if (!old.empty())
            body["client_id"] = old;
        long status = 0;
        Kind kind = callApi(tool, "POST", "/network/auth-client", body, answer, status);
        if (kind == Kind::unauthorized)
            throw Failure(exitConfig, "the API refused the root credential");
        if (kind == Kind::failed)
            throw Failure(exitFailure, "provisioning failed: no valid answer (HTTP " + std::to_string(status) + ")");
        if (kind == Kind::refused) {
            if (clientLimitRefusal(answer))
                throw Failure(exitConfig, clientLimitText);
            if (!old.empty() && refusalMessage(answer) == "Client does not exist.") {
                // deactivated after 30 days without connecting: provision anew
                map["clients"].erase(key);
                writePrivate(tool.mapPath, map.dump());
                old.clear();
                continue;
            }
            throw Failure(exitFailure, "provisioning refused: " + refusalMessage(answer));
        }
        break;
    }
    bool valid = answer.is_object() && answer.contains("client_id") && answer["client_id"].is_string() &&
        answer.contains("by_client_jwt") && answer["by_client_jwt"].is_string();
    std::string id = valid ? answer["client_id"].get<std::string>() : "",
                jwt = valid ? answer["by_client_jwt"].get<std::string>() : "";
    if (!valid || !std::regex_match(id, ids) || !claimMatches(jwt, id) || (!old.empty() && old != id))
        throw Failure(exitFailure, "provisioning answered an invalid client");
    if (old.empty()) {
        for (const auto& value : map["clients"])
            if (value == id)
                throw Failure(exitFailure, "provisioning answered a client mapped to another key");
        map["clients"][key] = id;
        writePrivate(tool.mapPath, map.dump());
    }
    std::string line = jwt + "\n";
    try {
        writePrivate(jwtFile, line);
    } catch (const Failure&) {
        std::fill(line.begin(), line.end(), '\0');
        throw Failure(exitFailure, "could not write the client JWT file");
    }
    std::fill(line.begin(), line.end(), '\0');
    tool.out << Json{{"client_id", id}}.dump() << '\n';
    return exitOk;
}

// A byte count: a decimal integer from 0 to 9223372036854775807, no units.
std::optional<int64_t> parseByteCount(const std::string& text) {
    if (text.empty() || !std::all_of(text.begin(), text.end(), [](char c) { return '0' <= c && c <= '9'; }))
        return std::nullopt;
    uint64_t number = 0;
    for (char c : text) {
        number = number * 10 + uint64_t(c - '0');
        if (number > uint64_t(std::numeric_limits<int64_t>::max()))
            return std::nullopt;
    }
    return int64_t(number);
}

// The cap request body from the options: only the given fields, with null for
// a cleared cap and reset_total only when given.
std::optional<Json> capRequest(const std::string& client, const std::vector<std::string>& options) {
    Json body = {{"client_id", client}};
    bool monthly = false, total = false, reset = false;
    for (size_t i = 0; i < options.size(); i += 1) {
        const std::string& option = options[i];
        if (option == "--reset-total" && !reset) {
            reset = true;
            body["reset_total"] = true;
            continue;
        }
        bool isMonthly = option == "--monthly" && !monthly, isTotal = option == "--total" && !total;
        if ((!isMonthly && !isTotal) || i + 1 >= options.size())
            return std::nullopt;
        const std::string& value = options[++i];
        Json limitValue = nullptr;
        if (value != "null") {
            auto count = parseByteCount(value);
            if (!count)
                return std::nullopt;
            limitValue = *count;
        }
        body[isMonthly ? "monthly_byte_limit" : "total_byte_limit"] = limitValue;
        (isMonthly ? monthly : total) = true;
    }
    if (!monthly && !total && !reset)
        return std::nullopt;
    return body;
}

// Prints a cap object answer, or throws the failure.
int printCap(const Tool& tool, Kind kind, const Json& answer, long status) {
    if (kind == Kind::unauthorized)
        throw Failure(exitConfig, "the API refused the root credential");
    if (kind == Kind::refused)
        throw Failure(exitFailure, "the API refused: " + refusalMessage(answer));
    if (kind == Kind::failed || !answer.contains("client_id"))
        throw Failure(exitFailure, "no cap object in the answer (HTTP " + std::to_string(status) +
                "; a server without the cap routes answers 404)");
    tool.out << answer.dump() << '\n';
    return exitOk;
}

// cap <key> [options]: posts only the given fields.
int cap(const Tool& tool, const std::string& key, const std::vector<std::string>& options) {
    requireKey(key, "cap");
    std::string client = requireClient(loadMap(tool.mapPath), key);
    auto body = capRequest(client, options);
    if (!body)
        throw Failure(exitConfig, "cap: give --monthly, --total or --reset-total with byte counts or null");
    Json answer;
    long status = 0;
    Kind kind = callApi(tool, "POST", "/network/client-data-cap", body, answer, status);
    return printCap(tool, kind, answer, status);
}

// usage <key>: the key's cap object, read with the root credential.
int usage(const Tool& tool, const std::string& key) {
    requireKey(key, "usage");
    std::string client = requireClient(loadMap(tool.mapPath), key);
    Json answer;
    long status = 0;
    Kind kind = callApi(tool, "GET", "/network/client-data-cap?client_id=" + client, std::nullopt, answer, status);
    return printCap(tool, kind, answer, status);
}

// Percent-encodes all but the unreserved characters.
std::string encodeQueryValue(const std::string& text) {
    static const char digits[] = "0123456789ABCDEF";
    std::string out;
    for (unsigned char c : text) {
        if (std::isalnum(c) || c == '-' || c == '.' || c == '_' || c == '~') {
            out.push_back(char(c));
        } else {
            out += '%';
            out += digits[c >> 4];
            out += digits[c & 15];
        }
    }
    return out;
}

// usage-all: pages through GET /network/client-data-caps with limit=1000 and
// prints one cap object per line, stopping at a null cursor or a repeated one.
int usageAll(const Tool& tool) {
    std::optional<std::string> cursor;
    for (;;) {
        std::string path = "/network/client-data-caps?limit=1000";
        if (cursor)
            path += "&cursor=" + encodeQueryValue(*cursor);
        Json answer;
        long status = 0;
        Kind kind = callApi(tool, "GET", path, std::nullopt, answer, status);
        if (kind == Kind::unauthorized)
            throw Failure(exitConfig, "the API refused the root credential");
        if (kind != Kind::ok || !answer.contains("clients") || !answer["clients"].is_array())
            throw Failure(exitFailure, "no page of cap objects in the answer (HTTP " + std::to_string(status) + ")");
        for (const auto& client : answer["clients"])
            tool.out << client.dump() << '\n';
        if (!answer.contains("next_cursor") || !answer["next_cursor"].is_string())
            return exitOk;
        std::string next = answer["next_cursor"].get<std::string>();
        if (cursor && next == *cursor)
            return exitOk;
        cursor = next;
    }
}

// remove <key>: removes the key's client, then the mapping, also when the
// client no longer exists.
int removeClient(const Tool& tool, const std::string& key) {
    requireKey(key, "remove");
    Lock lock(tool.mapPath);
    Json map = loadMap(tool.mapPath);
    std::string client = requireClient(map, key);
    Json answer;
    long status = 0;
    Kind kind = callApi(tool, "POST", "/network/remove-client", Json{{"client_id", client}}, answer, status);
    if (kind == Kind::unauthorized)
        throw Failure(exitConfig, "the API refused the root credential");
    if (kind == Kind::failed || (kind == Kind::refused && refusalMessage(answer) != "Client does not exist."))
        throw Failure(exitFailure,
            "removing the client failed" + (kind == Kind::refused ? ": " + refusalMessage(answer) : ""));
    map["clients"].erase(key);
    writePrivate(tool.mapPath, map.dump());
    tool.out << Json{{"removed", client}}.dump() << '\n';
    return exitOk;
}

// Runs one command line (the arguments after the program name), writing a
// failure's one line to err.
int run(const Tool& tool, std::ostream& err, const std::vector<std::string>& args) {
    try {
        if (args.size() == 3 && args[0] == "provision")
            return provision(tool, args[1], args[2]);
        if (args.size() >= 2 && args[0] == "cap")
            return cap(tool, args[1], std::vector<std::string>(args.begin() + 2, args.end()));
        if (args.size() == 2 && args[0] == "usage")
            return usage(tool, args[1]);
        if (args.size() == 1 && args[0] == "usage-all")
            return usageAll(tool);
        if (args.size() == 2 && args[0] == "remove")
            return removeClient(tool, args[1]);
        throw Failure(exitConfig, usageText);
    } catch (const Failure& failure) {
        err << failure.what() << '\n';
        return failure.code;
    }
}

size_t receive(char* bytes, size_t size, size_t count, void* context) {
    auto& output = *static_cast<std::string*>(context);
    size_t n = size * count;
    if (n > limit - output.size())
        return 0;
    try {
        output.append(bytes, n);
    } catch (...) {
        return 0;
    }
    return n;
}

// One API call with the root credential as the bearer token.
std::optional<ApiAnswer> apiCall(const std::string& apiOrigin, const std::string& root, const std::string& method,
    const std::string& path, const std::optional<Json>& body) {
    std::unique_ptr<CURL, decltype(&curl_easy_cleanup)> curl(curl_easy_init(), curl_easy_cleanup);
    if (!curl)
        return std::nullopt;
    curl_slist* list = nullptr;
    std::string authorization = "Authorization: Bearer " + root;
    list = curl_slist_append(list, authorization.c_str());
    std::fill(authorization.begin(), authorization.end(), '\0');
    if (body)
        list = curl_slist_append(list, "Content-Type: application/json");
    std::unique_ptr<curl_slist, decltype(&curl_slist_free_all)> headers(list, curl_slist_free_all);
    std::string url = apiOrigin + path, raw = body ? body->dump() : "", response;
    curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, list);
    curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, 15L);
    curl_easy_setopt(curl.get(), CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, receive);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &response);
    if (body) {
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDS, raw.c_str());
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDSIZE, long(raw.size()));
    } else if (method == "GET") {
        curl_easy_setopt(curl.get(), CURLOPT_HTTPGET, 1L);
    }
    if (curl_easy_perform(curl.get()) != CURLE_OK)
        return std::nullopt;
    long code = 0;
    curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &code);
    return ApiAnswer{code, response};
}

void require(bool ok, int line) {
    if (!ok)
        throw std::runtime_error("embed-server self-test failed at line " + std::to_string(line));
}
#define CHECK(condition) require(condition, __LINE__)

// A mock API: canned answers in order, and the requests it saw.
struct Mock {
    Mock() = default;
    explicit Mock(std::vector<ApiAnswer> cannedAnswers) : answers(std::move(cannedAnswers)) {}
    std::vector<ApiAnswer> answers;
    std::vector<std::string> methods, paths;
    std::vector<std::optional<Json>> bodies;
    Call call() {
        return [this](const std::string& method, const std::string& path,
                   const std::optional<Json>& body) -> std::optional<ApiAnswer> {
            if (methods.size() >= answers.size())
                return std::nullopt;
            methods.push_back(method);
            paths.push_back(path);
            bodies.push_back(body);
            return answers[methods.size() - 1];
        };
    }
};

// Runs a command line against the mock; out and err receive the output.
int runWith(Mock& mock, const fs::path& mapPath, std::string& out, std::string& err,
    const std::vector<std::string>& args) {
    std::ostringstream outStream, errStream;
    Tool tool{mapPath, mock.call(), outStream};
    int code = run(tool, errStream, args);
    out = outStream.str();
    err = errStream.str();
    return code;
}

void selfTest() {
    std::string id = "11111111-1111-1111-1111-111111111111", id2 = "22222222-2222-2222-2222-222222222222";
    // fixed offline token payloads holding only {"client_id": id} and
    // {"client_id": id2}
    std::string jwt = "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ.test";
    std::string jwt2 = "e30.eyJjbGllbnRfaWQiOiIyMjIyMjIyMi0yMjIyLTIyMjItMjIyMi0yMjIyMjIyMjIyMjIifQ.test";
    std::string answer = Json{{"client_id", id}, {"by_client_jwt", jwt}}.dump(),
                answer2 = Json{{"client_id", id2}, {"by_client_jwt", jwt2}}.dump();
    std::string pattern = (fs::temp_directory_path() / "ur-embed-server-XXXXXX").string();
    std::vector<char> temp(pattern.begin(), pattern.end());
    temp.push_back(0);
    CHECK(mkdtemp(temp.data()) != nullptr);
    fs::path dir(temp.data()), mapPath = dir / "clients.json", jwtPath = dir / "client.jwt";
    std::string key = "user:alice:22222222-2222-2222-2222-222222222222", out, err;
    try {
        // provision: a new client, then a reissue
        Mock m{{{200, answer}, {200, answer}}};
        CHECK(runWith(m, mapPath, out, err, {"provision", key, jwtPath.string()}) == exitOk);
        CHECK(out == "{\"client_id\":\"" + id + "\"}\n" && out.find(".test") == std::string::npos &&
              err.find(".test") == std::string::npos);
        CHECK(runWith(m, mapPath, out, err, {"provision", key, jwtPath.string()}) == exitOk);
        CHECK(m.paths[0] == "/network/auth-client" && m.methods[0] == "POST");
        CHECK(!m.bodies[0]->contains("client_id") && !m.bodies[0]->contains("source_client_id"));
        CHECK((*m.bodies[0])["description"] == description && (*m.bodies[0])["device_spec"] == deviceSpec);
        CHECK((*m.bodies[1])["client_id"] == id && !m.bodies[1]->contains("source_client_id"));
        // the client JWT file and the map are private, the map round trips
        struct stat st {};
        CHECK(stat(jwtPath.c_str(), &st) == 0 && !(st.st_mode & 0077));
        CHECK(stat(mapPath.c_str(), &st) == 0 && !(st.st_mode & 0077));
        std::ifstream jwtFile(jwtPath);
        std::string jwtLine;
        std::getline(jwtFile, jwtLine);
        CHECK(jwtLine == jwt);
        CHECK(loadMap(mapPath)["clients"][key] == id);
        // "Client does not exist." drops the mapping and provisions anew
        Mock gone{{{200, R"({"error":{"message":"Client does not exist."}})"}, {200, answer2}}};
        CHECK(runWith(gone, mapPath, out, err, {"provision", key, jwtPath.string()}) == exitOk);
        CHECK((*gone.bodies[0])["client_id"] == id && !gone.bodies[1]->contains("client_id"));
        CHECK(loadMap(mapPath)["clients"][key] == id2);
        // the client limit (either flag) and a refused root credential are 78
        std::string newKey = "user:bob:33333333-3333-3333-3333-333333333333";
        for (const char* refusal :
            {R"({"error":{"client_limit_exceeded":true,"message":"Client limit exceeded."}})",
                R"({"error":{"client_limit_exceeded":false,"upgrade_required":true,"message":"x"}})"}) {
            Mock limitMock{{{200, refusal}}};
            CHECK(runWith(limitMock, mapPath, out, err, {"provision", newKey, jwtPath.string()}) == exitConfig);
            CHECK(err == std::string(clientLimitText) + "\n");
        }
        Mock unauthorized{{{401, "{}"}}};
        CHECK(runWith(unauthorized, mapPath, out, err, {"provision", newKey, jwtPath.string()}) == exitConfig);
        // invalid answers are failures: an error, a mismatched claim, not json,
        // and a reissue (key, mapped to id2) answering another client
        std::string mismatched = Json{{"client_id", id2}, {"by_client_jwt", jwt}}.dump();
        for (const auto& [invalid, args] : std::vector<std::pair<std::string, std::vector<std::string>>>{
                 {R"({"error":{"message":"no"}})", {"provision", newKey, jwtPath.string()}},
                 {mismatched, {"provision", newKey, jwtPath.string()}},
                 {"not json", {"provision", newKey, jwtPath.string()}},
                 {answer, {"provision", key, jwtPath.string()}}}) {
            Mock bad{{{200, invalid}}};
            CHECK(runWith(bad, mapPath, out, err, args) == exitFailure);
        }
        // cap: only the given fields, null for a cleared cap
        std::string capAnswer = Json{{"client_id", id2}, {"monthly_byte_limit", 5}, {"monthly_used_byte_count", 0},
            {"total_byte_limit", nullptr}, {"capped", false}, {"capped_reason", ""}}.dump();
        Mock caps{{{200, capAnswer}, {200, capAnswer}, {200, capAnswer}}};
        CHECK(runWith(caps, mapPath, out, err, {"cap", key, "--monthly", "5"}) == exitOk);
        CHECK(out.find("\"monthly_byte_limit\":5") != std::string::npos && out.back() == '\n');
        CHECK(runWith(caps, mapPath, out, err, {"cap", key, "--total", "null", "--monthly", "0"}) == exitOk);
        CHECK(runWith(caps, mapPath, out, err, {"cap", key, "--reset-total"}) == exitOk);
        CHECK(caps.paths[0] == "/network/client-data-cap" && caps.methods[0] == "POST");
        CHECK(*caps.bodies[0] == Json({{"client_id", id2}, {"monthly_byte_limit", 5}}));
        CHECK(*caps.bodies[1] == Json({{"client_id", id2}, {"total_byte_limit", nullptr}, {"monthly_byte_limit", 0}}));
        CHECK((*caps.bodies[1])["total_byte_limit"].is_null());
        CHECK(*caps.bodies[2] == Json({{"client_id", id2}, {"reset_total", true}}));
        // invalid options are 78 and reach no API
        for (const auto& args : std::vector<std::vector<std::string>>{{"cap", key},
                 {"cap", key, "--monthly", "10GB"}, {"cap", key, "--monthly", "-1"},
                 {"cap", key, "--monthly", "9223372036854775808"}, {"cap", key, "--monthly", "1.5"},
                 {"cap", key, "--monthly", "+5"}, {"cap", key, "--monthly"},
                 {"cap", key, "--monthly", "1", "--monthly", "2"}, {"cap", key, "--weekly", "1"}}) {
            Mock none;
            CHECK(runWith(none, mapPath, out, err, args) == exitConfig && none.methods.empty());
        }
        Mock maxMock{{{200, capAnswer}}};
        CHECK(runWith(maxMock, mapPath, out, err, {"cap", key, "--total", "9223372036854775807"}) == exitOk);
        CHECK((*maxMock.bodies[0])["total_byte_limit"] == std::numeric_limits<int64_t>::max());
        // usage: the cap object, read with the root credential; a 404 is a failure
        Mock reads{{{200, capAnswer}, {404, "404 page not found"}, {200, R"({"error":{"message":"no"}})"}}};
        CHECK(runWith(reads, mapPath, out, err, {"usage", key}) == exitOk);
        CHECK(reads.methods[0] == "GET" && reads.paths[0] == "/network/client-data-cap?client_id=" + id2);
        CHECK(runWith(reads, mapPath, out, err, {"usage", key}) == exitFailure);
        CHECK(runWith(reads, mapPath, out, err, {"usage", key}) == exitFailure);
        Mock none;
        CHECK(runWith(none, mapPath, out, err, {"usage", "user:nobody"}) == exitConfig);
        // usage-all: paging, and the stop on a repeated cursor
        Mock pages{{{200, R"({"clients":[{"client_id":"a"},{"client_id":"b"}],"next_cursor":"c 1/+"})"},
            {200, R"({"clients":[{"client_id":"c"}],"next_cursor":null})"}}};
        CHECK(runWith(pages, mapPath, out, err, {"usage-all"}) == exitOk);
        CHECK(out == "{\"client_id\":\"a\"}\n{\"client_id\":\"b\"}\n{\"client_id\":\"c\"}\n");
        CHECK(pages.paths[0] == "/network/client-data-caps?limit=1000" &&
              pages.paths[1] == "/network/client-data-caps?limit=1000&cursor=c%201%2F%2B");
        Mock repeated{{{200, R"({"clients":[],"next_cursor":"x"})"},
            {200, R"({"clients":[{"client_id":"d"}],"next_cursor":"x"})"},
            {200, R"({"clients":[],"next_cursor":null})"}}};
        CHECK(runWith(repeated, mapPath, out, err, {"usage-all"}) == exitOk);
        CHECK(repeated.methods.size() == 2 && out == "{\"client_id\":\"d\"}\n");
        // remove: the mapping goes for both answers
        Mock removed{{{200, "{}"}}};
        CHECK(runWith(removed, mapPath, out, err, {"remove", key}) == exitOk);
        CHECK(out == "{\"removed\":\"" + id2 + "\"}\n");
        CHECK(removed.paths[0] == "/network/remove-client" && (*removed.bodies[0])["client_id"] == id2);
        CHECK(mappedClient(loadMap(mapPath), key).empty());
        Mock again{{{200, answer}}};
        CHECK(runWith(again, mapPath, out, err, {"provision", key, jwtPath.string()}) == exitOk);
        Mock already{{{200, R"({"error":{"message":"Client does not exist."}})"}}};
        CHECK(runWith(already, mapPath, out, err, {"remove", key}) == exitOk);
        CHECK(mappedClient(loadMap(mapPath), key).empty());
        // a map with other fields, such as the token server's, is refused
        {
            std::ofstream file(mapPath, std::ios::trunc);
            file << R"({"version":1,"clients":{},"pending_caps":[]})";
        }
        chmod(mapPath.c_str(), 0600);
        Mock refused{{{200, answer}}};
        CHECK(runWith(refused, mapPath, out, err, {"provision", key, jwtPath.string()}) == exitConfig);
        CHECK(refused.methods.empty());
        fs::remove(mapPath);
        // keys and command lines
        CHECK(std::regex_match("user:alice", keys) && std::regex_match(key, keys) &&
              !std::regex_match("user:../a", keys) && !std::regex_match(id, keys) &&
              !std::regex_match("user:", keys) && !std::regex_match("user:-a", keys));
        for (const auto& args : std::vector<std::vector<std::string>>{{"provision", "user:a"}, {"usage"},
                 {"usage-all", "x"}, {"--client-id", "x"}, {"remove", id}}) {
            Mock unused;
            CHECK(runWith(unused, mapPath, out, err, args) == exitConfig && unused.methods.empty());
        }
        // origins follow the allocators' rules
        CHECK(origin("http://127.0.0.1:1234") == "http://127.0.0.1:1234");
        CHECK(origin("https://api.bringyour.com/") == "https://api.bringyour.com");
        CHECK(origin("http://example.com").empty() && origin("https://example.com/path").empty() &&
              origin("https://u:p@example.com").empty() && origin("https://example.com?x=1").empty());
    } catch (...) {
        fs::remove_all(dir);
        throw;
    }
    fs::remove_all(dir);
    std::cout << "embed-server self-test passed\n";
}

// A setting from the environment; empty when unset.
std::string setting(const char* name) {
    const char* value = getenv(name);
    return value ? value : "";
}

int main(int argc, char** argv) {
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK)
        return exitFailure;
    std::vector<std::string> args(argv + 1, argv + argc);
    if (args == std::vector<std::string>{"--self-test"}) {
        try {
            selfTest();
        } catch (const std::exception& e) {
            std::cerr << e.what() << '\n';
            curl_global_cleanup();
            return exitFailure;
        }
        curl_global_cleanup();
        return exitOk;
    }
    std::string root = setting("URNETWORK_ROOT_JWT"), mapPath = setting("URNETWORK_CLIENT_MAP"),
                base = setting("URNETWORK_API_URL");
    std::string apiOrigin = origin(base.empty() ? "https://api.bringyour.com" : base);
    int code;
    if (args.empty()) {
        std::cerr << usageText << '\n';
        code = exitConfig;
    } else if (root.empty() || root.find_first_of(" \t\r\n") != std::string::npos) {
        std::cerr << "set URNETWORK_ROOT_JWT to the root credential\n";
        code = exitConfig;
    } else if (mapPath.empty() || mapPath[0] != '/') {
        std::cerr << "set URNETWORK_CLIENT_MAP to an absolute path in a private directory\n";
        code = exitConfig;
    } else if (apiOrigin.empty()) {
        std::cerr << "URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for local tests\n";
        code = exitConfig;
    } else {
        Tool tool{mapPath,
            [&](const std::string& method, const std::string& path, const std::optional<Json>& body) {
                return apiCall(apiOrigin, root, method, path, body);
            },
            std::cout};
        code = run(tool, std::cerr, args);
    }
    curl_global_cleanup();
    return code;
}
