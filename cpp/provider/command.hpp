// The command line and the exit codes of the C++ provider example: provider
// [run] | --self-test | --version. The exit codes are for supervisors
// (background/README.md): 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
#pragma once

#include <string_view>

namespace provider {

// a requested stop: Ctrl-C, SIGTERM
inline constexpr int exitStopped = 0;
// any other failure; supervisors restart after a delay
inline constexpr int exitFailure = 1;
// a configuration or credential problem that a restart does not fix (sysexits
// EX_CONFIG)
inline constexpr int exitConfig = 78;
// an unknown command line is a configuration problem
inline constexpr int exitUsage = exitConfig;

inline constexpr std::string_view usageText = "usage: provider [run] | --self-test | --version";

// The forms of the command line; usage is anything else, which prints the
// usage and exits with exitUsage.
enum class Command { run, selfTest, version, usage };

// The command that the arguments name: no argument or `run` runs the
// provider.
inline Command parseCommand(int argc, const char* const* argv) {
    if (argc <= 1 || (argc == 2 && std::string_view(argv[1]) == "run")) {
        return Command::run;
    }
    if (argc == 2 && std::string_view(argv[1]) == "--self-test") {
        return Command::selfTest;
    }
    if (argc == 2 && std::string_view(argv[1]) == "--version") {
        return Command::version;
    }
    return Command::usage;
}

}  // namespace provider
