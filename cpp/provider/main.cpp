// The C++ provider example: a console app for Windows, macOS and Linux that
// runs a URnetwork provider for the developer's network and shows its status
// (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
// credential that the developer's backend issued for this installation; the
// payout wallet is mapped by the backend and is only displayed here.
//
// Usage: provider [run] | --self-test | --version. All installation state is
// in the private directory named by URNETWORK_PROVIDER_STATE_DIR (state.hpp).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
#include <csignal>
#include <cstring>
#include <exception>
#include <iostream>
#include <memory>
#include <string>

#include <urnetwork_sdk.hpp>

#include "command.hpp"
#include "selftest.hpp"
#include "session.hpp"
#include "state.hpp"
#include "status.hpp"

#ifndef _WIN32
#include <signal.h>
#endif

namespace {

#ifdef _WIN32
// Ctrl-C and Ctrl-Break stop the provider. The handler runs on its own thread;
// returning TRUE keeps the process alive until the run loop stops.
BOOL WINAPI consoleControl(DWORD control) {
    if (control != CTRL_C_EVENT && control != CTRL_BREAK_EVENT) {
        return FALSE;
    }
    provider::stopRequested = 1;
    return TRUE;
}
#else
// SIGINT and SIGTERM stop the provider.
void stopSignal(int) {
    provider::stopRequested = 1;
}
#endif

// Routes Ctrl-C (and SIGTERM on POSIX) to a requested stop, exit code 0.
bool handleStopRequests() {
#ifdef _WIN32
    return SetConsoleCtrlHandler(consoleControl, TRUE) != 0;
#else
    struct sigaction action;
    std::memset(&action, 0, sizeof(action));
    action.sa_handler = stopSignal;
    sigemptyset(&action.sa_mask);
    // the go runtime in the sdk library needs handlers that run on the
    // alternate signal stack
    action.sa_flags = SA_ONSTACK;
    return sigaction(SIGINT, &action, nullptr) == 0 && sigaction(SIGTERM, &action, nullptr) == 0;
#endif
}

// Keeps the sdk's log files in logDir, which the sdk bounds (16 MiB files, the
// newest four kept at each start), instead of the system temp directory. The
// sdk still copies its log lines to stderr.
void configureSdkLogs(const provider::fs::path& logDir) {
    provider::makePrivateDir(logDir);
    urnet::setLogDir(provider::utf8Path(logDir));
}

// Runs the provider until a stop is requested or the credential is rejected,
// and returns the exit code.
int runProvider() {
    provider::printLine(provider::consentDisclaimer);
    provider::ProviderConfig config;
    try {
        config = provider::loadProviderConfig(provider::environmentStateDir());
    } catch (const std::exception& e) {
        std::cerr << e.what() << std::endl;
        return provider::exitConfig;
    }
    try {
        configureSdkLogs(config.stateDir / "logs");
    } catch (const std::exception& e) {
        std::cerr << "could not set the sdk log directory: " << e.what() << std::endl;
        return provider::exitFailure;
    }
    if (!handleStopRequests()) {
        std::cerr << "could not handle stop signals" << std::endl;
        return provider::exitFailure;
    }
    std::unique_ptr<provider::ProviderSession> session;
    try {
        session = std::make_unique<provider::ProviderSession>(config);
    } catch (const std::exception& e) {
        std::cerr << "could not start the provider: " << e.what() << std::endl;
        return provider::exitFailure;
    }
    provider::printLine("provider client " + config.clientId + ", instance " + config.instanceId);
    try {
        int exitCode = session->run();
        session->close();
        return exitCode;
    } catch (const std::exception& e) {
        // the session's destructor stops providing
        std::cerr << "the provider failed: " << e.what() << std::endl;
        return provider::exitFailure;
    }
}

}  // namespace

// Runs one command and returns its exit code.
int main(int argc, char** argv) {
    switch (provider::parseCommand(argc, argv)) {
    case provider::Command::selfTest:
        return provider::selfTestMain();
    case provider::Command::version:
        provider::printLine(urnet::version());
        return provider::exitStopped;
    case provider::Command::usage:
        std::cerr << provider::usageText << std::endl;
        return provider::exitUsage;
    case provider::Command::run:
        break;
    }
    return runProvider();
}
