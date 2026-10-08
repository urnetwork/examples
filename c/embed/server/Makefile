CC ?= cc
CPPFLAGS += $(shell pkg-config --cflags libcurl json-c)
CFLAGS += -std=c11 -Wall -Wextra -O2
LDLIBS += $(shell pkg-config --libs libcurl json-c)
embed-server: embed_server.c
	$(CC) $(CPPFLAGS) $(CFLAGS) $< $(LDLIBS) -o $@
self-test: embed-server
	./embed-server --self-test
clean:
	rm -f embed-server
.PHONY: self-test clean
