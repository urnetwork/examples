// A running provider on the C++ SDK (urnetwork_sdk.hpp, a header-only facade
// over the C ABI): the network space manager, the provider device with the
// installation's identity, and the listeners that feed its status. sdk
// listeners run on sdk threads: they copy what they carry into SessionEvents,
// which they hold by shared_ptr, and hand the work to the thread that runs
// the session, which saves refreshed tokens, applies wallet reads and ends the
// run on a logout.
#pragma once

#include <chrono>
#include <condition_variable>
#include <csignal>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

#include <urnetwork_sdk.hpp>

#include "command.hpp"
#include "state.hpp"
#include "status.hpp"

namespace provider {

// How often the status is read, and the longest gap between status lines.
inline constexpr auto statusPollInterval = std::chrono::seconds(1);
inline constexpr auto statusRepeatInterval = std::chrono::seconds(60);

// How often the payout wallet is read again. The wallet is fixed; a reread
// shows a mapping that the backend completes while the provider runs.
inline constexpr auto walletSyncInterval = std::chrono::minutes(10);

// The device description and spec recorded for this installation's device.
inline constexpr const char* deviceDescription = "C++ provider example";
inline constexpr const char* deviceSpec = "urnetwork-examples/cpp-provider";

// The provider extender role's two device settings (PROVIDER_CONTRACT.md,
// "Extender role"), passed explicitly and both on. provideExtenderEnabled is
// the embedder's hard switch: false means the role never runs.
// defaultProvideExtender is the setting the device uses until the user sets
// one: false turns the default off.
inline constexpr bool provideExtenderEnabled = true;
inline constexpr bool defaultProvideExtender = true;

// Set from a signal handler or the Windows console handler; the run loop
// stops within one status poll.
inline volatile std::sig_atomic_t stopRequested = 0;

// Work that sdk listeners hand to the run loop, guarded by stateLock.
struct SessionEvents {
    std::mutex stateLock;
    // notified when work arrives, so the run loop does it at once
    std::condition_variable changed;
    // the server rejected the client credential
    bool logout = false;
    // a refreshed client jwt that is not saved yet
    std::optional<std::string> refreshedClientJwt;
    // the outcome of a wallet read that the run loop has not applied yet
    std::optional<bool> walletReadSucceeded;
    // fed by the provider contract listeners, which lock it themselves
    ClientsServed clientsServed{clientsServedLimit};
};

// Keeps closed subscriptions, and the listeners they own, until the process
// exits: a native callback that raced the close can still run a listener, so
// it must never see freed memory.
inline void retainUntilExit(std::vector<urnet::Sub>& subs) {
    static auto* retainedSubs = new std::vector<urnet::Sub>();
    for (auto& sub : subs) {
        retainedSubs->push_back(std::move(sub));
    }
    subs.clear();
}

// The key material that recreates the device's identity. Empty without an
// identity (the first run, or another client's identity): the device then
// makes a new identity, which the app saves.
inline urnet::DeviceLocalKeyMaterial providerKeyMaterial(const std::optional<ProviderIdentity>& identity) {
    if (!identity) {
        return urnet::DeviceLocalKeyMaterial{};
    }
    auto keyMaterial = urnet::newDeviceLocalKeyMaterial(
        identity->clientKeySeed.data(),
        static_cast<int32_t>(identity->clientKeySeed.size()),
        identity->provideTlsCertificatePem.data(),
        static_cast<int32_t>(identity->provideTlsCertificatePem.size()),
        identity->provideTlsPrivateKeyPem.data(),
        static_cast<int32_t>(identity->provideTlsPrivateKeyPem.size()));
    keyMaterial.setExtenderKeySeed(identity->extenderKeySeed.data(), static_cast<int32_t>(identity->extenderKeySeed.size()));
    return keyMaterial;
}

// One run of the provider. The constructor starts providing; run shows the
// status; close (also run by the destructor) stops providing. The wallet
// fields belong to the thread that runs the session.
class ProviderSession {
public:
    // Creates the provider device with the installation's identity and the
    // extender role's settings on, saves a new identity on first run, and
    // starts providing publicly. The sdk declares provide intent on the
    // device's platform connections by itself while the provide mode is
    // public.
    explicit ProviderSession(const ProviderConfig& providerConfig)
        : config(providerConfig), events(std::make_shared<SessionEvents>()),
          manager(urnet::newNetworkSpaceManagerNoStorage()) {
        try {
            urnet::NetworkSpaceKey key;
            key.host_name = "ur.network";
            key.env_name = "main";
            urnet::NetworkSpaceValues values;
            values.migration_host_name = "bringyour.com";
            space = manager.updateNetworkSpaceValues(key, values);
            if (!space) {
                throw std::runtime_error("the sdk did not create the network space");
            }
            api = space.getApi();
            api.setByJwt(config.clientJwt);
            // one call for both runs: on first run there is no identity, the
            // key material is empty and the device makes a new identity,
            // saved below; the key material handle is released after the call
            device = urnet::newDeviceLocalWithProvideExtender(
                space,
                config.clientJwt,
                deviceDescription,
                deviceSpec,
                "1",
                config.instanceId,
                false,
                providerKeyMaterial(config.identity),
                provideExtenderEnabled,
                defaultProvideExtender);
            if (!device) {
                throw std::runtime_error("the sdk did not create the device");
            }
            if (!config.identity) {
                saveNewIdentity();
            }

            // the listeners share the events, so a late callback still finds them
            subs.push_back(device.addJwtRefreshListener([sessionEvents = events](std::string clientJwt) {
                std::lock_guard<std::mutex> lock(sessionEvents->stateLock);
                sessionEvents->refreshedClientJwt = std::move(clientJwt);
                sessionEvents->changed.notify_one();
            }));
            subs.push_back(device.addAuthLogoutListener([sessionEvents = events]() {
                std::lock_guard<std::mutex> lock(sessionEvents->stateLock);
                sessionEvents->logout = true;
                sessionEvents->changed.notify_one();
            }));
            subs.push_back(device.addProviderIngressContractDetailsChangeListener(
                [sessionEvents = events](std::optional<urnet::ContractDetails> details) {
                    sessionEvents->clientsServed.add(details, true);
                }));
            subs.push_back(device.addProviderEgressContractDetailsChangeListener(
                [sessionEvents = events](std::optional<urnet::ContractDetails> details) {
                    sessionEvents->clientsServed.add(details, false);
                }));
            device.setProvideMode(urnet::ProvideModePublic);
            syncWallet();
        } catch (...) {
            for (auto& sub : subs) {
                sub.close();
            }
            retainUntilExit(subs);
            if (device) {
                device.close();
            }
            manager.close();
            throw;
        }
    }

    ProviderSession(const ProviderSession&) = delete;
    ProviderSession& operator=(const ProviderSession&) = delete;

    // Stops providing if close has not run.
    ~ProviderSession() {
        close();
    }

    // Prints status lines until a stop is requested or the server rejects the
    // credential, and returns the process exit code.
    int run() {
        std::string lastKey;
        bool printed = false;
        auto lastPrintTime = std::chrono::steady_clock::now();
        auto nextWalletSyncTime = lastPrintTime + walletSyncInterval;
        for (;;) {
            if (takeEvents()) {
                std::cerr << "the server rejected the client credential; issue a new scoped client JWT from your backend"
                          << std::endl;
                return exitConfig;
            }
            if (stopRequested) {
                return exitStopped;
            }
            ProviderStatus status = readStatus();
            auto now = std::chrono::steady_clock::now();
            std::string key = status.key();
            if (!printed || key != lastKey || statusRepeatInterval <= now - lastPrintTime) {
                printLine(status.line());
                lastKey = key;
                lastPrintTime = now;
                printed = true;
            }
            if (nextWalletSyncTime <= now) {
                syncWallet();
                nextWalletSyncTime = now + walletSyncInterval;
            }
            std::unique_lock<std::mutex> lock(events->stateLock);
            if (!events->logout && !events->refreshedClientJwt && !events->walletReadSucceeded) {
                events->changed.wait_for(lock, statusPollInterval);
            }
        }
    }

    // Stops providing and closes the subscriptions, the device and the manager;
    // the handles are released when the session is destroyed. A token refreshed
    // before the close is still saved.
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.setProvideMode(urnet::ProvideModeNone);
        for (auto& sub : subs) {
            sub.close();
        }
        retainUntilExit(subs);
        takeEvents();
        device.close();
        manager.close();
        printLine("status: stopped");
    }

private:
    // Reads the identity of a device that was created without one and saves it
    // as identity.json, so later starts present the same provider.
    void saveNewIdentity() {
        ProviderIdentity identity;
        identity.clientId = config.clientId;
        identity.clientKeySeed = device.getClientKeySeed();
        identity.provideTlsCertificatePem = device.getProvideTlsCertificatePem();
        identity.provideTlsPrivateKeyPem = device.getProvideTlsPrivateKeyPem();
        identity.extenderKeySeed = device.getExtenderKeySeed();
        if (identity.clientKeySeed.size() != 32) {
            throw std::runtime_error("the device has no provider identity to save");
        }
        try {
            saveProviderIdentity(config.stateDir, identity);
        } catch (const std::exception& e) {
            throw std::runtime_error(std::string("save ") + identityFileName + ": " + e.what());
        }
    }

    // Reads the status from the device getters and the listener state.
    ProviderStatus readStatus() {
        // "client_limit_exceeded" with the hold's end in RetryTime while the
        // platform holds this client off for its network's client limit; empty
        // only when the call cannot run, which reads as no limit
        urnet::ClientLimitStatus clientLimitStatus = device.getClientLimitStatus().value_or(urnet::ClientLimitStatus{});
        ProviderStatus status;
        // GetProviderReady also waits for processed client key registration,
        // which default device settings do not enable, so the connected
        // carrier is the readiness signal here
        status.state = std::string(providerState(
            device.getProvideMode(),
            clientLimitStatus.Status,
            device.getProvidePaused(),
            device.getProvideEnabled(),
            device.getProviderConnected()));
        status.clientLimitRetryTime = clientLimitStatus.RetryTime;
        status.dataProvidedByteCount = dataProvidedByteCount(device.getProviderPacketStats());
        std::tie(status.clientsServed, status.clientsServedAtLimit) = events->clientsServed.count();
        status.payoutWallet = payoutWallet;
        status.payoutWalletScope = payoutWalletScopeText;
        return status;
    }

    // Reads the payout wallet (GET /sn/wallet with the client credential). The
    // app only displays the wallet: the backend maps it, never the app.
    void syncWallet() {
        device.syncSnWallet([sessionEvents = events](
                                std::optional<urnet::SnGetWalletResult> result, std::optional<std::string> error) {
            bool succeeded = walletReadSucceeded(result, error);
            std::lock_guard<std::mutex> lock(sessionEvents->stateLock);
            sessionEvents->walletReadSucceeded = succeeded;
            sessionEvents->changed.notify_one();
        });
    }

    // Takes the work that listeners handed over and does it on this thread.
    // Returns whether the server rejected the credential.
    bool takeEvents() {
        std::optional<std::string> refreshedClientJwt;
        std::optional<bool> walletRead;
        bool logout = false;
        {
            std::lock_guard<std::mutex> lock(events->stateLock);
            std::swap(refreshedClientJwt, events->refreshedClientJwt);
            std::swap(walletRead, events->walletReadSucceeded);
            logout = events->logout;
        }
        if (refreshedClientJwt) {
            try {
                writePrivateFile(config.stateDir / clientJwtFileName, *refreshedClientJwt + "\n");
            } catch (const std::exception& e) {
                // never print the token itself
                std::cerr << "could not save the refreshed client credential: " << e.what() << std::endl;
            }
        }
        if (walletRead) {
            applyWalletRead(*walletRead);
        }
        return logout;
    }

    // Applies a wallet read. A failure keeps the last known wallet; after a
    // success the sdk has cached the effective wallet: this client's own
    // consent, else the network consent, else (with hotkey delegations) the
    // network's hotkey entry, else a non-consent wallet.
    void applyWalletRead(bool succeeded) {
        if (!succeeded) {
            if (payoutWallet == payoutWalletChecking) {
                payoutWallet = std::string(payoutWalletUnavailable);
            }
            return;
        }
        std::tie(payoutWallet, payoutWalletScopeText) = payoutWalletDisplay(device.getSnWallet(), config.clientId);
    }

    const ProviderConfig& config;
    std::shared_ptr<SessionEvents> events;
    // released in reverse order: the device, then the api, the space and the
    // manager
    urnet::NetworkSpaceManager manager;
    urnet::NetworkSpace space;
    urnet::Api api;
    urnet::DeviceLocal device;
    std::vector<urnet::Sub> subs;
    bool closed = false;
    // payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet or the
    // mapped coldkey
    std::string payoutWallet = std::string(payoutWalletChecking);
    std::string payoutWalletScopeText;
};

}  // namespace provider
