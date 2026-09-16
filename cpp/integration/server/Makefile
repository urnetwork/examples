CXX ?= c++
CPPFLAGS += $(shell pkg-config --cflags libcurl) -I/opt/homebrew/include
CXXFLAGS += -std=c++17 -Wall -Wextra -O2
LDLIBS += $(shell pkg-config --libs libcurl)
allocator: allocator.cpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< $(LDLIBS) -o $@
self-test: allocator
	./allocator --self-test
clean:
	rm -f allocator
.PHONY: self-test clean
