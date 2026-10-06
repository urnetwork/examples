# Builds the C provider example against the URnetwork SDK's C ABI. Override
# SDK_INCLUDE and SDK_LIBDIR for an installed SDK package; on Linux set
# SDK_RPATH='$$ORIGIN' to run the app with the library beside it.
CC ?= cc
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
SDK_RPATH ?= $(abspath $(SDK_LIBDIR))
CFLAGS ?= -O2 -Wall -Wextra
LDLIBS ?= -pthread
SOURCES := main.c session.c status.c state.c json.c selftest.c
SELF_TEST_SOURCES := selftest_main.c status.c state.c json.c selftest.c
.PHONY: all check self-test clean
all: provider
provider: $(SOURCES) provider.h
	$(CC) $(CFLAGS) -std=c11 -I"$(SDK_INCLUDE)" $(SOURCES) -L"$(SDK_LIBDIR)" -Wl,-rpath,'$(SDK_RPATH)' -lURnetworkSdk $(LDLIBS) -o $@
check: provider
	./provider --self-test
	./provider --version
# the self-test without the sdk library: only the sdk header's constants
self-test: $(SELF_TEST_SOURCES) provider.h
	$(CC) $(CFLAGS) -std=c11 -I"$(SDK_INCLUDE)" $(SELF_TEST_SOURCES) $(LDLIBS) -o provider-self-test
	./provider-self-test
clean:
	rm -f provider provider.exe provider-self-test provider-self-test.exe
