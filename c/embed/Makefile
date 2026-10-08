# Builds the C embed example against the URnetwork SDK's C ABI and libcurl.
# Override SDK_INCLUDE and SDK_LIBDIR for an installed SDK package; on Linux set
# SDK_RPATH='$$ORIGIN' to run the app with the library beside it. libcurl comes
# from pkg-config.
CC ?= cc
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
SDK_RPATH ?= $(abspath $(SDK_LIBDIR))
CURL_CFLAGS ?= $(shell pkg-config --cflags libcurl)
CURL_LIBS ?= $(shell pkg-config --libs libcurl)
CFLAGS ?= -O2 -Wall -Wextra
LDLIBS ?= -pthread
SOURCES := main.c session.c status.c state.c fetch.c http_curl.c json.c selftest.c
SELF_TEST_SOURCES := selftest_main.c status.c state.c fetch.c json.c selftest.c
.PHONY: all check self-test clean
all: embed
embed: $(SOURCES) embed.h
	$(CC) $(CFLAGS) -std=c11 -I"$(SDK_INCLUDE)" $(CURL_CFLAGS) $(SOURCES) -L"$(SDK_LIBDIR)" -Wl,-rpath,'$(SDK_RPATH)' -lURnetworkSdk $(CURL_LIBS) $(LDLIBS) -o $@
check: embed
	./embed --self-test
	./embed --version
# the self-test without the sdk library or libcurl: only the sdk header's
# constants
self-test: $(SELF_TEST_SOURCES) embed.h
	$(CC) $(CFLAGS) -std=c11 -I"$(SDK_INCLUDE)" $(SELF_TEST_SOURCES) $(LDLIBS) -o embed-self-test
	./embed-self-test
clean:
	rm -f embed embed.exe embed-self-test embed-self-test.exe
