// The self-test on its own, built without the sdk library by `make self-test`
// (and as the provider-self-test target of the CMake build): it runs the same
// checks as `provider --self-test` without loading the sdk runtime. It uses
// only the data types of urnetwork_sdk.hpp, which make no sdk calls.
#include "selftest.hpp"

// Runs the self-test: exit code 0 when it passed, 1 when not.
int main() {
    return provider::selfTestMain();
}
