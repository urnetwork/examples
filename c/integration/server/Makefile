CC ?= cc
CPPFLAGS += $(shell pkg-config --cflags libcurl json-c)
CFLAGS += -std=c11 -Wall -Wextra -O2
LDLIBS += $(shell pkg-config --libs libcurl json-c)
allocator: allocator.c
	$(CC) $(CPPFLAGS) $(CFLAGS) $< $(LDLIBS) -o $@
self-test: allocator
	./allocator --self-test
clean:
	rm -f allocator
.PHONY: self-test clean
