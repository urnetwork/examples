// The app's HTTP function on libcurl: one request, no redirects, HTTP and
// HTTPS only, a bounded answer and a timeout. It runs on the app's thread for
// the token fetch and on a cap reader thread, so it never uses signals. The
// bearer token goes only into the Authorization header. The process calls
// curl_global_init once at start, before any thread.
#pragma once

#include <algorithm>
#include <memory>
#include <optional>
#include <string>

#include <curl/curl.h>

#include "fetch.hpp"

namespace embed {

// The largest answer the app reads.
inline constexpr std::size_t responseByteLimit = 1024 * 1024;

namespace detail {

// The answer's bytes as they arrive.
struct ResponseBuffer {
    std::string bytes;
    bool tooLarge = false;
};

// Appends one chunk of the answer; stops the transfer past the limit.
inline std::size_t receive(char* data, std::size_t size, std::size_t count, void* context) {
    auto* buffer = static_cast<ResponseBuffer*>(context);
    std::size_t length = size * count;
    if (responseByteLimit - buffer->bytes.size() < length) {
        buffer->tooLarge = true;
        return 0;
    }
    try {
        buffer->bytes.append(data, length);
    } catch (...) {
        return 0;
    }
    return length;
}

}  // namespace detail

// Sends one request with libcurl.
inline std::optional<HttpResponse> curlHttp(const HttpRequest& request, std::string& error) {
    std::unique_ptr<CURL, decltype(&curl_easy_cleanup)> curl(curl_easy_init(), curl_easy_cleanup);
    if (!curl) {
        error = "libcurl did not start";
        return std::nullopt;
    }
    curl_slist* list = nullptr;
    if (!request.authorization.empty()) {
        std::string header = "Authorization: Bearer " + request.authorization;
        list = curl_slist_append(list, header.c_str());
        // curl keeps its own copy of the header
        std::fill(header.begin(), header.end(), '\0');
    }
    if (request.body) {
        list = curl_slist_append(list, "Content-Type: application/json");
    }
    list = curl_slist_append(list, "Accept: application/json");
    std::unique_ptr<curl_slist, decltype(&curl_slist_free_all)> headers(list, curl_slist_free_all);
    detail::ResponseBuffer buffer;
    curl_easy_setopt(curl.get(), CURLOPT_URL, request.url.c_str());
    curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, list);
    curl_easy_setopt(curl.get(), CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl.get(), CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(curl.get(), CURLOPT_CONNECTTIMEOUT, 10L);
    curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, 15L);
#if LIBCURL_VERSION_NUM >= 0x075500
    curl_easy_setopt(curl.get(), CURLOPT_PROTOCOLS_STR, "http,https");
#endif
    curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, detail::receive);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &buffer);
    if (request.body) {
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDS, request.body->c_str());
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDSIZE, static_cast<long>(request.body->size()));
    } else {
        curl_easy_setopt(curl.get(), CURLOPT_HTTPGET, 1L);
    }
    CURLcode result = curl_easy_perform(curl.get());
    long status = 0;
    curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &status);
    if (buffer.tooLarge) {
        error = "the answer is too large";
        return std::nullopt;
    }
    if (result != CURLE_OK) {
        error = curl_easy_strerror(result);
        return std::nullopt;
    }
    return HttpResponse{status, std::move(buffer.bytes)};
}

}  // namespace embed
