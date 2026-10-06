// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. The functions are pure and
// take the C++ SDK's data types (urnetwork_sdk.hpp), which need no sdk calls,
// so the self-test checks them without the sdk library, a network or
// credentials.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <iostream>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>

#include <urnetwork_sdk.hpp>

namespace provider {

// Shown once at start, and in every example's README. The app that integrates
// a provider owns the consent screen; this example starts without asking. One
// literal per line with explicit line breaks, so the text stays exact in a
// checkout with CRLF line endings.
inline constexpr std::string_view consentDisclaimer =
    "Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.\n"
    "Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.\n"
    "This example starts providing without asking, because the consent screen belongs to your app.";

inline constexpr std::string_view providerStateStopped = "stopped";
// the platform disconnected this client for its network's client limit, and
// the sdk holds off reconnecting until the retry time
inline constexpr std::string_view providerStateClientLimit = "client limit";
inline constexpr std::string_view providerStateStarting = "starting";
inline constexpr std::string_view providerStatePaused = "paused";
inline constexpr std::string_view providerStateProviding = "providing";

// the payout wallet before the first wallet read finishes
inline constexpr std::string_view payoutWalletChecking = "checking";
// the payout wallet when the first wallet read failed
inline constexpr std::string_view payoutWalletUnavailable = "unavailable";
// the payout wallet when no wallet is mapped
inline constexpr std::string_view payoutWalletNotSet = "not set";

inline constexpr std::string_view payoutWalletScopeHotkey = "hotkey";
inline constexpr std::string_view payoutWalletScopeProvider = "this provider";
inline constexpr std::string_view payoutWalletScopeNetwork = "network";
inline constexpr std::string_view payoutWalletScopeAnotherProvider = "another provider";

// The consent_scope of a network's hotkey delegation entry in GET /sn/wallet.
// Hotkey delegations come with a later server and sdk change, which adds the
// sdk's own constant; the app only labels the entry.
inline constexpr std::string_view snWalletConsentScopeHotkey = "hotkey";

// Distinct clients are counted up to this many; beyond it the count is a lower
// bound, shown with a trailing "+".
inline constexpr std::size_t clientsServedLimit = 100 * 1000;

// the nil id, which marks an absent peer in a contract path
inline constexpr std::string_view zeroIdString = "00000000-0000-0000-0000-000000000000";

// Prints one line on stdout at once, also when stdout is a file or a pipe
// (launchd and the Windows task keep the console output in files).
inline void printLine(std::string_view line) {
    std::cout << line << std::endl;
}

// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
// value that rounds to 1024.0 moves to the next unit. The arithmetic is exact
// integer arithmetic, so the text does not depend on the C++ library's float
// formatting; a tie rounds to even, as Go's %.1f does.
inline std::string formatByteCount(int64_t byteCount) {
    static const char* const units[] = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
    if (byteCount < 1024) {
        return std::to_string(byteCount) + " B";
    }
    auto count = static_cast<uint64_t>(byteCount);
    uint64_t divisor = 1024;
    std::size_t unitIndex = 0;
    while (unitIndex + 1 < std::size(units)) {
        // the value rounds to 1024.0 or more from 1023.95 up
        uint64_t whole = count / divisor;
        uint64_t remainder = count % divisor;
        if (whole < 1023 || (whole == 1023 && 20 * remainder < 19 * divisor)) {
            break;
        }
        divisor *= 1024;
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

// The status field text: the state, and for the client limit state the time
// the sdk retries, for example "client limit, retry at 19:05 UTC". The retry
// time (unix milliseconds) is rounded up to the next whole minute in UTC, so
// the shown time is never before the real retry; a retry time of 0 shows
// "client limit". Unix time has no leap seconds, so every day is 1440
// minutes.
inline std::string providerStatusText(std::string_view state, int64_t clientLimitRetryTime) {
    if (state != providerStateClientLimit || clientLimitRetryTime <= 0) {
        return std::string(state);
    }
    const int64_t minuteMillis = 60 * 1000;
    int64_t retryMinute = clientLimitRetryTime / minuteMillis + (clientLimitRetryTime % minuteMillis != 0 ? 1 : 0);
    int minuteOfDay = static_cast<int>(retryMinute % (24 * 60));
    char clock[8];
    std::snprintf(clock, sizeof(clock), "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    return std::string(providerStateClientLimit) + ", retry at " + clock + " UTC";
}

// One status snapshot.
struct ProviderStatus {
    std::string state;
    // the end of the client limit hold in unix milliseconds, shown with the
    // client limit state; 0 when unknown
    int64_t clientLimitRetryTime = 0;
    std::size_t clientsServed = 0;
    bool clientsServedAtLimit = false;
    int64_t dataProvidedByteCount = 0;
    // a coldkey ss58 address, or payoutWalletChecking, payoutWalletUnavailable,
    // payoutWalletNotSet
    std::string payoutWallet;
    // empty unless payoutWallet is an address
    std::string payoutWalletScope;

    // The status line, for example "status: providing | clients served: 3 |
    // data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
    std::string line() const {
        std::string payoutWalletText = payoutWallet;
        if (!payoutWalletScope.empty()) {
            payoutWalletText += " (" + payoutWalletScope + ")";
        }
        return "status: " + providerStatusText(state, clientLimitRetryTime) +
            " | clients served: " + std::to_string(clientsServed) + (clientsServedAtLimit ? "+" : "") +
            " | data provided: " + formatByteCount(dataProvidedByteCount) +
            " | payout wallet: " + payoutWalletText;
    }

    // The fields that change rarely. A change prints a status line at once;
    // the data counter alone only prints on the periodic line. The status text
    // carries the client limit retry time, so a new retry time prints too.
    std::string key() const {
        return providerStatusText(state, clientLimitRetryTime) + "|" + std::to_string(clientsServed) + "|" +
            (clientsServedAtLimit ? "true" : "false") + "|" + payoutWallet + "|" + payoutWalletScope;
    }
};

// The providing state from the device getters, in this order: stopped unless
// the provide mode is public; client limit while the sdk holds this client off
// for its network's client limit; paused while paused; providing once the
// provider is enabled and its platform carrier is connected; starting
// otherwise.
inline std::string_view providerState(
    int64_t provideMode, std::string_view clientLimitStatus, bool providePaused, bool provideEnabled, bool providerConnected) {
    if (provideMode != urnet::ProvideModePublic) {
        return providerStateStopped;
    }
    if (clientLimitStatus == urnet::ClientLimitStatusExceeded) {
        return providerStateClientLimit;
    }
    if (providePaused) {
        return providerStatePaused;
    }
    if (provideEnabled && providerConnected) {
        return providerStateProviding;
    }
    return providerStateStarting;
}

// Which owner the effective payout wallet belongs to. The consent scope comes
// first: a hotkey delegation is network-level, with no client id, but is not
// the network's wallet. Otherwise by the wallet's client id: this provider's
// own mapping, the network's wallet, or another provider of the network.
inline std::string_view payoutWalletScope(
    std::string_view walletConsentScope, std::string_view walletClientId, std::string_view clientId) {
    if (walletConsentScope == snWalletConsentScopeHotkey) {
        return payoutWalletScopeHotkey;
    }
    if (walletClientId.empty()) {
        return payoutWalletScopeNetwork;
    }
    if (walletClientId == clientId) {
        return payoutWalletScopeProvider;
    }
    return payoutWalletScopeAnotherProvider;
}

// The payout wallet to show from the sdk's cached wallet: the coldkey and its
// scope label for this client, or payoutWalletNotSet with no scope when no
// wallet is mapped.
inline std::pair<std::string, std::string> payoutWalletDisplay(
    const std::optional<urnet::SnWallet>& wallet, std::string_view clientId) {
    if (!wallet || wallet->coldkey_ss58.empty()) {
        return {std::string(payoutWalletNotSet), ""};
    }
    auto scope = payoutWalletScope(
        wallet->consent_scope.value_or(""), wallet->client_id.value_or(""), clientId);
    return {wallet->coldkey_ss58, std::string(scope)};
}

// Whether a wallet read (GET /sn/wallet) succeeded: no error text, a result,
// and no error in the result.
inline bool walletReadSucceeded(
    const std::optional<urnet::SnGetWalletResult>& result, const std::optional<std::string>& error) {
    return !error && result && !result->error;
}

// Bytes relayed for clients, in both directions, since the device started; 0
// when the stats are null.
inline int64_t dataProvidedByteCount(const std::optional<urnet::PacketStats>& packetStats) {
    if (!packetStats) {
        return 0;
    }
    return packetStats->RemoteEgressByteCount + packetStats->RemoteIngressByteCount;
}

// The peer of one provider contract, by direction as the sdk's contract
// screens resolve it: the source of a receive (ingress) contract, the
// destination of a send (egress) contract. A path without that client id is
// keyed by its stream id, then by the contract id. Empty when the contract
// names no peer.
inline std::string contractPeerKey(const urnet::ContractDetails& details, bool receive) {
    auto present = [](const std::optional<std::string>& id) {
        return id && !id->empty() && *id != zeroIdString;
    };
    if (const auto& path = details.ContractTransferPath) {
        const auto& peerId = receive ? path->SourceId : path->DestinationId;
        if (present(peerId)) {
            return *peerId;
        }
        if (present(path->StreamId)) {
            return "stream:" + *path->StreamId;
        }
    }
    if (present(details.ContractId)) {
        return "contract:" + *details.ContractId;
    }
    return "";
}

// The distinct clients that held a contract with this provider since the app
// started. Safe for concurrent use: the sdk delivers contract details on its
// own threads.
class ClientsServed {
public:
    // An empty count that keeps at most limit distinct peers.
    explicit ClientsServed(std::size_t peerLimit) : limit(peerLimit) {}

    // Counts the peer of one provider contract.
    void add(const std::optional<urnet::ContractDetails>& details, bool receive) {
        if (!details) {
            return;
        }
        std::string peerKey = contractPeerKey(*details, receive);
        if (peerKey.empty()) {
            return;
        }
        std::lock_guard<std::mutex> lock(stateLock);
        if (peerKeys.count(peerKey)) {
            return;
        }
        if (limit <= peerKeys.size()) {
            atLimit = true;
            return;
        }
        peerKeys.insert(std::move(peerKey));
    }

    // The distinct count, and whether the count stopped at the limit.
    std::pair<std::size_t, bool> count() const {
        std::lock_guard<std::mutex> lock(stateLock);
        return {peerKeys.size(), atLimit};
    }

private:
    const std::size_t limit;
    mutable std::mutex stateLock;
    // contractPeerKey values
    std::unordered_set<std::string> peerKeys;
    bool atLimit = false;
};

}  // namespace provider
