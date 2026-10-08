/* Installation state for one embed installation, kept in one private
 * directory named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md,
 * "Installation state"):
 *
 *   client.jwt    the scoped client JWT: written by the token fetch, by the
 *                 backend tool's provision or by the developer, and rewritten
 *                 by the app whenever the sdk refreshes the token
 *   instance-id   this installation's uuid, created on first run; it is also
 *                 the installation ID the app sends to the token server
 *   logs/         the sdk's bounded log files
 *
 * Every file is replaced atomically with owner-only permissions. On POSIX the
 * directory and its files must not be accessible to group or others, and a
 * symlinked file is refused; Windows relies on the access control of the
 * user's profile directory. Paths are utf-8; the Windows branches convert
 * them for the wide file functions. Base64, uuids and the jwt payload are
 * decoded here by hand. The file functions are the C provider example's. */
#include "embed.h"
#include <ctype.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>

#ifdef _WIN32
#include <bcrypt.h>
#define UR_EMBED_SEPARATOR "\\"
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#define UR_EMBED_SEPARATOR "/"
#endif

static const char standard_alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static const char url_alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/* Writes the message to error and returns false. */
static bool fail(char *error, size_t capacity, const char *format, ...) {
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(error, capacity, format, arguments);
  va_end(arguments);
  return false;
}

#ifdef _WIN32
/* A utf-8 string as the wide string the Windows functions take, owned; NULL
 * when it does not convert. */
static wchar_t *wide_string(const char *text) {
  int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1,
                                  NULL, 0);
  if (count <= 0)
    return NULL;
  wchar_t *wide = malloc((size_t)count * sizeof(*wide));
  if (wide && MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1,
                                  wide, count) <= 0) {
    free(wide);
    wide = NULL;
  }
  return wide;
}

/* A wide string as utf-8, owned; NULL when it does not convert. */
static char *utf8_string(const wchar_t *wide) {
  int size = WideCharToMultiByte(CP_UTF8, 0, wide, -1, NULL, 0, NULL, NULL);
  if (size <= 0)
    return NULL;
  char *text = malloc((size_t)size);
  if (text &&
      WideCharToMultiByte(CP_UTF8, 0, wide, -1, text, size, NULL, NULL) <= 0) {
    free(text);
    text = NULL;
  }
  return text;
}

/* The system's text for a Windows error code. */
static const char *windows_error_text(DWORD code, char *text,
                                      size_t capacity) {
  DWORD length = FormatMessageA(
      FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL, code,
      0, text, (DWORD)capacity, NULL);
  while (0 < length && isspace((unsigned char)text[length - 1]))
    length--;
  if (length == 0)
    snprintf(text, capacity, "Windows error %lu", (unsigned long)code);
  else
    text[length] = 0;
  return text;
}
#endif

/* The value of an environment variable as utf-8, owned; NULL when it is not
 * set. */
char *ur_embed_environment(const char *name) {
#ifdef _WIN32
  wchar_t *wide_name = wide_string(name);
  const wchar_t *value = wide_name ? _wgetenv(wide_name) : NULL;
  char *text = value ? utf8_string(value) : NULL;
  free(wide_name);
  return text;
#else
  return ur_embed_copy_string(getenv(name));
#endif
}

/* Whether path is absolute, as Go's filepath.IsAbs decides: on Windows a
 * drive letter with a separator, or a UNC path. */
static bool is_absolute_path(const char *path) {
#ifdef _WIN32
  if (isalpha((unsigned char)path[0]) && path[1] == ':')
    return path[2] == '\\' || path[2] == '/';
  return (path[0] == '\\' || path[0] == '/') &&
         (path[1] == '\\' || path[1] == '/');
#else
  return path[0] == '/';
#endif
}

/* Joins a directory and a file name. False when the path does not fit. */
bool ur_embed_path_join(char *path, size_t capacity, const char *dir,
                           const char *name) {
  size_t length = strlen(dir);
  bool separated = 0 < length && (dir[length - 1] == '/'
#ifdef _WIN32
                                  || dir[length - 1] == '\\'
#endif
                                 );
  int written = snprintf(path, capacity, "%s%s%s", dir,
                         separated ? "" : UR_EMBED_SEPARATOR, name);
  return 0 <= written && (size_t)written < capacity;
}

/* The state directory must be an existing absolute directory, private to its
 * owner on POSIX. */
bool ur_embed_check_state_dir(const char *state_dir, char *error,
                                 size_t capacity) {
  if (!state_dir || !*state_dir)
    return fail(error, capacity,
                "set URNETWORK_EMBED_STATE_DIR to this installation's "
                "private state directory");
  if (!is_absolute_path(state_dir))
    return fail(error, capacity,
                "URNETWORK_EMBED_STATE_DIR must be an absolute path");
  if (UR_EMBED_PATH_CAPACITY / 2 < strlen(state_dir))
    return fail(error, capacity, "URNETWORK_EMBED_STATE_DIR is too long");
#ifdef _WIN32
  wchar_t *wide = wide_string(state_dir);
  DWORD attributes = wide ? GetFileAttributesW(wide) : INVALID_FILE_ATTRIBUTES;
  DWORD code = GetLastError();
  free(wide);
  if (attributes == INVALID_FILE_ATTRIBUTES) {
    char text[256];
    return fail(error, capacity, "state directory: %s",
                windows_error_text(code, text, sizeof(text)));
  }
  if (!(attributes & FILE_ATTRIBUTE_DIRECTORY))
    return fail(error, capacity,
                "URNETWORK_EMBED_STATE_DIR is not a directory");
#else
  struct stat info;
  if (stat(state_dir, &info))
    return fail(error, capacity, "state directory: %s", strerror(errno));
  if (!S_ISDIR(info.st_mode))
    return fail(error, capacity,
                "URNETWORK_EMBED_STATE_DIR is not a directory");
  if (info.st_mode & 077)
    return fail(error, capacity,
                "the state directory must be private to its owner (chmod 700)");
#endif
  return true;
}

/* Reads a regular, private state file of bounded size into data, with a
 * terminator after its bytes. A symlink is refused so that the credential
 * cannot be redirected to another file. A missing file returns
 * UR_EMBED_READ_MISSING with the system's message in error. */
ur_embed_read_result ur_embed_read_private_file(const char *path,
                                                      ur_bytes *data,
                                                      char *error,
                                                      size_t capacity) {
  data->data = NULL;
  data->length = 0;
  uint8_t *buffer = malloc(UR_EMBED_STATE_FILE_BYTE_LIMIT + 2);
  if (!buffer) {
    fail(error, capacity, "out of memory");
    return UR_EMBED_READ_FAILED;
  }
  size_t length = 0;
#ifdef _WIN32
  char text[256];
  wchar_t *wide = wide_string(path);
  WIN32_FILE_ATTRIBUTE_DATA info;
  if (!wide || !GetFileAttributesExW(wide, GetFileExInfoStandard, &info)) {
    DWORD code = wide ? GetLastError() : ERROR_INVALID_NAME;
    free(wide);
    free(buffer);
    fail(error, capacity, "%s", windows_error_text(code, text, sizeof(text)));
    return code == ERROR_FILE_NOT_FOUND || code == ERROR_PATH_NOT_FOUND
               ? UR_EMBED_READ_MISSING
               : UR_EMBED_READ_FAILED;
  }
  bool regular =
      !(info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY |
                                 FILE_ATTRIBUTE_REPARSE_POINT |
                                 FILE_ATTRIBUTE_DEVICE));
  bool too_large = info.nFileSizeHigh ||
                   UR_EMBED_STATE_FILE_BYTE_LIMIT < info.nFileSizeLow;
  HANDLE file = INVALID_HANDLE_VALUE;
  if (regular && !too_large)
    file = CreateFileW(wide, GENERIC_READ, FILE_SHARE_READ, NULL,
                       OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, NULL);
  DWORD code = GetLastError();
  free(wide);
  if (!regular || too_large || file == INVALID_HANDLE_VALUE) {
    free(buffer);
    fail(error, capacity, "%s",
         !regular    ? "not a regular file"
         : too_large ? "file is too large"
                     : windows_error_text(code, text, sizeof(text)));
    return UR_EMBED_READ_FAILED;
  }
  bool read_failed = false;
  for (;;) {
    DWORD count = 0;
    if (!ReadFile(file, buffer + length,
                  (DWORD)(UR_EMBED_STATE_FILE_BYTE_LIMIT + 1 - length),
                  &count, NULL)) {
      read_failed = true;
      code = GetLastError();
      break;
    }
    if (count == 0 || UR_EMBED_STATE_FILE_BYTE_LIMIT < length + count) {
      length += count;
      break;
    }
    length += count;
  }
  CloseHandle(file);
  if (read_failed) {
    free(buffer);
    fail(error, capacity, "%s", windows_error_text(code, text, sizeof(text)));
    return UR_EMBED_READ_FAILED;
  }
#else
  struct stat info;
  if (lstat(path, &info)) {
    int error_number = errno;
    free(buffer);
    fail(error, capacity, "%s", strerror(error_number));
    return error_number == ENOENT ? UR_EMBED_READ_MISSING
                                  : UR_EMBED_READ_FAILED;
  }
  const char *refusal = NULL;
  if (!S_ISREG(info.st_mode))
    refusal = "not a regular file";
  else if (UR_EMBED_STATE_FILE_BYTE_LIMIT < info.st_size)
    refusal = "file is too large";
  else if (info.st_mode & 077)
    refusal = "file must be private to its owner (chmod 600)";
  if (refusal) {
    free(buffer);
    fail(error, capacity, "%s", refusal);
    return UR_EMBED_READ_FAILED;
  }
  /* O_NOFOLLOW also refuses a symlink swapped in after the check */
  int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
  if (fd < 0) {
    free(buffer);
    fail(error, capacity, "%s", strerror(errno));
    return UR_EMBED_READ_FAILED;
  }
  for (;;) {
    ssize_t count =
        read(fd, buffer + length, UR_EMBED_STATE_FILE_BYTE_LIMIT + 1 - length);
    if (count < 0 && errno == EINTR)
      continue;
    if (count < 0) {
      int error_number = errno;
      close(fd);
      free(buffer);
      fail(error, capacity, "%s", strerror(error_number));
      return UR_EMBED_READ_FAILED;
    }
    length += (size_t)count;
    if (count == 0 || UR_EMBED_STATE_FILE_BYTE_LIMIT < length)
      break;
  }
  close(fd);
#endif
  if (UR_EMBED_STATE_FILE_BYTE_LIMIT < length) {
    free(buffer);
    fail(error, capacity, "file is too large");
    return UR_EMBED_READ_FAILED;
  }
  buffer[length] = 0;
  data->data = buffer;
  data->length = length;
  return UR_EMBED_READ_OK;
}

/* Replaces a state file atomically: a private temporary file in the same
 * directory is written, synced and renamed over the old file. */
bool ur_embed_write_private_file(const char *path, const void *data,
                                    size_t length, char *error,
                                    size_t capacity) {
  /* the temporary file is ".<name>.<random>" beside the file */
  const char *name = strrchr(path, '/');
#ifdef _WIN32
  const char *backslash = strrchr(path, '\\');
  if (!name || (backslash && name < backslash))
    name = backslash;
#endif
  if (!name)
    return fail(error, capacity, "%s is not an absolute path", path);
  name += 1;
  char temp_path[UR_EMBED_PATH_CAPACITY];
#ifdef _WIN32
  uint8_t random[8];
  if (!ur_embed_random_bytes(random, sizeof(random)))
    return fail(error, capacity, "cannot read the system random source");
  int written = snprintf(
      temp_path, sizeof(temp_path), "%.*s.%s.%02x%02x%02x%02x%02x%02x%02x%02x",
      (int)(name - path), path, name, random[0], random[1], random[2],
      random[3], random[4], random[5], random[6], random[7]);
  if (written < 0 || sizeof(temp_path) <= (size_t)written)
    return fail(error, capacity, "the path is too long");
  wchar_t *wide_temp = wide_string(temp_path);
  wchar_t *wide = wide_string(path);
  /* the first failure's code; ERROR_SUCCESS while every step succeeds */
  DWORD code = ERROR_SUCCESS;
  HANDLE file = INVALID_HANDLE_VALUE;
  if (!wide_temp || !wide) {
    code = ERROR_INVALID_NAME;
  } else {
    file = CreateFileW(wide_temp, GENERIC_WRITE, 0, NULL, CREATE_NEW,
                       FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE)
      code = GetLastError();
  }
  const uint8_t *bytes = data;
  for (size_t offset = 0; code == ERROR_SUCCESS && offset < length;) {
    DWORD count = 0;
    DWORD chunk = (DWORD)(length - offset < 1024 * 1024 ? length - offset
                                                        : 1024 * 1024);
    if (!WriteFile(file, bytes + offset, chunk, &count, NULL))
      code = GetLastError();
    else if (count == 0)
      code = ERROR_WRITE_FAULT;
    offset += count;
  }
  if (code == ERROR_SUCCESS && !FlushFileBuffers(file))
    code = GetLastError();
  if (file != INVALID_HANDLE_VALUE) {
    CloseHandle(file);
    if (code == ERROR_SUCCESS &&
        !MoveFileExW(wide_temp, wide,
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
      code = GetLastError();
    if (code != ERROR_SUCCESS)
      DeleteFileW(wide_temp);
  }
  free(wide_temp);
  free(wide);
  if (code != ERROR_SUCCESS) {
    char text[256];
    return fail(error, capacity, "%s",
                windows_error_text(code, text, sizeof(text)));
  }
  return true;
#else
  int written = snprintf(temp_path, sizeof(temp_path), "%.*s.%s.XXXXXX",
                         (int)(name - path), path, name);
  if (written < 0 || sizeof(temp_path) <= (size_t)written)
    return fail(error, capacity, "the path is too long");
  /* mkstemp creates the file with mode 0600 */
  int fd = mkstemp(temp_path);
  if (fd < 0)
    return fail(error, capacity, "%s", strerror(errno));
  bool ok = fchmod(fd, 0600) == 0;
  const uint8_t *bytes = data;
  for (size_t offset = 0; ok && offset < length;) {
    ssize_t count = write(fd, bytes + offset, length - offset);
    if (count < 0 && errno == EINTR)
      continue;
    ok = 0 < count;
    if (ok)
      offset += (size_t)count;
  }
  ok = ok && fsync(fd) == 0;
  int error_number = errno;
  if (close(fd) && ok) {
    ok = false;
    error_number = errno;
  }
  if (ok && rename(temp_path, path)) {
    ok = false;
    error_number = errno;
  }
  if (!ok) {
    unlink(temp_path);
    return fail(error, capacity, "%s", strerror(error_number));
  }
  return true;
#endif
}

/* Creates a directory private to its owner; an existing one is kept. */
bool ur_embed_make_private_dir(const char *path, char *error,
                                  size_t capacity) {
#ifdef _WIN32
  wchar_t *wide = wide_string(path);
  bool made = wide && CreateDirectoryW(wide, NULL);
  DWORD code = wide ? GetLastError() : ERROR_INVALID_NAME;
  free(wide);
  if (!made && code != ERROR_ALREADY_EXISTS) {
    char text[256];
    return fail(error, capacity, "%s",
                windows_error_text(code, text, sizeof(text)));
  }
#else
  if (mkdir(path, 0700) && errno != EEXIST)
    return fail(error, capacity, "%s", strerror(errno));
#endif
  return true;
}

/* Creates a new private directory in the system temp directory, for the
 * self-test. */
bool ur_embed_make_temp_dir(char *path, size_t capacity) {
#ifdef _WIN32
  wchar_t temp[MAX_PATH + 1];
  DWORD length = GetTempPathW(MAX_PATH + 1, temp);
  if (length == 0 || MAX_PATH < length)
    return false;
  char *temp_dir = utf8_string(temp);
  uint8_t random[8];
  bool ok = temp_dir && ur_embed_random_bytes(random, sizeof(random));
  if (ok) {
    int written = snprintf(
        path, capacity, "%sur-embed-self-test-%02x%02x%02x%02x%02x%02x%02x%02x",
        temp_dir, random[0], random[1], random[2], random[3], random[4],
        random[5], random[6], random[7]);
    ok = 0 <= written && (size_t)written < capacity;
  }
  free(temp_dir);
  if (!ok)
    return false;
  wchar_t *wide = wide_string(path);
  ok = wide && CreateDirectoryW(wide, NULL);
  free(wide);
  return ok;
#else
  const char *temp_dir = getenv("TMPDIR");
  if (!temp_dir || !*temp_dir)
    temp_dir = "/tmp";
  if (!ur_embed_path_join(path, capacity, temp_dir,
                             "ur-embed-self-test-XXXXXX"))
    return false;
  /* mkdtemp creates the directory with mode 0700 */
  return mkdtemp(path) != NULL;
#endif
}

/* Removes a file, or an empty directory. */
bool ur_embed_remove_path(const char *path, bool dir) {
#ifdef _WIN32
  wchar_t *wide = wide_string(path);
  bool removed = wide && (dir ? RemoveDirectoryW(wide) : DeleteFileW(wide));
  free(wide);
  return removed;
#else
  return (dir ? rmdir(path) : unlink(path)) == 0;
#endif
}

/* Fills data from the operating system's random source. */
bool ur_embed_random_bytes(uint8_t *data, size_t length) {
#ifdef _WIN32
  return BCRYPT_SUCCESS(BCryptGenRandom(NULL, data, (ULONG)length,
                                        BCRYPT_USE_SYSTEM_PREFERRED_RNG));
#else
  int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return false;
  size_t offset = 0;
  while (offset < length) {
    ssize_t count = read(fd, data + offset, length - offset);
    if (count < 0 && errno == EINTR)
      continue;
    if (count <= 0)
      break;
    offset += (size_t)count;
  }
  close(fd);
  return offset == length;
#endif
}

/* Decodes standard base64 with padding, or with url the url alphabet
 * without padding as jwt segments use it (trailing "=" are ignored there, as
 * the Go reference trims them). False for any other text. */
bool ur_embed_base64_decode(const char *text, size_t length, bool url,
                               ur_bytes *data) {
  const char *alphabet = url ? url_alphabet : standard_alphabet;
  data->data = NULL;
  data->length = 0;
  size_t symbol_count = length;
  if (url) {
    while (0 < symbol_count && text[symbol_count - 1] == '=')
      symbol_count--;
    if (symbol_count % 4 == 1)
      return false;
  } else {
    if (length % 4)
      return false;
    for (int padding = 0; padding < 2 && 0 < symbol_count &&
                          text[symbol_count - 1] == '=';
         padding++)
      symbol_count--;
  }
  uint8_t *bytes = malloc(symbol_count * 3 / 4 + 1);
  if (!bytes)
    return false;
  size_t byte_count = 0;
  uint32_t group = 0;
  for (size_t i = 0; i < symbol_count; i++) {
    const char *symbol = text[i] ? strchr(alphabet, text[i]) : NULL;
    if (!symbol) {
      free(bytes);
      return false;
    }
    group = group << 6 | (uint32_t)(symbol - alphabet);
    if (i % 4 == 3) {
      bytes[byte_count++] = (uint8_t)(group >> 16);
      bytes[byte_count++] = (uint8_t)(group >> 8);
      bytes[byte_count++] = (uint8_t)group;
      group = 0;
    }
  }
  /* a partial group of 2 or 3 symbols carries 1 or 2 bytes */
  if (symbol_count % 4 == 2) {
    bytes[byte_count++] = (uint8_t)(group >> 4);
  } else if (symbol_count % 4 == 3) {
    bytes[byte_count++] = (uint8_t)(group >> 10);
    bytes[byte_count++] = (uint8_t)(group >> 2);
  } else if (!url && symbol_count % 4 == 1) {
    free(bytes);
    return false;
  }
  if (byte_count == 0) {
    free(bytes);
    bytes = NULL;
  }
  data->data = bytes;
  data->length = byte_count;
  return true;
}

/* Reads a uuid in its 8-4-4-4-12 hex form, in either case, into its canonical
 * lowercase form. */
bool ur_embed_parse_uuid(const char *text, size_t length,
                            char uuid[UR_EMBED_ID_CAPACITY]) {
  if (length != 36)
    return false;
  for (size_t i = 0; i < 36; i++) {
    bool dash = i == 8 || i == 13 || i == 18 || i == 23;
    if (dash ? text[i] != '-' : !isxdigit((unsigned char)text[i]))
      return false;
    uuid[i] = (char)tolower((unsigned char)text[i]);
  }
  uuid[36] = 0;
  return true;
}

/* A new random (version 4) uuid from the operating system's random source. */
bool ur_embed_new_uuid(char uuid[UR_EMBED_ID_CAPACITY]) {
  uint8_t bytes[16];
  if (!ur_embed_random_bytes(bytes, sizeof(bytes)))
    return false;
  bytes[6] = (uint8_t)((bytes[6] & 0x0f) | 0x40);
  bytes[8] = (uint8_t)((bytes[8] & 0x3f) | 0x80);
  snprintf(uuid, UR_EMBED_ID_CAPACITY,
           "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5],
           bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11],
           bytes[12], bytes[13], bytes[14], bytes[15]);
  return true;
}

/* The client_id claim of a scoped client JWT, in canonical form. This checks
 * the token's shape and claim only; the sdk and the server verify the token
 * itself. */
bool ur_embed_parse_client_jwt_client_id(
    const char *client_jwt, char client_id[UR_EMBED_ID_CAPACITY],
    char *error, size_t capacity) {
  const char *not_jwt = "client.jwt does not hold a JWT; obtain the scoped "
                        "client JWT from your backend";
  /* three non-empty parts separated by dots */
  const char *first_dot = strchr(client_jwt, '.');
  const char *second_dot = first_dot ? strchr(first_dot + 1, '.') : NULL;
  if (!second_dot || strchr(second_dot + 1, '.') || first_dot == client_jwt ||
      second_dot == first_dot + 1 || !second_dot[1])
    return fail(error, capacity, "%s", not_jwt);
  ur_bytes payload;
  if (!ur_embed_base64_decode(first_dot + 1,
                                 (size_t)(second_dot - first_dot - 1), true,
                                 &payload))
    return fail(error, capacity, "%s", not_jwt);
  ur_json_value claims =
      ur_json_parse((const char *)payload.data, payload.length);
  char *claim = ur_json_string(ur_json_member(claims, "client_id"));
  ur_bytes_free(&payload);
  if (!claim || !*claim) {
    free(claim);
    return fail(error, capacity,
                "client.jwt has no client_id claim; write a scoped client JWT, "
                "not a network JWT");
  }
  bool valid = ur_embed_parse_uuid(claim, strlen(claim), client_id);
  free(claim);
  if (!valid)
    return fail(error, capacity, "client.jwt has an invalid client_id claim");
  return true;
}

/* The text without the whitespace around it: start and length. */
static void trim_space(const char *text, size_t length, const char **start,
                       size_t *trimmed_length) {
  while (0 < length && isspace((unsigned char)*text)) {
    text++;
    length--;
  }
  while (0 < length && isspace((unsigned char)text[length - 1]))
    length--;
  *start = text;
  *trimmed_length = length;
}

/* Reads instance-id, creating it on first run. An installation keeps one
 * instance id for its lifetime. */
bool ur_embed_load_or_create_instance_id(
    const char *state_dir, char instance_id[UR_EMBED_ID_CAPACITY],
    char *error, size_t capacity) {
  char path[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_path_join(path, sizeof(path), state_dir,
                             UR_EMBED_INSTANCE_ID_FILE_NAME))
    return fail(error, capacity, "the state directory path is too long");
  ur_bytes data;
  char read_error[UR_EMBED_ERROR_CAPACITY];
  ur_embed_read_result read_result = ur_embed_read_private_file(
      path, &data, read_error, sizeof(read_error));
  if (read_result == UR_EMBED_READ_OK) {
    const char *text;
    size_t length;
    trim_space((const char *)data.data, data.length, &text, &length);
    bool valid = ur_embed_parse_uuid(text, length, instance_id);
    ur_bytes_free(&data);
    if (!valid)
      return fail(error, capacity, "%s does not hold a UUID",
                  UR_EMBED_INSTANCE_ID_FILE_NAME);
    return true;
  }
  if (read_result == UR_EMBED_READ_FAILED)
    return fail(error, capacity, "read %s from the state directory: %s",
                UR_EMBED_INSTANCE_ID_FILE_NAME, read_error);
  if (!ur_embed_new_uuid(instance_id))
    return fail(error, capacity, "cannot read the system random source");
  char line[UR_EMBED_ID_CAPACITY + 1];
  snprintf(line, sizeof(line), "%s\n", instance_id);
  char write_error[UR_EMBED_ERROR_CAPACITY];
  if (!ur_embed_write_private_file(path, line, strlen(line), write_error,
                                      sizeof(write_error)))
    return fail(error, capacity, "write %s: %s",
                UR_EMBED_INSTANCE_ID_FILE_NAME, write_error);
  return true;
}

/* Reads client.jwt and its client_id claim. A missing file returns
 * UR_EMBED_READ_MISSING; a file that cannot be read, or holds no client JWT (a
 * network JWT has no client_id claim), returns UR_EMBED_READ_FAILED with the
 * reason in error. The token itself is never printed. */
ur_embed_read_result ur_embed_load_client_jwt(
    const char *state_dir, char **client_jwt,
    char client_id[UR_EMBED_ID_CAPACITY], char *error, size_t capacity) {
  *client_jwt = NULL;
  char path[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_path_join(path, sizeof(path), state_dir,
                          UR_EMBED_CLIENT_JWT_FILE_NAME)) {
    fail(error, capacity, "the state directory path is too long");
    return UR_EMBED_READ_FAILED;
  }
  ur_bytes data;
  char read_error[UR_EMBED_ERROR_CAPACITY];
  ur_embed_read_result read_result =
      ur_embed_read_private_file(path, &data, read_error, sizeof(read_error));
  if (read_result == UR_EMBED_READ_MISSING)
    return UR_EMBED_READ_MISSING;
  if (read_result == UR_EMBED_READ_FAILED) {
    fail(error, capacity, "read %s from the state directory: %s",
         UR_EMBED_CLIENT_JWT_FILE_NAME, read_error);
    return UR_EMBED_READ_FAILED;
  }
  const char *text;
  size_t length;
  trim_space((const char *)data.data, data.length, &text, &length);
  /* a NUL byte would cut the token short as a C string */
  char *token = memchr(text, 0, length) ? NULL : malloc(length + 1);
  if (token) {
    memcpy(token, text, length);
    token[length] = 0;
  }
  ur_bytes_free(&data);
  if (!token) {
    fail(error, capacity,
         "client.jwt does not hold a JWT; obtain the scoped client JWT from "
         "your backend");
    return UR_EMBED_READ_FAILED;
  }
  if (!ur_embed_parse_client_jwt_client_id(token, client_id, error,
                                           capacity)) {
    free(token);
    return UR_EMBED_READ_FAILED;
  }
  *client_jwt = token;
  return UR_EMBED_READ_OK;
}

/* Replaces client.jwt atomically with the token and a line break. */
bool ur_embed_save_client_jwt(const char *state_dir, const char *client_jwt,
                              char *error, size_t capacity) {
  char path[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_path_join(path, sizeof(path), state_dir,
                          UR_EMBED_CLIENT_JWT_FILE_NAME))
    return fail(error, capacity, "the state directory path is too long");
  size_t length = strlen(client_jwt);
  char *line = malloc(length + 2);
  if (!line)
    return fail(error, capacity, "out of memory");
  memcpy(line, client_jwt, length);
  line[length] = '\n';
  bool saved =
      ur_embed_write_private_file(path, line, length + 1, error, capacity);
  free(line);
  return saved;
}
