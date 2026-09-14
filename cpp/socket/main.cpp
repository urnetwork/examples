#include "ur_stream.hpp"
#include <boost/beast/core.hpp>
#include <boost/beast/http.hpp>
#include <iostream>

struct Session {
    ur_session value{};
    Session() {if (ur_session_open(&value)) throw std::runtime_error("Device setup failed");}
    ~Session() {ur_session_close(&value);}
};
int main(int argc, char** argv) {
    try {
        std::string mode = argc > 1 ? argv[1] : "beast";
        if (mode == "--version" || mode == "--new-id") {
            auto s = mode == "--version" ? urnet_version() : urnet_new_id();
            std::cout << s << '\n'; urnet_free_string(s); return 0;
        }
        Session session;
        if (mode == "beast") {
            std::string authority = argc > 2 ? argv[2] : "example.com:443";
            UrStream stream(session.value.device, authority);
            namespace http = boost::beast::http;
            http::request<http::empty_body> request{http::verb::get, argc > 3 ? argv[3] : "/", 11};
            request.set(http::field::host, authority);
            request.set(http::field::connection, "close");
            http::write(stream, request);
            boost::beast::flat_buffer buffer;
            http::response_parser<http::string_body> parser;
            parser.body_limit(1 << 20);
            http::read(stream, buffer, parser);
            std::cout << parser.get() << '\n';
        } else if (mode == "udp" || mode == "dtls") {
            if (argc < 3) throw std::runtime_error("Provide an echo server host:port");
            char *error = nullptr;
            auto conn = mode == "udp" ? urnet_device_dial(session.value.device, "udp", argv[2], 30000, &error)
                : urnet_device_dial_tls(session.value.device, "udp", argv[2], 30000, "{}", &error);
            if (ur_error(error) || !conn) return 1;
            struct Release {uint64_t h; ~Release(){urnet_release(h);}} release{conn};
            urnet_conn_set_deadline(conn, ur_now_millis() + 10000, &error); if (ur_error(error)) return 1;
            urnet_conn_write(conn, reinterpret_cast<const uint8_t*>("hello"), 5, &error); if (ur_error(error)) return 1;
            uint8_t bytes[65535]; bool eof = false;
            auto n = urnet_conn_read(conn, bytes, sizeof(bytes), &eof, &error);
            if (n > 0) std::cout.write(reinterpret_cast<char*>(bytes), n);
            if (ur_error(error)) return 1;
            std::cout << "\nEOF: " << eof << '\n';
        } else throw std::runtime_error("Modes: beast, udp, dtls");
    } catch (const std::exception& e) {std::cerr << e.what() << '\n'; return 1;}
}
