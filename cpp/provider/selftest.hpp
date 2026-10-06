// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It
// checks the disclaimer, the status text, the providing state, the
// clients-served count, the sdk's data types read from the contract's json
// shapes and the installation state files without a network, credentials or a
// device, and without calling the sdk: `provider --self-test` runs it in the
// app, and `make self-test` runs it in a binary that does not link the sdk
// library (selftest_main.cpp). The data is synthetic; the wallet address is
// the public Substrate development account.
#pragma once

#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include <nlohmann/json.hpp>
#include <urnetwork_sdk.hpp>

#include "command.hpp"
#include "state.hpp"
#include "status.hpp"

namespace provider {

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
// newline), published in PROVIDER_CONTRACT.md for every example to check
inline constexpr std::string_view consentDisclaimerSha256 =
    "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

// the public Substrate development account, used only as test data
inline constexpr const char* walletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";
inline constexpr const char* clientId1 = "11111111-1111-1111-1111-111111111111";
inline constexpr const char* clientId2 = "22222222-2222-2222-2222-222222222222";
inline constexpr const char* clientId3 = "33333333-3333-3333-3333-333333333333";
inline constexpr const char* streamId4 = "44444444-4444-4444-4444-444444444444";

// A failed check.
class SelfTestFailure : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Fails the self-test unless the condition holds.
inline void expect(bool condition, const std::string& failure) {
    if (!condition) {
        throw SelfTestFailure(failure);
    }
}

// The sha-256 digest of data as lowercase hex.
inline std::string sha256Hex(std::string_view data) {
    static const uint32_t roundConstants[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    uint32_t state[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    auto rotateRight = [](uint32_t word, int count) { return word >> count | word << (32 - count); };
    // the message, the 0x80 marker, zeros and the bit length fill whole blocks
    std::vector<uint8_t> message(data.begin(), data.end());
    uint64_t bitCount = static_cast<uint64_t>(data.size()) * 8;
    message.push_back(0x80);
    while (message.size() % 64 != 56) {
        message.push_back(0);
    }
    for (int i = 7; 0 <= i; i -= 1) {
        message.push_back(static_cast<uint8_t>(bitCount >> (8 * i)));
    }
    for (std::size_t offset = 0; offset < message.size(); offset += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i += 1) {
            const uint8_t* word = &message[offset + 4 * i];
            w[i] = static_cast<uint32_t>(word[0]) << 24 | static_cast<uint32_t>(word[1]) << 16 |
                static_cast<uint32_t>(word[2]) << 8 | word[3];
        }
        for (int i = 16; i < 64; i += 1) {
            uint32_t s0 = rotateRight(w[i - 15], 7) ^ rotateRight(w[i - 15], 18) ^ w[i - 15] >> 3;
            uint32_t s1 = rotateRight(w[i - 2], 17) ^ rotateRight(w[i - 2], 19) ^ w[i - 2] >> 10;
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t v[8];
        std::copy(std::begin(state), std::end(state), std::begin(v));
        for (int i = 0; i < 64; i += 1) {
            uint32_t s1 = rotateRight(v[4], 6) ^ rotateRight(v[4], 11) ^ rotateRight(v[4], 25);
            uint32_t choice = (v[4] & v[5]) ^ (~v[4] & v[6]);
            uint32_t t1 = v[7] + s1 + choice + roundConstants[i] + w[i];
            uint32_t s0 = rotateRight(v[0], 2) ^ rotateRight(v[0], 13) ^ rotateRight(v[0], 22);
            uint32_t majority = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            std::copy_backward(v, v + 7, v + 8);
            v[4] += t1;
            v[0] = t1 + s0 + majority;
        }
        for (int i = 0; i < 8; i += 1) {
            state[i] += v[i];
        }
    }
    std::string hex;
    for (uint32_t word : state) {
        char digits[9];
        std::snprintf(digits, sizeof(digits), "%08x", static_cast<unsigned>(word));
        hex += digits;
    }
    return hex;
}

// The disclaimer is the contract's exact text.
inline void checkConsentDisclaimer() {
    expect(sha256Hex("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "sha-256 of \"abc\" differs");
    expect(sha256Hex(consentDisclaimer) == consentDisclaimerSha256,
        "consent disclaimer differs from PROVIDER_CONTRACT.md");
}

// Byte counts use binary units with one decimal.
inline void checkFormatByteCount() {
    struct Case {
        int64_t byteCount;
        const char* text;
    };
    const Case cases[] = {
        {0, "0 B"},
        {1023, "1023 B"},
        {1024, "1.0 KiB"},
        {1536, "1.5 KiB"},
        {1048575, "1.0 MiB"},
        {13002342, "12.4 MiB"},
        {int64_t(5) * 1024 * 1024 * 1024, "5.0 GiB"},
        {int64_t(3) * 1024 * 1024 * 1024 * 1024, "3.0 TiB"},
        // an exact tie rounds to even, as Go's %.1f does
        {1280, "1.2 KiB"},
        {1792, "1.8 KiB"},
        {INT64_MAX, "8.0 EiB"},
    };
    for (const auto& c : cases) {
        std::string text = formatByteCount(c.byteCount);
        expect(text == c.text,
            "byte count " + std::to_string(c.byteCount) + " formats as \"" + text + "\", want \"" + c.text + "\"");
    }
}

// The client limit status names the sdk's retry time in UTC, rounded up to the
// next whole minute; other states never show it.
inline void checkStatusText() {
    struct Case {
        std::string_view state;
        int64_t clientLimitRetryTime;
        const char* text;
    };
    const Case cases[] = {
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        {providerStateClientLimit, 1791313500000, "client limit, retry at 19:05 UTC"},
        // 19:04:00.001 rounds up, so the shown time is never before the retry
        {providerStateClientLimit, 1791313440001, "client limit, retry at 19:05 UTC"},
        // no retry time
        {providerStateClientLimit, 0, "client limit"},
        // 23:59:00.001 rolls over the hour and the day
        {providerStateClientLimit, 1791331140001, "client limit, retry at 00:00 UTC"},
        {providerStateStarting, 1791313500000, "starting"},
    };
    for (const auto& c : cases) {
        std::string text = providerStatusText(c.state, c.clientLimitRetryTime);
        expect(text == c.text, "status text \"" + text + "\" for \"" + std::string(c.state) + "\" retrying at " +
            std::to_string(c.clientLimitRetryTime) + ", want \"" + c.text + "\"");
    }
}

// A status for the checks, with the fields the contract's golden lines vary.
inline ProviderStatus testStatus(std::string_view state, std::size_t clientsServed, bool clientsServedAtLimit,
    int64_t dataProvidedByteCount, std::string_view payoutWallet, std::string_view payoutWalletScope) {
    ProviderStatus status;
    status.state = std::string(state);
    status.clientsServed = clientsServed;
    status.clientsServedAtLimit = clientsServedAtLimit;
    status.dataProvidedByteCount = dataProvidedByteCount;
    status.payoutWallet = std::string(payoutWallet);
    status.payoutWalletScope = std::string(payoutWalletScope);
    return status;
}

// The status line matches the contract's golden lines.
inline void checkStatusLines() {
    ProviderStatus clientLimited = testStatus(providerStateClientLimit, 0, false, 0, walletA, payoutWalletScopeNetwork);
    clientLimited.clientLimitRetryTime = 1791313500000;
    struct Case {
        ProviderStatus status;
        std::string line;
    };
    const Case cases[] = {
        {testStatus(providerStateStarting, 0, false, 0, payoutWalletChecking, ""),
            "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking"},
        {testStatus(providerStateProviding, 3, false, 13002342, walletA, payoutWalletScopeNetwork),
            std::string("status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: ") + walletA +
                " (network)"},
        {testStatus(providerStateProviding, 3, false, 13002342, walletA, payoutWalletScopeHotkey),
            std::string("status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: ") + walletA +
                " (hotkey)"},
        {testStatus(providerStatePaused, clientsServedLimit, true, 1536, walletA, payoutWalletScopeProvider),
            std::string("status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: ") + walletA +
                " (this provider)"},
        {testStatus(providerStateStopped, 0, false, 0, payoutWalletNotSet, ""),
            "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set"},
        {clientLimited,
            std::string("status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | "
                        "payout wallet: ") + walletA + " (network)"},
    };
    for (const auto& c : cases) {
        std::string line = c.status.line();
        expect(line == c.line, "status line \"" + line + "\", want \"" + c.line + "\"");
    }
}

// A change of the status text prints a line at once, including a new client
// limit retry time; the data counter alone does not.
inline void checkStatusKey() {
    ProviderStatus status = testStatus(providerStateClientLimit, 0, false, 0, payoutWalletChecking, "");
    status.clientLimitRetryTime = 1791313500000;
    // 19:25 UTC
    ProviderStatus retried = status;
    retried.clientLimitRetryTime = 1791314700000;
    expect(status.key() != retried.key(), "a new client limit retry time does not print a status line");
    ProviderStatus counted = status;
    counted.dataProvidedByteCount = 1536;
    expect(status.key() == counted.key(), "the data counter alone prints a status line");
}

// The providing state follows the provide mode, client limit, pause, enable
// and connected rules, in that order.
inline void checkProviderState() {
    struct Case {
        int64_t provideMode;
        std::string_view clientLimitStatus;
        bool providePaused;
        bool provideEnabled;
        bool providerConnected;
        std::string_view state;
    };
    const std::string_view none = urnet::ClientLimitStatusNone;
    const std::string_view exceeded = urnet::ClientLimitStatusExceeded;
    const Case cases[] = {
        {urnet::ProvideModeNone, none, false, false, false, providerStateStopped},
        {urnet::ProvideModeNetwork, none, false, true, true, providerStateStopped},
        {urnet::ProvideModePublic, none, false, true, false, providerStateStarting},
        {urnet::ProvideModePublic, none, false, false, true, providerStateStarting},
        {urnet::ProvideModePublic, none, false, true, true, providerStateProviding},
        {urnet::ProvideModePublic, none, true, true, true, providerStatePaused},
        // the client limit comes after stopped and before every other state
        {urnet::ProvideModeNetwork, exceeded, false, true, true, providerStateStopped},
        {urnet::ProvideModePublic, exceeded, true, true, true, providerStateClientLimit},
        {urnet::ProvideModePublic, exceeded, false, false, false, providerStateClientLimit},
        {urnet::ProvideModePublic, none, true, true, true, providerStatePaused},
    };
    int index = 0;
    for (const auto& c : cases) {
        std::string_view state =
            providerState(c.provideMode, c.clientLimitStatus, c.providePaused, c.provideEnabled, c.providerConnected);
        expect(state == c.state, "provider state \"" + std::string(state) + "\" for case " + std::to_string(index) +
            ", want \"" + std::string(c.state) + "\"");
        index += 1;
    }
}

// The payout wallet is labeled by its consent scope first, then by the owner
// of its mapping.
inline void checkPayoutWalletScope() {
    struct Case {
        std::string_view walletConsentScope;
        std::string_view walletClientId;
        std::string_view scope;
    };
    const Case cases[] = {
        // a hotkey delegation is network-level but not the network's wallet
        {snWalletConsentScopeHotkey, "", payoutWalletScopeHotkey},
        {snWalletConsentScopeHotkey, clientId1, payoutWalletScopeHotkey},
        {urnet::SnWalletConsentScopeNetwork, "", payoutWalletScopeNetwork},
        {"", "", payoutWalletScopeNetwork},
        {urnet::SnWalletConsentScopeProvider, clientId1, payoutWalletScopeProvider},
        {"", clientId1, payoutWalletScopeProvider},
        {urnet::SnWalletConsentScopeProvider, clientId2, payoutWalletScopeAnotherProvider},
    };
    for (const auto& c : cases) {
        std::string_view scope = payoutWalletScope(c.walletConsentScope, c.walletClientId, clientId1);
        expect(scope == c.scope, "payout wallet scope \"" + std::string(scope) + "\" for consent scope \"" +
            std::string(c.walletConsentScope) + "\" and client \"" + std::string(c.walletClientId) + "\"");
    }
}

// A value of the sdk's data type T read from json, as the sdk returns it.
template <typename T>
T sdkValue(const char* json) {
    return nlohmann::json::parse(json).get<T>();
}

// The sdk's values in the contract's json shapes, with null and missing keys:
// the data provided, the client limit status, the payout wallet and the
// outcome of a wallet read.
inline void checkSdkValues() {
    // the top-level counters, not the ones the stats repeat per transport
    auto packetStats = sdkValue<urnet::PacketStats>(
        R"({"TransportStats": [{"TransportType": "h1", "Stats": {"RemoteEgressByteCount": 40, "RemoteIngressByteCount": 60}}], "RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7})");
    expect(dataProvidedByteCount(packetStats) == 12, "data provided reads the per-transport counters");
    expect(dataProvidedByteCount(sdkValue<urnet::PacketStats>(R"({"RemoteEgressByteCount": 5})")) == 5,
        "a missing counter does not count as 0");
    expect(dataProvidedByteCount(std::nullopt) == 0, "null provider packet stats are not 0 bytes");

    auto clientLimitStatus =
        sdkValue<urnet::ClientLimitStatus>(R"({"Status": "client_limit_exceeded", "RetryTime": 1791313500000})");
    expect(clientLimitStatus.Status == urnet::ClientLimitStatusExceeded && clientLimitStatus.RetryTime == 1791313500000,
        "the client limit status json reads differently");
    // the call could not run: read as no limit
    std::optional<urnet::ClientLimitStatus> noStatus;
    auto noLimit = noStatus.value_or(urnet::ClientLimitStatus{});
    expect(providerState(urnet::ProvideModePublic, noLimit.Status, false, true, true) == providerStateProviding,
        "a missing client limit status reads as a limit");

    struct WalletCase {
        std::optional<urnet::SnWallet> wallet;
        std::string payoutWallet;
        std::string scope;
    };
    const WalletCase walletCases[] = {
        {sdkValue<urnet::SnWallet>(R"({"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "client_id": "11111111-1111-1111-1111-111111111111", "set_at_millis": 1, "consent_scope": "provider"})"),
            walletA, std::string(payoutWalletScopeProvider)},
        {sdkValue<urnet::SnWallet>(R"({"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "client_id": null, "consent_scope": "network"})"),
            walletA, std::string(payoutWalletScopeNetwork)},
        {sdkValue<urnet::SnWallet>(R"({"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "consent_scope": "hotkey"})"),
            walletA, std::string(payoutWalletScopeHotkey)},
        {sdkValue<urnet::SnWallet>(R"({"coldkey_ss58": "", "set_at_millis": 0})"), std::string(payoutWalletNotSet), ""},
        {std::nullopt, std::string(payoutWalletNotSet), ""},
    };
    for (const auto& c : walletCases) {
        auto [payoutWallet, scope] = payoutWalletDisplay(c.wallet, clientId1);
        expect(payoutWallet == c.payoutWallet && scope == c.scope,
            "payout wallet \"" + payoutWallet + " (" + scope + ")\", want \"" + c.payoutWallet + " (" + c.scope + ")\"");
    }

    expect(walletReadSucceeded(sdkValue<urnet::SnGetWalletResult>(R"({"wallet": {"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"}})"),
               std::nullopt),
        "a wallet read failed");
    expect(walletReadSucceeded(sdkValue<urnet::SnGetWalletResult>(R"({"wallet": null, "wallets": null, "error": null})"),
               std::nullopt),
        "a wallet read without a wallet failed");
    expect(!walletReadSucceeded(sdkValue<urnet::SnGetWalletResult>(R"({"error": {"code": "server_error", "message": "down"}})"),
               std::nullopt),
        "a wallet read with an error succeeded");
    expect(!walletReadSucceeded(sdkValue<urnet::SnGetWalletResult>(R"({"wallet": null})"), std::string("request failed")),
        "a wallet read with an error text succeeded");
    expect(!walletReadSucceeded(std::nullopt, std::nullopt), "a wallet read without a result succeeded");
}

// Contract peers resolve by direction and count once per client, up to the
// limit.
inline void checkClientsServed() {
    // the contract's peer vectors, as the sdk passes contract details
    const auto receiveFromClient2 = sdkValue<urnet::ContractDetails>(
        R"({"ContractId": "55555555-5555-5555-5555-555555555555", "ContractTransferPath": {"SourceId": "22222222-2222-2222-2222-222222222222", "DestinationId": "11111111-1111-1111-1111-111111111111", "StreamId": null}, "Status": "open"})");
    const auto sendToClient2 = sdkValue<urnet::ContractDetails>(
        R"({"ContractId": "66666666-6666-6666-6666-666666666666", "ContractTransferPath": {"SourceId": "11111111-1111-1111-1111-111111111111", "DestinationId": "22222222-2222-2222-2222-222222222222", "StreamId": null}})");
    const auto receiveOnStream = sdkValue<urnet::ContractDetails>(
        R"({"ContractId": "77777777-7777-7777-7777-777777777777", "ContractTransferPath": {"SourceId": "00000000-0000-0000-0000-000000000000", "DestinationId": "11111111-1111-1111-1111-111111111111", "StreamId": "44444444-4444-4444-4444-444444444444"}})");
    struct Case {
        urnet::ContractDetails details;
        bool receive;
        std::string peerKey;
    };
    const Case cases[] = {
        {receiveFromClient2, true, clientId2},
        {sendToClient2, false, clientId2},
        {receiveOnStream, true, std::string("stream:") + streamId4},
        {sdkValue<urnet::ContractDetails>(R"({"ContractId": "88888888-8888-8888-8888-888888888888", "ContractTransferPath": null})"),
            true, "contract:88888888-8888-8888-8888-888888888888"},
        // a missing path or path id falls back the same way as a null one
        {sdkValue<urnet::ContractDetails>(R"({"ContractId": "88888888-8888-8888-8888-888888888888"})"), true,
            "contract:88888888-8888-8888-8888-888888888888"},
        {sdkValue<urnet::ContractDetails>(R"({"ContractId": "88888888-8888-8888-8888-888888888888", "ContractTransferPath": {"DestinationId": "11111111-1111-1111-1111-111111111111"}})"),
            true, "contract:88888888-8888-8888-8888-888888888888"},
        {sdkValue<urnet::ContractDetails>(R"({"ContractId": null, "ContractTransferPath": null})"), true, ""},
    };
    for (const auto& c : cases) {
        std::string peerKey = contractPeerKey(c.details, c.receive);
        expect(peerKey == c.peerKey, "contract peer key \"" + peerKey + "\", want \"" + c.peerKey + "\"");
    }

    // both directions of one client count once
    ClientsServed served(2);
    served.add(receiveFromClient2, true);
    served.add(sendToClient2, false);
    served.add(sdkValue<urnet::ContractDetails>(
                   R"({"ContractId": "99999999-9999-9999-9999-999999999999", "ContractTransferPath": {"SourceId": "22222222-2222-2222-2222-222222222222", "DestinationId": "11111111-1111-1111-1111-111111111111"}})"),
        true);
    served.add(std::nullopt, true);
    auto [count, atLimit] = served.count();
    expect(count == 1 && !atLimit, "one client counted as " + std::to_string(count));
    served.add(sdkValue<urnet::ContractDetails>(
                   R"({"ContractId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "ContractTransferPath": {"SourceId": "33333333-3333-3333-3333-333333333333", "DestinationId": "11111111-1111-1111-1111-111111111111"}})"),
        true);
    std::tie(count, atLimit) = served.count();
    expect(count == 2 && !atLimit, "two clients counted as " + std::to_string(count));
    // a third distinct peer reaches the limit of 2
    served.add(receiveOnStream, true);
    std::tie(count, atLimit) = served.count();
    expect(count == 2 && atLimit, "limited count " + std::to_string(count) + " (at limit " + std::to_string(atLimit) + ")");
}

// Base64 round trips, and malformed text is refused.
inline void checkBase64() {
    struct Case {
        std::string bytes;
        std::string text;
    };
    const Case cases[] = {
        {"", ""}, {"f", "Zg=="}, {"fo", "Zm8="}, {"foo", "Zm9v"}, {"foob", "Zm9vYg=="}, {"\xfb\xff", "+/8="},
    };
    for (const auto& c : cases) {
        std::vector<uint8_t> bytes(c.bytes.begin(), c.bytes.end());
        auto decoded = base64Decode(c.text, false);
        expect(base64Encode(bytes) == c.text && decoded && *decoded == bytes,
            "base64 \"" + c.text + "\" does not round trip");
    }
    // the url alphabet, with or without padding, as jwt segments use it
    for (const char* text : {"-_8", "-_8="}) {
        auto decoded = base64Decode(text, true);
        expect(decoded && *decoded == std::vector<uint8_t>{0xfb, 0xff},
            std::string("base64url \"") + text + "\" decodes differently");
    }
    for (const char* text : {"Zg=", "Zg", "Zm9v!", "Z===", "Zg==Zg==", "-_8="}) {
        expect(!base64Decode(text, false), std::string("invalid base64 \"") + text + "\" was accepted");
    }
    for (const char* text : {"Zm9vY", "+/8"}) {
        expect(!base64Decode(text, true), std::string("invalid base64url \"") + text + "\" was accepted");
    }
}

// Uuids are read in canonical form, and new ones are random version 4 uuids.
inline void checkUuid() {
    expect(parseUuid("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE") == std::optional<std::string>("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
        "an uppercase uuid is not read in canonical form");
    for (const char* text : {"", "not-a-uuid", "11111111-1111-1111-1111-11111111111", "11111111-1111-1111-1111-1111111111111",
             "11111111x1111-1111-1111-111111111111", "1111111g-1111-1111-1111-111111111111"}) {
        expect(!parseUuid(text), std::string("invalid uuid \"") + text + "\" was accepted");
    }
    std::string uuid = newUuid();
    std::string other = newUuid();
    expect(parseUuid(uuid) == uuid && uuid[14] == '4' && std::string_view("89ab").find(uuid[19]) != std::string_view::npos &&
            uuid != other,
        "new uuid \"" + uuid + "\" is not a random uuid");
}

// A synthetic, unsigned jwt with the given payload json.
inline std::string selfTestJwt(std::string_view payloadJson) {
    std::string payload = base64Encode(std::vector<uint8_t>(payloadJson.begin(), payloadJson.end()));
    // the url alphabet without padding
    for (char& c : payload) {
        c = c == '+' ? '-' : c == '/' ? '_' : c;
    }
    payload.erase(payload.find_last_not_of('=') + 1);
    return "e30." + payload + ".test";
}

// Only a JWT with a valid client_id claim is a client credential.
inline void checkClientJwtClaims() {
    std::string clientJwt = selfTestJwt(std::string(R"({"client_id":")") + clientId1 + R"(","network_id":")" + clientId2 + "\"}");
    expect(parseClientJwtClientId(clientJwt) == clientId1, "client jwt claim differs");
    const std::string invalidJwts[] = {
        "",
        "not-a-jwt",
        selfTestJwt(std::string(R"({"network_id":")") + clientId2 + "\"}"),
        selfTestJwt(R"({"client_id":"not-a-uuid"})"),
        "e30.%%%.test",
        "e30..test",
        selfTestJwt("[1]"),
    };
    for (const auto& invalidJwt : invalidJwts) {
        bool accepted = true;
        try {
            parseClientJwtClientId(invalidJwt);
        } catch (const ConfigError&) {
            accepted = false;
        }
        expect(!accepted, "invalid client jwt \"" + invalidJwt + "\" accepted");
    }
}

// A new private directory in the system temp directory, removed with its
// files when the guard ends.
class TempStateDir {
public:
    // Creates the directory.
    TempStateDir() : path(fs::temp_directory_path() / ("ur-provider-self-test-" + hexString(randomBytes(8)))) {
        fs::create_directory(path);
#ifndef _WIN32
        fs::permissions(path, fs::perms::owner_all);
#endif
    }

    TempStateDir(const TempStateDir&) = delete;
    TempStateDir& operator=(const TempStateDir&) = delete;

    // Removes the directory and its files.
    ~TempStateDir() {
        std::error_code error;
        fs::remove_all(path, error);
    }

    const fs::path path;
};

// Whether the call throws.
template <typename F>
bool throws(F call) {
    try {
        call();
    } catch (const std::exception&) {
        return true;
    }
    return false;
}

// State files are private, replaced atomically, created once and bound to
// their client.
inline void checkStateFiles() {
    TempStateDir stateDir;

    // an atomic private write
    fs::path path = stateDir.path / clientJwtFileName;
    writePrivateFile(path, "first\n");
    writePrivateFile(path, "second\n");
    expect(readPrivateFile(path) == std::optional<std::string>("second\n"), "private file round trip failed");
#ifndef _WIN32
    expect(fs::status(path).permissions() == (fs::perms::owner_read | fs::perms::owner_write),
        "the private file is not mode 0600");
    // a file or directory that others can read is refused, and so is a
    // symlinked file
    fs::permissions(path, fs::perms::group_read | fs::perms::others_read, fs::perm_options::add);
    expect(throws([&] { readPrivateFile(path); }), "a group-readable credential file was accepted");
    fs::permissions(path, fs::perms::owner_read | fs::perms::owner_write);
    fs::path linkPath = stateDir.path / "client.jwt.link";
    fs::create_symlink(path, linkPath);
    expect(throws([&] { readPrivateFile(linkPath); }), "a symlinked credential file was accepted");
    fs::permissions(stateDir.path, fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
        fs::perms::others_read | fs::perms::others_exec);
    bool refused = throws([&] { checkStateDir(stateDir.path); });
    fs::permissions(stateDir.path, fs::perms::owner_all);
    expect(refused, "a group-readable state directory was accepted");
#endif
    checkStateDir(stateDir.path);

    // the instance id is created once and reused
    std::string instanceId = loadOrCreateInstanceId(stateDir.path);
    expect(parseUuid(instanceId) == instanceId, "instance id \"" + instanceId + "\" is not a uuid");
    expect(loadOrCreateInstanceId(stateDir.path) == instanceId, "the instance id changed");

    // the identity belongs to its client
    ProviderIdentity identity;
    identity.clientId = clientId1;
    identity.clientKeySeed = std::vector<uint8_t>(32, 1);
    identity.provideTlsCertificatePem = std::vector<uint8_t>{'c', 'e', 'r', 't'};
    identity.provideTlsPrivateKeyPem = std::vector<uint8_t>{'k', 'e', 'y'};
    identity.extenderKeySeed = std::vector<uint8_t>{'s', 'e', 'e', 'd'};
    saveProviderIdentity(stateDir.path, identity);
    auto loaded = loadProviderIdentity(stateDir.path, clientId1);
    expect(loaded && loaded->clientId == clientId1 && loaded->clientKeySeed == identity.clientKeySeed &&
            loaded->provideTlsCertificatePem == identity.provideTlsCertificatePem &&
            loaded->provideTlsPrivateKeyPem == identity.provideTlsPrivateKeyPem &&
            loaded->extenderKeySeed == identity.extenderKeySeed,
        "identity round trip failed");
    expect(!loadProviderIdentity(stateDir.path, clientId2), "another client's identity was used");
    const std::string seed32 = base64Encode(std::vector<uint8_t>(32, 1));
    const std::string invalidIdentities[] = {
        R"({"version":1})",
        std::string(R"({"version":2,"client_id":")") + clientId1 + R"(","client_key_seed":")" + seed32 + "\"}",
        std::string(R"({"version":1,"client_id":")") + clientId1 + R"(","client_key_seed":"AQEB"})",
        std::string(R"({"version":1,"client_id":7,"client_key_seed":")") + seed32 + "\"}",
        std::string(R"({"version":1,"client_id":")") + clientId1 + R"(","client_key_seed":")" + seed32 +
            R"(","extender_key_seed":7})",
        "not json",
    };
    for (const auto& invalidIdentity : invalidIdentities) {
        writePrivateFile(stateDir.path / identityFileName, invalidIdentity);
        expect(throws([&] { loadProviderIdentity(stateDir.path, clientId1); }),
            "an invalid identity was accepted: " + invalidIdentity);
    }
}

// A missing or incomplete installation state is refused; a first run loads.
inline void checkProviderConfig() {
    expect(throws([] { loadProviderConfig(fs::path()); }), "a missing state directory was accepted");
    expect(throws([] { loadProviderConfig(fs::path("relative") / "state"); }), "a relative state directory was accepted");
    TempStateDir stateDir;
    expect(throws([&] { loadProviderConfig(stateDir.path); }), "a state directory without client.jwt was accepted");
    // a network jwt has no client_id claim
    std::string networkJwt = selfTestJwt(std::string(R"({"network_id":")") + clientId2 + "\"}");
    writePrivateFile(stateDir.path / clientJwtFileName, networkJwt + "\n");
    expect(throws([&] { loadProviderConfig(stateDir.path); }), "a network jwt was accepted");
    std::string clientJwt = selfTestJwt(std::string(R"({"client_id":")") + clientId1 + "\"}");
    writePrivateFile(stateDir.path / clientJwtFileName, clientJwt + "\n");
    ProviderConfig config = loadProviderConfig(stateDir.path);
    // without an identity the device gets empty key material and makes a new
    // identity
    expect(config.clientJwt == clientJwt && config.clientId == clientId1 && parseUuid(config.instanceId) &&
            !config.identity,
        "first-run configuration differs");
}

// The command line forms, and the usage error's exit code 78.
inline void checkUsage() {
    struct Case {
        std::vector<const char*> argv;
        Command command;
    };
    const Case cases[] = {
        {{"provider"}, Command::run},
        {{"provider", "run"}, Command::run},
        {{"provider", "--self-test"}, Command::selfTest},
        {{"provider", "--version"}, Command::version},
        {{"provider", "--unknown"}, Command::usage},
        {{"provider", "run", "--unknown"}, Command::usage},
    };
    for (const auto& c : cases) {
        expect(parseCommand(static_cast<int>(c.argv.size()), c.argv.data()) == c.command,
            "command line with " + std::to_string(c.argv.size()) + " arguments parses differently");
    }
    expect(exitUsage == 78, "usage error exit code " + std::to_string(exitUsage) + ", want 78");
}

// Runs every check; throws the first failure.
inline void runSelfTest() {
    checkConsentDisclaimer();
    checkFormatByteCount();
    checkStatusText();
    checkStatusLines();
    checkStatusKey();
    checkProviderState();
    checkPayoutWalletScope();
    checkSdkValues();
    checkClientsServed();
    checkBase64();
    checkUuid();
    checkClientJwtClaims();
    checkStateFiles();
    checkProviderConfig();
    checkUsage();
}

// Runs the self-test and prints one line: exit code 0 when it passed, 1 when
// not.
inline int selfTestMain() {
    try {
        runSelfTest();
    } catch (const std::exception& e) {
        std::cerr << "provider self-test failed: " << e.what() << std::endl;
        return exitFailure;
    }
    printLine("provider self-test passed");
    return exitStopped;
}

}  // namespace provider
