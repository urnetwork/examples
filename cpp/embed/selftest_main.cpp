// The self-test on its own, built without the sdk library and without libcurl
// by `make self-test` (and as the embed-self-test target of the CMake build):
// it runs the same checks as `embed --self-test` without loading either
// runtime. It uses only the data types and constants of urnetwork_sdk.hpp,
// which make no sdk calls.
#include "selftest.hpp"

// Runs the self-test: exit code 0 when it passed, 1 when not.
int main() {
    return embed::selfTestMain();
}
