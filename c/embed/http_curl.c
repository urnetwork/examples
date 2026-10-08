/* The app's HTTP function on libcurl: one request, no redirects, HTTP and
 * HTTPS only, a bounded answer and a timeout. It runs on the app's thread for
 * the token fetch and on a cap reader thread, so it never uses signals. The
 * bearer token goes only into the Authorization header. */
#include "embed.h"
#include <curl/curl.h>
#include <stdarg.h>
#include <stdio.h>

/* The largest answer the app reads. */
#define RESPONSE_BYTE_LIMIT (1024 * 1024)

/* The answer's bytes as they arrive. */
typedef struct {
  char *bytes;
  size_t length;
  bool too_large;
} response_buffer;

/* Writes the message to error and returns false. */
static bool fail(char *error, size_t capacity, const char *format, ...) {
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(error, capacity, format, arguments);
  va_end(arguments);
  return false;
}

/* Appends one chunk of the answer; stops the transfer past the limit. */
static size_t receive(char *data, size_t size, size_t count, void *context) {
  response_buffer *buffer = context;
  size_t length = size * count;
  if (RESPONSE_BYTE_LIMIT - buffer->length < length) {
    buffer->too_large = true;
    return 0;
  }
  char *bytes = realloc(buffer->bytes, buffer->length + length + 1);
  if (!bytes)
    return 0;
  memcpy(bytes + buffer->length, data, length);
  buffer->bytes = bytes;
  buffer->length += length;
  bytes[buffer->length] = 0;
  return length;
}

/* Sends one request with libcurl. The process calls curl_global_init once at
 * start, before any thread. */
bool ur_embed_curl_http(void *context, const ur_embed_http_request *request,
                        ur_embed_http_response *response, char *error,
                        size_t capacity) {
  (void)context;
  memset(response, 0, sizeof(*response));
  CURL *curl = curl_easy_init();
  if (!curl)
    return fail(error, capacity, "libcurl did not start");
  struct curl_slist *headers = NULL;
  char *authorization = NULL;
  if (request->authorization) {
    size_t size = strlen(request->authorization) + 23;
    authorization = malloc(size);
    if (authorization) {
      snprintf(authorization, size, "Authorization: Bearer %s",
               request->authorization);
      headers = curl_slist_append(headers, authorization);
      /* curl keeps its own copy of the header */
      memset(authorization, 0, size);
      free(authorization);
    }
  }
  if (request->body)
    headers = curl_slist_append(headers, "Content-Type: application/json");
  headers = curl_slist_append(headers, "Accept: application/json");
  response_buffer buffer = {0};
  curl_easy_setopt(curl, CURLOPT_URL, request->url);
  curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
  curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 10L);
  curl_easy_setopt(curl, CURLOPT_TIMEOUT, 15L);
#if LIBCURL_VERSION_NUM >= 0x075500
  curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http,https");
#endif
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, receive);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &buffer);
  if (request->body) {
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, request->body);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(request->body));
  } else {
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
  }
  CURLcode result = curl_easy_perform(curl);
  long status = 0;
  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
  curl_slist_free_all(headers);
  curl_easy_cleanup(curl);
  if (buffer.too_large) {
    free(buffer.bytes);
    return fail(error, capacity, "the answer is too large");
  }
  if (result != CURLE_OK) {
    free(buffer.bytes);
    return fail(error, capacity, "%s", curl_easy_strerror(result));
  }
  response->status = status;
  response->body = buffer.bytes ? buffer.bytes : calloc(1, 1);
  response->body_length = buffer.length;
  if (!response->body)
    return fail(error, capacity, "out of memory");
  return true;
}
