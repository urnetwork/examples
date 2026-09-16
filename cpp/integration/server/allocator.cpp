// SERVER ONLY: ./allocator user:<authenticated-service-user-id> | --self-test
// The authenticated backend supplies the service key internally, never a raw
// request field or UR client ID. Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP
// (absolute path in an existing service-owned directory), and optional
// URNETWORK_API_URL. This POSIX CLI uses libcurl + nlohmann/json. Confirm
// crash-left .lock is stale before removal.
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <cstdlib>
#include <curl/curl.h>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <nlohmann/json.hpp>
#include <regex>
#include <set>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
using Json = nlohmann::json;
namespace fs = std::filesystem;
constexpr size_t limit = 1 << 20;
const std::regex users("user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}");
const std::regex ids("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
void require(bool ok) {
  if (!ok)
    throw std::runtime_error("invalid allocator input or operation");
}
std::string service_user(const std::vector<std::string> &args) {
  require(args.size() == 1 && std::regex_match(args[0], users));
  return args[0];
}
std::string url_part(CURLU *u, CURLUPart part) {
  char *raw = nullptr;
  if (curl_url_get(u, part, &raw, 0) != CURLUE_OK)
    return {};
  std::unique_ptr<char, decltype(&curl_free)> owned(raw, curl_free);
  return raw;
}
std::string endpoint(const std::string &base) {
  std::unique_ptr<CURLU, decltype(&curl_url_cleanup)> url(curl_url(),
                                                          curl_url_cleanup);
  require(url &&
          curl_url_set(url.get(), CURLUPART_URL, base.c_str(), 0) == CURLUE_OK);
  auto scheme = url_part(url.get(), CURLUPART_SCHEME),
       host = url_part(url.get(), CURLUPART_HOST),
       path = url_part(url.get(), CURLUPART_PATH);
  bool local = host == "localhost" || host == "127.0.0.1" || host == "[::1]";
  require(!host.empty() && (scheme == "https" || (scheme == "http" && local)) &&
          (path.empty() || path == "/") &&
          url_part(url.get(), CURLUPART_USER).empty() &&
          url_part(url.get(), CURLUPART_PASSWORD).empty() &&
          url_part(url.get(), CURLUPART_QUERY).empty() &&
          url_part(url.get(), CURLUPART_FRAGMENT).empty());
  require(curl_url_set(url.get(), CURLUPART_PATH, "/network/auth-client", 0) ==
          CURLUE_OK);
  return url_part(url.get(), CURLUPART_URL);
}
Json request_for(const std::string &user, const std::string &client = {}) {
  service_user({user});
  Json body = {{"description", "service " + user},
               {"device_spec", "urnetwork-examples/cpp-server"}};
  if (!client.empty()) {
    require(std::regex_match(client, ids));
    body["client_id"] = client;
  }
  return body;
}
std::string base64url_decode(const std::string &value) {
  const std::string alphabet =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  std::string output;
  unsigned int bits = 0;
  int count = 0;
  require(!value.empty() && value.size() % 4 != 1);
  for (unsigned char c : value) {
    auto index = alphabet.find(c);
    require(index != std::string::npos);
    bits = (bits << 6) | unsigned(index);
    count += 6;
    if (count >= 8) {
      count -= 8;
      output.push_back(char((bits >> count) & 255));
    }
  }
  return output;
}
Json parse_response(const std::string &raw, const std::string &expected = {}) {
  auto obj = Json::parse(raw);
  require(obj.is_object() &&
          (!obj.contains("error") || obj["error"].is_null()) &&
          obj["client_id"].is_string() && obj["by_client_jwt"].is_string());
  auto id = obj["client_id"].get<std::string>(),
       jwt = obj["by_client_jwt"].get<std::string>();
  auto a = jwt.find('.'), b = jwt.find('.', a == std::string::npos ? 0 : a + 1);
  require(std::regex_match(id, ids) && a != std::string::npos && a > 0 &&
          b != std::string::npos && b > a + 1 && b + 1 < jwt.size() &&
          jwt.find('.', b + 1) == std::string::npos);
  auto claims = Json::parse(base64url_decode(jwt.substr(a + 1, b - a - 1)));
  require(claims["client_id"] == id && (expected.empty() || expected == id));
  // Claim comparison checks consistency, not signatures locally.
  return {{"client_id", id}, {"by_client_jwt", jwt}};
}
Json load_map(const fs::path &file) {
  struct stat st{};
  if (lstat(file.c_str(), &st) != 0) {
    require(errno == ENOENT);
    return {{"version", 1}, {"clients", Json::object()}};
  }
  require(S_ISREG(st.st_mode) && !(st.st_mode & 0077) &&
          st.st_size <= long(limit));
  std::ifstream input(file);
  Json map = Json::parse(input);
  require(map["version"].is_number_integer() && map["version"] == 1 &&
          map["clients"].is_object());
  std::set<std::string> seen;
  for (auto &[user, value] : map["clients"].items()) {
    require(std::regex_match(user, users) && value.is_string());
    auto id = value.get<std::string>();
    require(std::regex_match(id, ids) && seen.insert(id).second);
  }
  return map;
}
void save_map(const fs::path &file, const Json &map) {
  std::string pattern = file.string() + ".XXXXXX";
  std::vector<char> temp(pattern.begin(), pattern.end());
  temp.push_back(0);
  int fd = mkstemp(temp.data());
  require(fd >= 0);
  try {
    require(fchmod(fd, 0600) == 0);
    auto raw = map.dump();
    size_t offset = 0;
    while (offset < raw.size()) {
      ssize_t n = write(fd, raw.data() + offset, raw.size() - offset);
      if (n < 0 && errno == EINTR)
        continue;
      require(n > 0);
      offset += size_t(n);
    }
    require(fsync(fd) == 0);
    int closed = close(fd);
    fd = -1;
    require(closed == 0);
    require(rename(temp.data(), file.c_str()) == 0);
  } catch (...) {
    if (fd >= 0)
      close(fd);
    unlink(temp.data());
    throw;
  }
}
struct Lock {
  fs::path path;
  explicit Lock(const fs::path &file) : path(file.string() + ".lock") {
    require(file.is_absolute() && mkdir(path.c_str(), 0700) == 0);
  }
  ~Lock() { rmdir(path.c_str()); }
};
Json allocate(const std::string &user, const fs::path &file,
              const std::function<std::string(const Json &)> &call) {
  Lock lock(file);
  auto map = load_map(file);
  std::string old = map["clients"].value(user, std::string{});
  auto result = parse_response(call(request_for(user, old)), old);
  if (old.empty()) {
    for (const auto &id : map["clients"])
      require(id != result["client_id"]);
    map["clients"][user] = result["client_id"];
    save_map(file, map);
  }
  return result;
}
size_t receive(char *bytes, size_t size, size_t count, void *context) {
  auto &output = *static_cast<std::string *>(context);
  size_t n = size * count;
  if (n > limit - output.size())
    return 0;
  try {
    output.append(bytes, n);
  } catch (...) {
    return 0;
  }
  return n;
}
std::string post(const std::string &url, const std::string &root,
                 const Json &body) {
  require(!root.empty() &&
          std::none_of(root.begin(), root.end(),
                       [](unsigned char c) { return std::isspace(c); }));
  std::unique_ptr<CURL, decltype(&curl_easy_cleanup)> curl(curl_easy_init(),
                                                           curl_easy_cleanup);
  require(bool(curl));
  curl_slist *list = nullptr;
  list = curl_slist_append(list, ("Authorization: Bearer " + root).c_str());
  list = curl_slist_append(list, "Content-Type: application/json");
  std::unique_ptr<curl_slist, decltype(&curl_slist_free_all)> headers(
      list, curl_slist_free_all);
  auto raw = body.dump();
  std::string response;
  curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
  curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, list);
  curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDS, raw.c_str());
  curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDSIZE, long(raw.size()));
  curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, 15L);
  curl_easy_setopt(curl.get(), CURLOPT_FOLLOWLOCATION, 0L);
  curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, receive);
  curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &response);
  require(curl_easy_perform(curl.get()) == CURLE_OK);
  long code = 0;
  curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &code);
  require(code >= 200 && code < 300);
  return response;
}
void reject(const std::function<void()> &action) {
  try {
    action();
  } catch (...) {
    return;
  }
  throw std::runtime_error("invalid input accepted");
}
void self_test() {
  std::string id = "11111111-1111-1111-1111-111111111111";
  // Fixed offline token payload contains only
  // {"client_id":"11111111-1111-1111-1111-111111111111"}.
  std::string jwt = "e30."
                    "eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMT"
                    "ExMTExMTEifQ.test";
  auto raw = Json{{"client_id", id}, {"by_client_jwt", jwt}}.dump();
  std::vector<Json> calls;
  auto mock = [&](const Json &body) {
    calls.push_back(body);
    return raw;
  };
  std::string pattern =
      (fs::temp_directory_path() / "ur-allocator-XXXXXX").string();
  std::vector<char> temp(pattern.begin(), pattern.end());
  temp.push_back(0);
  require(mkdtemp(temp.data()) != nullptr);
  fs::path dir(temp.data()), file = dir / "clients.json";
  try {
    require(allocate("user:alice", file, mock)["client_id"] == id);
    require(allocate("user:alice", file, mock)["by_client_jwt"] == jwt);
    require(!calls[0].contains("client_id") &&
            !calls[0].contains("source_client_id") &&
            !calls[1].contains("source_client_id") &&
            calls[1]["client_id"] == id);
    require(load_map(file)["clients"]["user:alice"] == id);
    require(endpoint("http://127.0.0.1:1234") ==
            "http://127.0.0.1:1234/network/auth-client");
    reject([&] { service_user({id}); });
    reject([&] { service_user({"user:a", "--client-id", id}); });
    reject([] { service_user({"user:../a"}); });
    reject([] { endpoint("http://example.com"); });
    reject([] { endpoint("https://example.com/path"); });
    reject([] { parse_response("{\"error\":{}}"); });
    reject(
        [&] { parse_response(raw, "22222222-2222-2222-2222-222222222222"); });
  } catch (...) {
    fs::remove_all(dir);
    throw;
  }
  fs::remove_all(dir);
  std::cout << "allocator self-test passed\n";
}
std::string env_required(const char *key) {
  const char *value = getenv(key);
  require(value && *value);
  return value;
}
int main(int argc, char **argv) {
  try {
    require(curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK);
    if (argc == 2 && std::string(argv[1]) == "--self-test") {
      self_test();
      curl_global_cleanup();
      return 0;
    }
    std::string user =
        service_user(std::vector<std::string>(argv + 1, argv + argc));
    const char *base = getenv("URNETWORK_API_URL");
    auto url = endpoint(base ? base : "https://api.bringyour.com");
    auto root = env_required("URNETWORK_ROOT_JWT"),
         file = env_required("URNETWORK_CLIENT_MAP");
    std::cout << allocate(
                     user, file,
                     [&](const Json &body) { return post(url, root, body); })
                     .dump()
              << '\n';
    curl_global_cleanup();
    return 0;
  } catch (...) {
    curl_global_cleanup();
    std::cerr << "allocator failed: check service key, private mapping and "
                 "backend API configuration\n";
    return 1;
  }
}
