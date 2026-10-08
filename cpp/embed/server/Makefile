CXX ?= c++
CPPFLAGS += $(shell pkg-config --cflags libcurl) -I/opt/homebrew/include
CXXFLAGS += -std=c++17 -Wall -Wextra -O2
LDLIBS += $(shell pkg-config --libs libcurl)
embed-server: embed_server.cpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< $(LDLIBS) -o $@
self-test: embed-server
	./embed-server --self-test
clean:
	rm -f embed-server
.PHONY: self-test clean
