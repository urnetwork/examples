// Installation state for one provider install, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"):
//
//   client.jwt     the scoped client credential that the developer's backend
//                  issued for this installation; the developer writes it and
//                  the app rewrites it whenever the sdk refreshes the token
//   instance-id    this installation's uuid, created on first run
//   identity.json  the provider identity (client key seed, provide tls
//                  certificate and key, extender seed), created on first run
//                  so the provider keeps one identity across restarts
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others, and a
// symlinked file is refused; Windows relies on the access control of the
// user's profile directory. The json files use nlohmann/json; base64, uuids
// and the jwt payload are decoded here by hand.
#pragma once

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <optional>
#include <random>
#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>
#include <vector>

#include <nlohmann/json.hpp>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace provider {

namespace fs = std::filesystem;

inline constexpr const char* clientJwtFileName = "client.jwt";
inline constexpr const char* instanceIdFileName = "instance-id";
inline constexpr const char* identityFileName = "identity.json";

// the largest state file the app reads
inline constexpr std::uintmax_t stateFileByteLimit = 64 * 1024;

inline constexpr int providerIdentityVersion = 1;

// A configuration or credential problem that restarting does not fix (exit
// code 78).
class ConfigError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// identity.json. Byte fields are standard base64 in json. An identity belongs
// to one client: a newly provisioned client gets a new identity.
struct ProviderIdentity {
    int version = providerIdentityVersion;
    std::string clientId;
    std::vector<uint8_t> clientKeySeed;
    std::vector<uint8_t> provideTlsCertificatePem;
    std::vector<uint8_t> provideTlsPrivateKeyPem;
    std::vector<uint8_t> extenderKeySeed;
};

// The installation state loaded at start.
struct ProviderConfig {
    fs::path stateDir;
    std::string clientJwt;
    // the client_id claim of clientJwt
    std::string clientId;
    std::string instanceId;
    // empty on first run, and when the stored identity belongs to another
    // client: the device then makes a new identity, which the app saves
    std::optional<ProviderIdentity> identity;
};

inline constexpr std::string_view standardAlphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
inline constexpr std::string_view urlAlphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

// Standard base64 with padding, as Go's encoding/json writes byte fields.
inline std::string base64Encode(const std::vector<uint8_t>& bytes) {
    std::string text;
    for (std::size_t i = 0; i < bytes.size(); i += 3) {
        uint32_t group = static_cast<uint32_t>(bytes[i]) << 16;
        if (i + 1 < bytes.size()) {
            group |= static_cast<uint32_t>(bytes[i + 1]) << 8;
        }
        if (i + 2 < bytes.size()) {
            group |= bytes[i + 2];
        }
        text += standardAlphabet[group >> 18 & 63];
        text += standardAlphabet[group >> 12 & 63];
        text += i + 1 < bytes.size() ? standardAlphabet[group >> 6 & 63] : '=';
        text += i + 2 < bytes.size() ? standardAlphabet[group & 63] : '=';
    }
    return text;
}

// Decodes standard base64 with padding, or with url the url alphabet without
// padding as jwt segments use it (trailing "=" are ignored there, as the Go
// reference trims them). Empty for any other text.
inline std::optional<std::vector<uint8_t>> base64Decode(std::string_view text, bool url) {
    std::string_view alphabet = url ? urlAlphabet : standardAlphabet;
    std::size_t symbolCount = text.size();
    if (url) {
        while (0 < symbolCount && text[symbolCount - 1] == '=') {
            symbolCount -= 1;
        }
        if (symbolCount % 4 == 1) {
            return std::nullopt;
        }
    } else {
        if (text.size() % 4 != 0) {
            return std::nullopt;
        }
        for (int padding = 0; padding < 2 && 0 < symbolCount && text[symbolCount - 1] == '='; padding += 1) {
            symbolCount -= 1;
        }
        if (symbolCount % 4 == 1) {
            return std::nullopt;
        }
    }
    std::vector<uint8_t> bytes;
    uint32_t group = 0;
    for (std::size_t i = 0; i < symbolCount; i += 1) {
        std::size_t value = alphabet.find(text[i]);
        if (value == std::string_view::npos) {
            return std::nullopt;
        }
        group = group << 6 | static_cast<uint32_t>(value);
        if (i % 4 == 3) {
            bytes.push_back(static_cast<uint8_t>(group >> 16));
            bytes.push_back(static_cast<uint8_t>(group >> 8));
            bytes.push_back(static_cast<uint8_t>(group));
            group = 0;
        }
    }
    // a partial group of 2 or 3 symbols carries 1 or 2 bytes
    if (symbolCount % 4 == 2) {
        bytes.push_back(static_cast<uint8_t>(group >> 4));
    } else if (symbolCount % 4 == 3) {
        bytes.push_back(static_cast<uint8_t>(group >> 10));
        bytes.push_back(static_cast<uint8_t>(group >> 2));
    }
    return bytes;
}

// Reads a uuid in its 8-4-4-4-12 hex form, in either case, into its canonical
// lowercase form.
inline std::optional<std::string> parseUuid(std::string_view text) {
    if (text.size() != 36) {
        return std::nullopt;
    }
    std::string uuid(text);
    for (std::size_t i = 0; i < uuid.size(); i += 1) {
        bool dash = i == 8 || i == 13 || i == 18 || i == 23;
        auto c = static_cast<unsigned char>(uuid[i]);
        if (dash ? c != '-' : !std::isxdigit(c)) {
            return std::nullopt;
        }
        uuid[i] = static_cast<char>(std::tolower(c));
    }
    return uuid;
}

// Random bytes from the system's random device.
inline std::vector<uint8_t> randomBytes(std::size_t count) {
    std::random_device random;
    std::vector<uint8_t> bytes(count);
    for (auto& byte : bytes) {
        byte = static_cast<uint8_t>(random() & 0xff);
    }
    return bytes;
}

// Lowercase hex of bytes.
inline std::string hexString(const std::vector<uint8_t>& bytes) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string text;
    for (uint8_t byte : bytes) {
        text += digits[byte >> 4];
        text += digits[byte & 15];
    }
    return text;
}

// A new random (version 4) uuid.
inline std::string newUuid() {
    std::vector<uint8_t> bytes = randomBytes(16);
    bytes[6] = static_cast<uint8_t>((bytes[6] & 0x0f) | 0x40);
    bytes[8] = static_cast<uint8_t>((bytes[8] & 0x3f) | 0x80);
    std::string hex = hexString(bytes);
    return hex.substr(0, 8) + "-" + hex.substr(8, 4) + "-" + hex.substr(12, 4) + "-" + hex.substr(16, 4) + "-" +
        hex.substr(20);
}

// A path as the utf-8 text the sdk takes.
inline std::string utf8Path(const fs::path& path) {
#if defined(__cpp_char8_t)
    auto text = path.u8string();
    return std::string(text.begin(), text.end());
#else
    return path.u8string();
#endif
}

// The state directory from URNETWORK_PROVIDER_STATE_DIR; empty when it is not
// set.
inline fs::path environmentStateDir() {
#ifdef _WIN32
    const wchar_t* value = _wgetenv(L"URNETWORK_PROVIDER_STATE_DIR");
#else
    const char* value = std::getenv("URNETWORK_PROVIDER_STATE_DIR");
#endif
    return value ? fs::path(value) : fs::path();
}

// Whether a file or directory is open to group or others. Windows relies on
// the access control of the user's profile directory.
inline bool sharedWithOthers(const fs::file_status& status) {
#ifdef _WIN32
    (void)status;
    return false;
#else
    return (status.permissions() & (fs::perms::group_all | fs::perms::others_all)) != fs::perms::none;
#endif
}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
inline void checkStateDir(const fs::path& stateDir) {
    if (stateDir.empty()) {
        throw ConfigError("set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory");
    }
    if (!stateDir.is_absolute()) {
        throw ConfigError("URNETWORK_PROVIDER_STATE_DIR must be an absolute path");
    }
    std::error_code error;
    fs::file_status status = fs::status(stateDir, error);
    if (status.type() == fs::file_type::not_found) {
        throw ConfigError("state directory: " + std::make_error_code(std::errc::no_such_file_or_directory).message());
    }
    if (error) {
        throw ConfigError("state directory: " + error.message());
    }
    if (!fs::is_directory(status)) {
        throw ConfigError("URNETWORK_PROVIDER_STATE_DIR is not a directory");
    }
    if (sharedWithOthers(status)) {
        throw ConfigError("the state directory must be private to its owner (chmod 700)");
    }
}

// Reads a regular, private state file of bounded size; empty when the file
// does not exist. A symlink is refused so that the credential cannot be
// redirected to another file.
inline std::optional<std::string> readPrivateFile(const fs::path& path) {
    std::error_code error;
    fs::file_status status = fs::symlink_status(path, error);
    if (status.type() == fs::file_type::not_found) {
        return std::nullopt;
    }
    if (error) {
        throw std::runtime_error(error.message());
    }
    if (!fs::is_regular_file(status)) {
        throw std::runtime_error("not a regular file");
    }
    if (stateFileByteLimit < fs::file_size(path, error) || error) {
        throw std::runtime_error(error ? error.message() : "file is too large");
    }
    if (sharedWithOthers(status)) {
        throw std::runtime_error("file must be private to its owner (chmod 600)");
    }
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        throw std::runtime_error("cannot open the file");
    }
    std::string data;
    data.resize(stateFileByteLimit + 1);
    file.read(data.data(), static_cast<std::streamsize>(data.size()));
    if (file.bad()) {
        throw std::runtime_error("cannot read the file");
    }
    data.resize(static_cast<std::size_t>(file.gcount()));
    if (stateFileByteLimit < data.size()) {
        throw std::runtime_error("file is too large");
    }
    return data;
}

// Replaces a state file atomically: a private temporary file in the same
// directory is written, synced and renamed over the old file.
inline void writePrivateFile(const fs::path& path, std::string_view data) {
#ifdef _WIN32
    fs::path tempPath = path.parent_path() / (L"." + path.filename().wstring() + L"." +
        fs::path(hexString(randomBytes(8))).wstring());
    HANDLE file = CreateFileW(tempPath.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        throw std::system_error(static_cast<int>(GetLastError()), std::system_category());
    }
    // the first failure's code; ERROR_SUCCESS while every step succeeds
    DWORD code = ERROR_SUCCESS;
    for (std::size_t offset = 0; code == ERROR_SUCCESS && offset < data.size();) {
        DWORD count = 0;
        auto chunk = static_cast<DWORD>(std::min<std::size_t>(data.size() - offset, 1024 * 1024));
        if (!WriteFile(file, data.data() + offset, chunk, &count, nullptr)) {
            code = GetLastError();
        } else if (count == 0) {
            code = ERROR_WRITE_FAULT;
        }
        offset += count;
    }
    if (code == ERROR_SUCCESS && !FlushFileBuffers(file)) {
        code = GetLastError();
    }
    CloseHandle(file);
    if (code == ERROR_SUCCESS &&
        !MoveFileExW(tempPath.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        code = GetLastError();
    }
    if (code != ERROR_SUCCESS) {
        DeleteFileW(tempPath.c_str());
        throw std::system_error(static_cast<int>(code), std::system_category());
    }
#else
    std::string tempPath = (path.parent_path() / ("." + path.filename().string() + ".XXXXXX")).string();
    // mkstemp creates the file with mode 0600
    int fd = mkstemp(tempPath.data());
    if (fd < 0) {
        throw std::system_error(errno, std::generic_category());
    }
    bool written = fchmod(fd, 0600) == 0;
    for (std::size_t offset = 0; written && offset < data.size();) {
        ssize_t count = write(fd, data.data() + offset, data.size() - offset);
        if (count < 0 && errno == EINTR) {
            continue;
        }
        written = 0 < count;
        if (written) {
            offset += static_cast<std::size_t>(count);
        }
    }
    written = written && fsync(fd) == 0;
    int errorNumber = errno;
    if (close(fd) != 0 && written) {
        written = false;
        errorNumber = errno;
    }
    if (written && std::rename(tempPath.c_str(), path.c_str()) != 0) {
        written = false;
        errorNumber = errno;
    }
    if (!written) {
        unlink(tempPath.c_str());
        throw std::system_error(errorNumber, std::generic_category());
    }
#endif
}

// Creates a directory private to its owner; an existing one is kept.
inline void makePrivateDir(const fs::path& path) {
#ifdef _WIN32
    std::error_code error;
    fs::create_directory(path, error);
    if (error) {
        throw std::system_error(error);
    }
#else
    if (mkdir(path.c_str(), 0700) != 0 && errno != EEXIST) {
        throw std::system_error(errno, std::generic_category());
    }
#endif
}

// The client_id claim of a scoped client JWT, in canonical form. This checks
// the token's shape and claim only; the sdk and the server verify the token
// itself.
inline std::string parseClientJwtClientId(std::string_view clientJwt) {
    const char* notJwt = "client.jwt does not hold a JWT; write the scoped client JWT from your backend";
    // three non-empty parts separated by dots
    std::size_t firstDot = clientJwt.find('.');
    std::size_t secondDot = firstDot == std::string_view::npos ? firstDot : clientJwt.find('.', firstDot + 1);
    if (secondDot == std::string_view::npos || clientJwt.find('.', secondDot + 1) != std::string_view::npos ||
        firstDot == 0 || secondDot == firstDot + 1 || secondDot + 1 == clientJwt.size()) {
        throw ConfigError(notJwt);
    }
    auto payload = base64Decode(clientJwt.substr(firstDot + 1, secondDot - firstDot - 1), true);
    if (!payload) {
        throw ConfigError(notJwt);
    }
    auto claims = nlohmann::json::parse(payload->begin(), payload->end(), nullptr, false);
    auto claim = claims.is_object() ? claims.find("client_id") : claims.end();
    if (claim == claims.end() || !claim->is_string() || claim->get<std::string>().empty()) {
        throw ConfigError("client.jwt has no client_id claim; write a scoped client JWT, not a network JWT");
    }
    auto clientId = parseUuid(claim->get<std::string>());
    if (!clientId) {
        throw ConfigError("client.jwt has an invalid client_id claim");
    }
    return *clientId;
}

// The text without the whitespace around it.
inline std::string_view trimSpace(std::string_view text) {
    auto space = [](char c) { return std::isspace(static_cast<unsigned char>(c)) != 0; };
    while (!text.empty() && space(text.front())) {
        text.remove_prefix(1);
    }
    while (!text.empty() && space(text.back())) {
        text.remove_suffix(1);
    }
    return text;
}

// Reads instance-id, creating it on first run. An installation keeps one
// instance id for its lifetime.
inline std::string loadOrCreateInstanceId(const fs::path& stateDir) {
    fs::path path = stateDir / instanceIdFileName;
    std::optional<std::string> data;
    try {
        data = readPrivateFile(path);
    } catch (const std::exception& e) {
        throw ConfigError(std::string("read ") + instanceIdFileName + " from the state directory: " + e.what());
    }
    if (data) {
        auto instanceId = parseUuid(trimSpace(*data));
        if (!instanceId) {
            throw ConfigError(std::string(instanceIdFileName) + " does not hold a UUID");
        }
        return *instanceId;
    }
    std::string instanceId = newUuid();
    try {
        writePrivateFile(path, instanceId + "\n");
    } catch (const std::exception& e) {
        throw ConfigError(std::string("write ") + instanceIdFileName + ": " + e.what());
    }
    return instanceId;
}

// Reads identity.json for clientId. A missing file, or an identity of another
// client, returns empty: the device then creates a new identity, which the
// app saves. A file that does not parse, has another version or a seed that
// is not 32 bytes is an error.
inline std::optional<ProviderIdentity> loadProviderIdentity(const fs::path& stateDir, std::string_view clientId) {
    std::optional<std::string> data;
    try {
        data = readPrivateFile(stateDir / identityFileName);
    } catch (const std::exception& e) {
        throw ConfigError(std::string("read ") + identityFileName + " from the state directory: " + e.what());
    }
    if (!data) {
        return std::nullopt;
    }
    const ConfigError invalid(std::string(identityFileName) +
        " is not a valid provider identity; remove it to create a new one");
    auto json = nlohmann::json::parse(*data, nullptr, false);
    if (!json.is_object()) {
        throw invalid;
    }
    // a byte field: standard base64, or empty when null or absent
    auto bytes = [&](const char* name) {
        auto field = json.find(name);
        if (field == json.end() || field->is_null()) {
            return std::vector<uint8_t>();
        }
        auto decoded = field->is_string() ? base64Decode(field->get<std::string>(), false) : std::nullopt;
        if (!decoded) {
            throw invalid;
        }
        return *decoded;
    };
    auto version = json.find("version");
    if (version == json.end() || !version->is_number_integer() || version->get<int64_t>() != providerIdentityVersion) {
        throw invalid;
    }
    std::string storedClientId;
    if (auto field = json.find("client_id"); field != json.end() && !field->is_null()) {
        if (!field->is_string()) {
            throw invalid;
        }
        storedClientId = field->get<std::string>();
    }
    ProviderIdentity identity;
    identity.clientKeySeed = bytes("client_key_seed");
    if (identity.clientKeySeed.size() != 32) {
        throw invalid;
    }
    identity.provideTlsCertificatePem = bytes("provide_tls_certificate_pem");
    identity.provideTlsPrivateKeyPem = bytes("provide_tls_private_key_pem");
    identity.extenderKeySeed = bytes("extender_key_seed");
    if (storedClientId != clientId) {
        return std::nullopt;
    }
    identity.clientId = storedClientId;
    return identity;
}

// Writes identity.json: null for an empty field, as Go's encoding/json writes
// a nil slice, and no extender seed when it is empty.
inline void saveProviderIdentity(const fs::path& stateDir, const ProviderIdentity& identity) {
    auto bytes = [](const std::vector<uint8_t>& value) {
        return value.empty() ? nlohmann::ordered_json() : nlohmann::ordered_json(base64Encode(value));
    };
    nlohmann::ordered_json json;
    json["version"] = identity.version;
    json["client_id"] = identity.clientId;
    json["client_key_seed"] = bytes(identity.clientKeySeed);
    json["provide_tls_certificate_pem"] = bytes(identity.provideTlsCertificatePem);
    json["provide_tls_private_key_pem"] = bytes(identity.provideTlsPrivateKeyPem);
    if (!identity.extenderKeySeed.empty()) {
        json["extender_key_seed"] = bytes(identity.extenderKeySeed);
    }
    writePrivateFile(stateDir / identityFileName, json.dump());
}

// Loads the installation state, creating instance-id on first run. Every error
// is a ConfigError: restarting does not fix it.
inline ProviderConfig loadProviderConfig(const fs::path& stateDir) {
    checkStateDir(stateDir);
    std::optional<std::string> data;
    try {
        data = readPrivateFile(stateDir / clientJwtFileName);
        if (!data) {
            throw std::runtime_error(std::make_error_code(std::errc::no_such_file_or_directory).message());
        }
    } catch (const std::exception& e) {
        throw ConfigError(std::string("read ") + clientJwtFileName + " from the state directory: " + e.what());
    }
    ProviderConfig config;
    config.stateDir = stateDir;
    config.clientJwt = std::string(trimSpace(*data));
    // a NUL byte would cut the token short where the sdk reads it as a C string
    if (config.clientJwt.find('\0') != std::string::npos) {
        throw ConfigError("client.jwt does not hold a JWT; write the scoped client JWT from your backend");
    }
    config.clientId = parseClientJwtClientId(config.clientJwt);
    config.instanceId = loadOrCreateInstanceId(stateDir);
    config.identity = loadProviderIdentity(stateDir, config.clientId);
    return config;
}

}  // namespace provider
