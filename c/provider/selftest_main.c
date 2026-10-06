/* The self-test on its own, built without the sdk library by `make self-test`
 * (and as the provider-self-test target of the CMake build): it runs the same
 * checks as `provider --self-test` without loading the sdk runtime. */
#include "provider.h"

/* Runs the self-test: exit code 0 when it passed, 1 when not. */
int main(void) { return ur_provider_self_test_main(); }
