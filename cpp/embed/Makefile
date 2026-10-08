# Builds the C++ embed example against the URnetwork SDK's C++ header
# (urnetwork_sdk.hpp, over the C ABI), which needs nlohmann/json, and libcurl.
# Override SDK_INCLUDE, SDK_LIBDIR and JSON_INCLUDE for installed packages; on
# Linux set SDK_RPATH='$$ORIGIN' to run the app with the library beside it.
# libcurl comes from pkg-config. The headers are system include directories,
# so warnings stay about this example's code.
CXX ?= c++
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
SDK_RPATH ?= $(abspath $(SDK_LIBDIR))
JSON_INCLUDE ?= /opt/homebrew/opt/nlohmann-json/include
CURL_CFLAGS ?= $(shell pkg-config --cflags libcurl)
CURL_LIBS ?= $(shell pkg-config --libs libcurl)
CXXFLAGS ?= -O2 -Wall -Wextra
LDLIBS ?= -pthread
HEADERS := command.hpp fetch.hpp http_curl.hpp selftest.hpp session.hpp state.hpp status.hpp
.PHONY: all check self-test clean
all: embed
embed: main.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -std=c++17 -isystem "$(SDK_INCLUDE)" -isystem "$(JSON_INCLUDE)" $(CURL_CFLAGS) main.cpp -L"$(SDK_LIBDIR)" -Wl,-rpath,'$(SDK_RPATH)' -lURnetworkSdk $(CURL_LIBS) $(LDLIBS) -o $@
check: embed
	./embed --self-test
	./embed --version
# the self-test without the sdk library or libcurl: only the sdk header's data
# types and constants
self-test: selftest_main.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -std=c++17 -isystem "$(SDK_INCLUDE)" -isystem "$(JSON_INCLUDE)" selftest_main.cpp $(LDLIBS) -o embed-self-test
	./embed-self-test
clean:
	rm -f embed embed.exe embed-self-test embed-self-test.exe
