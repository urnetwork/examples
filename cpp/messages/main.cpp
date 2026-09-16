#include "../../c/messages/codec.h"
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" int ur_messages_main(int argc, char **argv);

// The C and C++ SDK packages share an ABI and wire codec. Own application bytes
// in a vector; the native callback queue in runtime.c also copies before
// returning.
static std::vector<std::uint8_t> text_frame(std::uint64_t id,
                                            const std::string &text) {
  std::vector<std::uint8_t> bytes(URMS_MAX_FRAME);
  auto size = urms_encode(bytes.data(), bytes.size(), 1, id,
                          reinterpret_cast<const std::uint8_t *>(text.data()),
                          text.size());
  if (!size)
    throw std::invalid_argument("invalid message");
  bytes.resize(size);
  return bytes;
}
int main(int argc, char **argv) {
  if (argc == 2 && std::string(argv[1]) == "--self-test") {
    auto bytes = text_frame(1, "hi");
    urms_frame frame{};
    if (urms_self_test() || !urms_decode(bytes.data(), bytes.size(), &frame) ||
        frame.id != 1)
      return 1;
    std::cout << "URMS codec self-test passed\n";
    return 0;
  }
  return ur_messages_main(argc, argv);
}
