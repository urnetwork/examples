/* The self-test on its own, built without the sdk library and without libcurl
 * by `make self-test` (and as the embed-self-test target of the CMake build):
 * it runs the same checks as `embed --self-test` without loading either
 * runtime. */
#include "embed.h"

/* Runs the self-test: exit code 0 when it passed, 1 when not. */
int main(void) { return ur_embed_self_test_main(); }
