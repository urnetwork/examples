#pragma once
#include "ur_session.h"
#include <boost/asio/buffer.hpp>
#include <boost/asio/error.hpp>
#include <boost/system/system_error.hpp>
#include <algorithm>
#include <limits>
#include <stdexcept>

// Boost.Beast SyncReadStream/SyncWriteStream over an SDK Conn. No native_handle
// is exposed: a C ABI handle is not an OS descriptor or an Asio TCP socket.
class UrStream {
    uint64_t conn_;
    boost::system::error_code pending_read_, pending_write_;
public:
    explicit UrStream(uint64_t device, const std::string& address) {
        char *error = nullptr;
        conn_ = urnet_device_dial_tls(device, "tcp", address.c_str(), 30000, "{\"nextProtos\":[\"http/1.1\"]}", &error);
        if (error) {std::string message(error); urnet_free_string(error); throw std::runtime_error(message);}
        if (!conn_) throw std::runtime_error("Socket creation returned zero");
        urnet_conn_set_deadline(conn_, ur_now_millis() + 30000, &error);
        if (error) {std::string message(error); urnet_free_string(error); urnet_release(conn_); throw std::runtime_error(message);}
    }
    ~UrStream() {urnet_release(conn_);}
    UrStream(const UrStream&) = delete;
    UrStream& operator=(const UrStream&) = delete;
    template<class Buffers> std::size_t read_some(const Buffers& buffers, boost::system::error_code& ec) {
        ec.clear();
        for (auto i = boost::asio::buffer_sequence_begin(buffers); i != boost::asio::buffer_sequence_end(buffers); ++i) {
            auto b = boost::asio::mutable_buffer(*i);
            if (!b.size()) continue;
            if (pending_read_) {ec = pending_read_; return 0;}
            bool eof = false; char *error = nullptr;
            auto n = urnet_conn_read(conn_, static_cast<uint8_t*>(b.data()), static_cast<int32_t>(std::min<std::size_t>(b.size(), 65535)), &eof, &error);
            if (error) {ur_error(error); pending_read_ = boost::asio::error::fault;}
            else if (eof) pending_read_ = boost::asio::error::eof;
            if (n <= 0) ec = pending_read_;
            return static_cast<std::size_t>(std::max<int32_t>(n, 0));
        }
        return 0;
    }
    template<class Buffers> std::size_t write_some(const Buffers& buffers, boost::system::error_code& ec) {
        ec.clear();
        for (auto i = boost::asio::buffer_sequence_begin(buffers); i != boost::asio::buffer_sequence_end(buffers); ++i) {
            auto b = boost::asio::const_buffer(*i);
            if (!b.size()) continue;
            if (pending_write_) {ec = pending_write_; return 0;}
            char *error = nullptr;
            auto n = urnet_conn_write(conn_, static_cast<const uint8_t*>(b.data()), static_cast<int32_t>(std::min<std::size_t>(b.size(), 65535)), &error);
            if (error) {ur_error(error); pending_write_ = boost::asio::error::fault;}
            if (n <= 0) ec = pending_write_ ? pending_write_ : boost::asio::error::broken_pipe;
            return static_cast<std::size_t>(std::max<int32_t>(n, 0));
        }
        return 0;
    }
    template<class Buffers> std::size_t read_some(const Buffers& b) {boost::system::error_code e; auto n=read_some(b,e); if(e) throw boost::system::system_error(e); return n;}
    template<class Buffers> std::size_t write_some(const Buffers& b) {boost::system::error_code e; auto n=write_some(b,e); if(e) throw boost::system::system_error(e); return n;}
};
