CXX ?= c++
CC ?= cc
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
.PHONY: all check clean
all: messages-example
runtime.o: ../../c/messages/runtime.c ../../c/messages/codec.h ../../c/integration/ur_session.h
	$(CC) -O2 -Wall -Wextra -std=c11 -I"$(SDK_INCLUDE)" -c $< -o $@
messages-example: main.cpp runtime.o
	$(CXX) -std=c++17 -O2 -Wall -Wextra main.cpp runtime.o -pthread -L"$(SDK_LIBDIR)" -Wl,-rpath,"$(abspath $(SDK_LIBDIR))" -lURnetworkSdk -o $@
check: messages-example
	./messages-example --self-test
	./messages-example --version
clean:
	rm -f runtime.o messages-example
