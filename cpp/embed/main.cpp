// The C++ embed example: a console app for Windows, macOS and Linux that
// embeds the URnetwork SDK in the developer's own product
// (EMBED_CONTRACT.md). It obtains this installation's scoped client JWT from
// the developer's backend, starts a local device with it on the C++ SDK, and
// shows the status and the data caps that the backend set for this
// installation. The device carries only the app's own traffic; what the app
// sends through it continues in the Sockets and Messages examples.
//
// Usage: embed [run] | --self-test | --licenses | --version. The settings
// come from the environment: URNETWORK_EMBED_STATE_DIR (required),
// URNETWORK_TOKEN_SERVER_URL with URNETWORK_DEMO_SESSION, and
// URNETWORK_API_URL.
//
// Exit codes: 0 stopped on request, 78 configuration or credential problem
// (restarting does not help), 1 any other failure.
#include <algorithm>
#include <csignal>
#include <cstring>
#include <exception>
#include <iostream>
#include <memory>
#include <string>

#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <urnetwork_sdk.hpp>

#include "command.hpp"
#include "fetch.hpp"
#include "http_curl.hpp"
#include "selftest.hpp"
#include "session.hpp"
#include "state.hpp"
#include "status.hpp"

#ifndef _WIN32
#include <signal.h>
#endif

namespace {

// The kind of app whose licenses --licenses prints.
#if defined(_WIN32)
const char* const licenseApp = urnet::LicenseAppWindows;
#elif defined(__APPLE__)
const char* const licenseApp = urnet::LicenseAppApple;
#else
const char* const licenseApp = urnet::LicenseAppLinux;
#endif

#ifdef _WIN32
// Ctrl-C and Ctrl-Break stop the app. The handler runs on its own thread;
// returning TRUE keeps the process alive until the run loop stops.
BOOL WINAPI consoleControl(DWORD control) {
    if (control != CTRL_C_EVENT && control != CTRL_BREAK_EVENT) {
        return FALSE;
    }
    embed::stopRequested = 1;
    return TRUE;
}
#else
// SIGINT and SIGTERM stop the app.
void stopSignal(int) {
    embed::stopRequested = 1;
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

// Keeps the sdk's log files in logDir, which the sdk bounds, instead of the
// system temp directory. The sdk still copies its log lines to stderr.
void configureSdkLogs(const embed::fs::path& logDir) {
    embed::makePrivateDir(logDir);
    urnet::setLogDir(embed::utf8Path(logDir));
}

// Runs the app until a stop is requested or the credential is rejected, and
// returns the exit code.
int runEmbed() {
    embed::Settings settings{
        embed::environment("URNETWORK_EMBED_STATE_DIR"),
        embed::environment("URNETWORK_TOKEN_SERVER_URL"),
        embed::environment("URNETWORK_DEMO_SESSION"),
        embed::environment("URNETWORK_API_URL"),
    };
    embed::Config config;
    std::string error;
    int exitCode;
    try {
        exitCode = embed::loadConfig(settings, embed::curlHttp, config, error);
    } catch (const std::exception& e) {
        error = e.what();
        exitCode = embed::exitFailure;
    }
    if (settings.demoSession) {
        std::fill(settings.demoSession->begin(), settings.demoSession->end(), '\0');
    }
    if (exitCode != embed::exitStopped) {
        std::cerr << error << std::endl;
        return exitCode;
    }
    try {
        configureSdkLogs(config.stateDir / "logs");
    } catch (const std::exception& e) {
        std::cerr << "could not set the sdk log directory: " << e.what() << std::endl;
        return embed::exitFailure;
    }
    if (!handleStopRequests()) {
        std::cerr << "could not handle stop signals" << std::endl;
        return embed::exitFailure;
    }
    std::unique_ptr<embed::EmbedSession> session;
    try {
        session = std::make_unique<embed::EmbedSession>(config);
    } catch (const std::exception& e) {
        std::cerr << "could not start the device: " << e.what() << std::endl;
        return embed::exitFailure;
    }
    embed::printLine("embed client " + config.clientId + ", installation " + config.instanceId);
    try {
        int runExitCode = session->run();
        session->close();
        return runExitCode;
    } catch (const std::exception& e) {
        // the session's destructor closes the device
        std::cerr << "the app failed: " << e.what() << std::endl;
        return embed::exitFailure;
    }
}

// Prints the sdk's licenses and data attributions for this kind of app, as
// json. Publish them with the app.
int printLicenses() {
    auto licenses = urnet::getLicenses(licenseApp);
    if (!licenses) {
        std::cerr << "the sdk returned no licenses" << std::endl;
        return embed::exitFailure;
    }
    embed::printLine(nlohmann::json(*licenses).dump());
    return embed::exitStopped;
}

}  // namespace

// Runs one command and returns its exit code.
int main(int argc, char** argv) {
    switch (embed::parseCommand(argc, argv)) {
    case embed::Command::selfTest:
        return embed::selfTestMain();
    case embed::Command::licenses:
        return printLicenses();
    case embed::Command::version:
        embed::printLine(urnet::version());
        return embed::exitStopped;
    case embed::Command::usage:
        std::cerr << embed::usageText << std::endl;
        return embed::exitUsage;
    case embed::Command::run:
        break;
    }
    // once, before any thread; there is no curl_global_cleanup, because a cap
    // read thread may still be in libcurl when the process exits
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
        std::cerr << "libcurl did not start" << std::endl;
        return embed::exitFailure;
    }
    return runEmbed();
}
