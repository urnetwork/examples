#include "codec.h"
#include <stdio.h>
int ur_messages_main(int argc, char **argv);
int main(int argc, char **argv) {
  if (argc == 2 && !strcmp(argv[1], "--self-test")) {
    int result = urms_self_test();
    puts(result ? "codec test failed" : "URMS codec self-test passed");
    return result;
  }
  return ur_messages_main(argc, argv);
}
