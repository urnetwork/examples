CC ?= cc
SDK_INCLUDE ?= ../../../sdk/cgo/include
SDK_LIBDIR ?= ../../../sdk/cgo/build/packages/darwin-arm64
CFLAGS ?= -O2 -Wall -Wextra
CURL_CFLAGS := $(shell curl-config --cflags)
CURL_LIBS := $(shell curl-config --libs)
.PHONY: all check clean
all: socket-example
socket-example: main.c ur_session.h
	$(CC) $(CFLAGS) -std=c11 -I"$(SDK_INCLUDE)" $(CURL_CFLAGS) main.c -L"$(SDK_LIBDIR)" -Wl,-rpath,"$(abspath $(SDK_LIBDIR))" -lURnetworkSdk $(CURL_LIBS) -o $@
check: socket-example
	./socket-example --version
clean:
	rm -f socket-example
