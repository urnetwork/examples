CXX ?= c++
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
BOOST_INCLUDE ?= /opt/homebrew/include
CXXFLAGS ?= -O2 -Wall -Wextra
.PHONY: all check clean
all: socket-example
socket-example: main.cpp ur_stream.hpp ur_session.h
	$(CXX) $(CXXFLAGS) -std=c++17 -I"$(SDK_INCLUDE)" -I"$(BOOST_INCLUDE)" main.cpp -L"$(SDK_LIBDIR)" -Wl,-rpath,"$(abspath $(SDK_LIBDIR))" -lURnetworkSdk -o $@
check: socket-example
	./socket-example --version
clean:
	rm -f socket-example
