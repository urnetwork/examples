#include "codec.h"
#include <stdio.h>
int main(void) {
  int result = urms_self_test();
  puts(result ? "codec test failed" : "URMS codec self-test passed");
  return result;
}
