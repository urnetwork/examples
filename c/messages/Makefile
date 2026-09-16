CC ?= cc
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
CFLAGS ?= -O2 -Wall -Wextra
.PHONY: all check self-test clean
all: messages-example
messages-example: main.c runtime.c codec.h ../integration/ur_session.h
	$(CC) $(CFLAGS) -std=c11 -I"$(SDK_INCLUDE)" main.c runtime.c -pthread -L"$(SDK_LIBDIR)" -Wl,-rpath,"$(abspath $(SDK_LIBDIR))" -lURnetworkSdk -o $@
check: messages-example
	./messages-example --self-test
	./messages-example --version
self-test:
	$(CC) $(CFLAGS) -std=c11 codec_test.c -o codec-test
	./codec-test
clean:
	rm -f messages-example codec-test
