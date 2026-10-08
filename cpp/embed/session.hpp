// A running embed app on the C++ SDK (urnetwork_sdk.hpp, a header-only
// facade over the C ABI): the network space manager, the local device with
// this installation's client JWT, and the listeners that feed its status. The
// device carries only the app's own traffic; it does not provide.
//
// sdk listeners run on sdk threads and the cap reads on their own threads:
// they copy what they carry into SessionEvents, which they hold by
// shared_ptr, and hand the work to the thread that runs the session, which
// saves refreshed tokens, applies cap readings and ends the run on a logout.
#pragma once

#include <chrono>
#include <condition_variable>
#include <csignal>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <urnetwork_sdk.hpp>

#include "command.hpp"
#include "fetch.hpp"
#include "http_curl.hpp"
#include "state.hpp"
#include "status.hpp"

namespace embed {

// How often the status is read, and the longest gap between status lines.
inline constexpr auto statusPollInterval = std::chrono::seconds(1);
inline constexpr auto statusRepeatInterval = std::chrono::seconds(60);

// How often the caps are read. A contract status change reads them at the next
// pass of the run loop, well within the contract's 5 seconds; changes during a
// read add one more read.
inline constexpr auto capReadInterval = std::chrono::minutes(5);

// The device description and spec recorded for this installation's device.
inline constexpr const char* deviceDescription = "C++ embed example";
inline constexpr const char* deviceSpec = "urnetwork-examples/cpp-embed";

// Set from a signal handler or the Windows console handler; the run loop
// stops within one status poll.
inline volatile std::sig_atomic_t stopRequested = 0;

// Work that sdk listeners and the cap reader hand to the run loop, guarded by
// stateLock.
struct SessionEvents {
    std::mutex stateLock;
    // notified when work arrives, so the run loop does it at once
    std::condition_variable changed;
    // the server rejected the client credential
    bool logout = false;
    // a refreshed client jwt that is not saved yet
    std::optional<std::string> refreshedClientJwt;
    // a contract status changed: read the caps again soon
    bool contractStatusChanged = false;
    // a finished cap read that the run loop has not applied yet: the reading,
    // empty for a failure, with the failure's reason
    bool capReadDone = false;
    std::optional<Cap> capReading;
    std::string capReadError;

    // Whether work is waiting; the caller holds stateLock.
    bool pendingWithLock() const {
        return logout || refreshedClientJwt || contractStatusChanged || capReadDone;
    }
};

// The work taken from SessionEvents in one wake of the run loop.
struct EventWork {
    bool logout = false;
    std::optional<std::string> refreshedClientJwt;
    bool contractStatusChanged = false;
    bool capReadDone = false;
    std::optional<Cap> capReading;
    std::string capReadError;
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

// One run of the app. The constructor starts the device; run shows the
// status; close (also run by the destructor) closes it.
class EmbedSession {
public:
    // Creates the local device with the installation's client JWT and instance
    // id, adds the listeners and sets the destination of the app's traffic.
    // The provide mode stays at its default: an embed app does not provide.
    explicit EmbedSession(Config& embedConfig)
        : config(embedConfig), events(std::make_shared<SessionEvents>()),
          manager(urnet::newNetworkSpaceManagerNoStorage()) {
        caps.apply(config.firstCap);
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
            device = urnet::newDeviceLocalWithDefaults(
                space, config.clientJwt, deviceDescription, deviceSpec, "1", config.instanceId, false);
            if (!device) {
                throw std::runtime_error("the sdk did not create the device");
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
            subs.push_back(device.addContractStatusChangeListener(
                [sessionEvents = events](std::optional<urnet::ContractStatus>) {
                    std::lock_guard<std::mutex> lock(sessionEvents->stateLock);
                    sessionEvents->contractStatusChanged = true;
                    sessionEvents->changed.notify_one();
                }));
            // the destination of the app's own traffic: the best available location
            urnet::ConnectLocationId bestAvailable;
            bestAvailable.best_available = true;
            urnet::ConnectLocation location;
            location.connect_location_id = bestAvailable;
            device.setConnectLocation(location);
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

    EmbedSession(const EmbedSession&) = delete;
    EmbedSession& operator=(const EmbedSession&) = delete;

    // Closes the device if close has not run.
    ~EmbedSession() {
        close();
    }

    // Prints status lines until a stop is requested or the server rejects the
    // credential, and returns the process exit code. The caps are read at
    // start (unless the token server's data_cap was the first reading), every
    // 5 minutes and within 5 seconds of a contract status change.
    int run() {
        std::string lastLine;
        bool printed = false;
        auto lastPrintTime = std::chrono::steady_clock::now();
        auto nextCapRead = lastPrintTime + (config.firstCap ? capReadInterval : std::chrono::minutes(0));
        bool contractCapReadPending = false;
        bool capReadRunning = false;
        bool capReadFailing = false;
        for (;;) {
            EventWork work = takeEvents();
            if (work.refreshedClientJwt) {
                saveRefreshedClientJwt(*work.refreshedClientJwt);
            }
            if (work.logout) {
                std::cerr << "the server rejected the client credential; sign in again to obtain a new client JWT from "
                             "your backend"
                          << std::endl;
                return exitConfig;
            }
            if (stopRequested) {
                return exitStopped;
            }
            auto now = std::chrono::steady_clock::now();
            if (work.capReadDone) {
                capReadRunning = false;
                caps.apply(work.capReading);
                if (!work.capReading && !capReadFailing) {
                    std::cerr << "could not read the data caps: " << work.capReadError << std::endl;
                }
                capReadFailing = !work.capReading;
            }
            if (work.contractStatusChanged) {
                contractCapReadPending = true;
            }
            if (!capReadRunning && (nextCapRead <= now || contractCapReadPending)) {
                capReadRunning = startCapRead();
                if (!capReadRunning) {
                    caps.apply(std::nullopt);
                }
                nextCapRead = now + capReadInterval;
                contractCapReadPending = false;
            }
            std::string line = statusLine(readStatus());
            if (!printed || line != lastLine || statusRepeatInterval <= now - lastPrintTime) {
                printLine(line);
                lastLine = line;
                lastPrintTime = now;
                printed = true;
            }
            std::unique_lock<std::mutex> lock(events->stateLock);
            if (!events->pendingWithLock()) {
                events->changed.wait_for(lock, statusPollInterval);
            }
        }
    }

    // Closes the subscriptions, the device and the manager; the handles are
    // released when the session is destroyed. A token refreshed before the
    // close is still saved.
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (auto& sub : subs) {
            sub.close();
        }
        retainUntilExit(subs);
        EventWork work = takeEvents();
        if (work.refreshedClientJwt) {
            saveRefreshedClientJwt(*work.refreshedClientJwt);
        }
        device.close();
        manager.close();
    }

private:
    // Starts a cap read on its own thread, so a slow answer never holds up the
    // status. False when the thread could not start.
    bool startCapRead() {
        try {
            std::thread([sessionEvents = events, apiUrl = config.apiUrl, clientJwt = config.clientJwt]() {
                std::string error;
                auto reading = readCaps(curlHttp, apiUrl, clientJwt, error);
                std::lock_guard<std::mutex> lock(sessionEvents->stateLock);
                sessionEvents->capReadDone = true;
                sessionEvents->capReading = reading;
                sessionEvents->capReadError = error;
                sessionEvents->changed.notify_one();
            }).detach();
            return true;
        } catch (const std::system_error&) {
            return false;
        }
    }

    // Reads the status inputs from the device getters and the caps.
    StatusInput readStatus() {
        StatusInput input;
        // "client_limit_exceeded" with the hold's end in RetryTime while the
        // platform holds this client off for its network's concurrent client
        // limit; empty only when the call cannot run, which reads as no limit
        urnet::ClientLimitStatus clientLimitStatus = device.getClientLimitStatus().value_or(urnet::ClientLimitStatus{});
        input.clientLimitStatus = clientLimitStatus.Status;
        input.clientLimitRetryTime = clientLimitStatus.RetryTime;
        // empty before the window exists
        if (auto windowStatus = device.getWindowStatus()) {
            input.providersAdded = windowStatus->ProviderStateAdded;
        }
        input.caps = caps;
        return input;
    }

    // Takes the work that listeners and the cap reader handed over.
    EventWork takeEvents() {
        std::lock_guard<std::mutex> lock(events->stateLock);
        EventWork work;
        work.logout = events->logout;
        std::swap(work.refreshedClientJwt, events->refreshedClientJwt);
        std::swap(work.contractStatusChanged, events->contractStatusChanged);
        std::swap(work.capReadDone, events->capReadDone);
        std::swap(work.capReading, events->capReading);
        std::swap(work.capReadError, events->capReadError);
        return work;
    }

    // Keeps a refreshed credential, so later cap reads and the next start use
    // a valid token. The token itself is never printed.
    void saveRefreshedClientJwt(const std::string& clientJwt) {
        try {
            saveClientJwt(config.stateDir, clientJwt);
        } catch (const std::exception& e) {
            std::cerr << "could not save the refreshed client credential: " << e.what() << std::endl;
        }
        config.clientJwt = clientJwt;
    }

    Config& config;
    std::shared_ptr<SessionEvents> events;
    // released in reverse order: the device, then the api, the space and the
    // manager
    urnet::NetworkSpaceManager manager;
    urnet::NetworkSpace space;
    urnet::Api api;
    urnet::DeviceLocal device;
    std::vector<urnet::Sub> subs;
    Caps caps;
    bool closed = false;
};

}  // namespace embed
