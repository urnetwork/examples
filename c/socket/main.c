#include "ur_session.h"
#include <curl/curl.h>

static int curl_request(const char *url) {
    const char *proxy = getenv("URNETWORK_HTTP_PROXY");
    if (!proxy || !*proxy) { fputs("Start the Go socket example in proxy mode and set URNETWORK_HTTP_PROXY.\n", stderr); return 1; }
    if (curl_global_init(CURL_GLOBAL_DEFAULT)) return 1;
    CURL *client = curl_easy_init();
    if (!client) { curl_global_cleanup(); return 1; }
    curl_easy_setopt(client, CURLOPT_URL, url);
    curl_easy_setopt(client, CURLOPT_PROXY, proxy);
    curl_easy_setopt(client, CURLOPT_NOPROXY, "");
    curl_easy_setopt(client, CURLOPT_TIMEOUT, 30L);
    /* HTTPS certificate and hostname verification remain enabled. The proxy
     * receives the original hostname and opens the upstream UR SDK socket. */
    CURLcode result = curl_easy_perform(client);
    if (result) fprintf(stderr, "curl: %s\n", curl_easy_strerror(result));
    curl_easy_cleanup(client); curl_global_cleanup(); return result ? 1 : 0;
}
int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "tls";
    if (!strcmp(mode, "--version") || !strcmp(mode, "--new-id")) {
        char *s = !strcmp(mode, "--version") ? urnet_version() : urnet_new_id();
        puts(s); urnet_free_string(s); return 0;
    }
    if (!strcmp(mode, "curl")) return curl_request(argc > 2 ? argv[2] : "https://example.com/");
    bool udp = !strcmp(mode, "udp"), dtls = !strcmp(mode, "dtls");
    if (!udp && !dtls && strcmp(mode, "tls")) { fputs("Modes: tls, udp, dtls, curl\n", stderr); return 1; }
    if ((udp || dtls) && argc < 3) { fputs("UDP/DTLS require an echo server host:port.\n", stderr); return 1; }
    const char *address = argc > 2 ? argv[2] : "example.com:443";
    ur_session s; if (ur_session_open(&s)) return 1;
    char *error = NULL;
    uint64_t conn = udp ? urnet_device_dial(s.device, "udp", address, 30000, &error)
        : urnet_device_dial_tls(s.device, dtls ? "udp" : "tcp", address, 30000, "{}", &error);
    int result = ur_error(error); if (result || !conn) { ur_session_close(&s); return 1; }
    error = NULL; urnet_conn_set_deadline(conn, ur_now_millis() + 10000, &error);
    result = ur_error(error);
    char request[2048];
    if (udp || dtls) strcpy(request, "hello");
    else if (snprintf(request, sizeof(request), "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", address) >= (int)sizeof(request)) result = 1;
    for (size_t offset = 0, length = strlen(request); !result && offset < length;) {
        error = NULL;
        int32_t n = urnet_conn_write(conn, (const uint8_t *)request + offset, (int32_t)(length - offset), &error);
        result = ur_error(error); if (n <= 0) result = 1; else offset += (size_t)n;
    }
    for (size_t received = 0; !result && received < 1024 * 1024;) {
        uint8_t data[65535]; bool eof = false; error = NULL;
        int32_t n = urnet_conn_read(conn, data, sizeof(data), &eof, &error);
        if (n > 0) { fwrite(data, 1, (size_t)n, stdout); received += (size_t)n; }
        result = ur_error(error);
        /* n == 0 with eof == false is a valid empty datagram. */
        if (eof || udp || dtls) break;
    }
    urnet_release(conn); ur_session_close(&s); return result;
}
