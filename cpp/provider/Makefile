# Builds the C++ provider example against the URnetwork SDK's C++ header
# (urnetwork_sdk.hpp, over the C ABI), which needs nlohmann/json. Override
# SDK_INCLUDE, SDK_LIBDIR and JSON_INCLUDE for installed packages; on Linux set
# SDK_RPATH='$$ORIGIN' to run the app with the library beside it. The headers
# are system include directories, so warnings stay about this example's code.
CXX ?= c++
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
SDK_RPATH ?= $(abspath $(SDK_LIBDIR))
JSON_INCLUDE ?= /opt/homebrew/opt/nlohmann-json/include
CXXFLAGS ?= -O2 -Wall -Wextra
LDLIBS ?= -pthread
HEADERS := command.hpp selftest.hpp session.hpp state.hpp status.hpp
.PHONY: all check self-test clean
all: provider
provider: main.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -std=c++17 -isystem "$(SDK_INCLUDE)" -isystem "$(JSON_INCLUDE)" main.cpp -L"$(SDK_LIBDIR)" -Wl,-rpath,'$(SDK_RPATH)' -lURnetworkSdk $(LDLIBS) -o $@
check: provider
	./provider --self-test
	./provider --version
# the self-test without the sdk library: only the sdk header's data types
self-test: selftest_main.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -std=c++17 -isystem "$(SDK_INCLUDE)" -isystem "$(JSON_INCLUDE)" selftest_main.cpp $(LDLIBS) -o provider-self-test
	./provider-self-test
clean:
	rm -f provider provider.exe provider-self-test provider-self-test.exe
