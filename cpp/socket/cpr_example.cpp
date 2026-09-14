#include <cpr/cpr.h>
#include <cstdlib>
#include <iostream>
int main(int argc, char **argv) {
    const char *proxy = std::getenv("URNETWORK_HTTP_PROXY");
    if (!proxy || !*proxy) {std::cerr << "Start the Go socket proxy and set URNETWORK_HTTP_PROXY\n"; return 1;}
    cpr::Session session;
    session.SetUrl(cpr::Url{argc > 1 ? argv[1] : "https://example.com/"});
    session.SetProxies(cpr::Proxies{{"http", proxy}, {"https", proxy}});
    session.SetTimeout(cpr::Timeout{30000});
    // Keep environment NO_PROXY from bypassing the explicitly selected path.
    curl_easy_setopt(session.GetCurlHolder()->handle, CURLOPT_NOPROXY, "");
    auto response = session.Get();
    if (response.error.code != cpr::ErrorCode::OK) {std::cerr << response.error.message << '\n'; return 1;}
    std::cout << response.status_code << '\n' << response.text << '\n';
}
