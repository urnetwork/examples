/* The status that every embed example shows, with the exact text rules of
 * EMBED_CONTRACT.md ("Status"): the status, the data used this month and the
 * running total. The functions are pure and read json values (json.c), so the
 * self-test checks them without the sdk, a network or credentials. */
#include "embed.h"
#include <inttypes.h>
#include <stdio.h>

/* Prints one line on stdout at once, also when stdout is a file or a pipe. */
void ur_embed_print_line(const char *line) {
  fputs(line, stdout);
  fputc('\n', stdout);
  fflush(stdout);
}

/* "embed client <client_id>, installation <instance_id>", printed at start. */
void ur_embed_start_line(const char *client_id, const char *instance_id,
                         char *line, size_t capacity) {
  snprintf(line, capacity, "embed client %s, installation %s", client_id,
           instance_id);
}

/* The GetLicenses app kind that --licenses prints: "apple" on Apple
 * platforms, "windows" on Windows, "linux" elsewhere. */
const char *ur_embed_license_app(void) {
#if defined(_WIN32)
  return "windows";
#elif defined(__APPLE__)
  return "apple";
#else
  return "linux";
#endif
}

/* Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "12.4 GB". Data
 * plans and the Embed plan's monthly data budget are sold in these units. A
 * value that rounds to 1000.0 moves to the next unit. The arithmetic is exact
 * integer arithmetic, so the text does not depend on the C library's float
 * formatting: a tie rounds to even on the exact value, so 1050 bytes is
 * "1.0 kB". */
void ur_embed_format_byte_count(int64_t byte_count, char *text,
                                size_t capacity) {
  static const char *const units[] = {"kB", "MB", "GB", "TB", "PB", "EB"};
  if (byte_count < 1000) {
    snprintf(text, capacity, "%" PRId64 " B", byte_count);
    return;
  }
  uint64_t count = (uint64_t)byte_count;
  uint64_t divisor = 1000;
  size_t unit_index = 0;
  while (unit_index + 1 < sizeof(units) / sizeof(*units)) {
    /* the value rounds to 1000.0 or more from 999.95 up; at exactly 999.95
     * the tie rounds to the even 1000.0 */
    uint64_t whole = count / divisor;
    uint64_t remainder = count % divisor;
    if (whole < 999 || (whole == 999 && 20 * remainder < 19 * divisor))
      break;
    divisor *= 1000;
    unit_index += 1;
  }
  uint64_t scaled = count % divisor * 10;
  uint64_t tenths = count / divisor * 10 + scaled / divisor;
  uint64_t remainder = scaled % divisor;
  if (divisor < 2 * remainder || (divisor == 2 * remainder && tenths % 2 == 1))
    tenths += 1;
  snprintf(text, capacity, "%" PRIu64 ".%" PRIu64 " %s", tenths / 10,
           tenths % 10, units[unit_index]);
}

/* Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's
 * days_from_civil). */
static int64_t days_from_civil(int64_t year, int month, int day) {
  year -= month <= 2;
  int64_t era = (year >= 0 ? year : year - 399) / 400;
  int64_t year_of_era = year - era * 400;
  int64_t day_of_year = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
  int64_t day_of_era =
      year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
  return era * 146097 + day_of_era - 719468;
}

/* The date of a day since 1970-01-01 (Howard Hinnant's civil_from_days). */
static void civil_from_days(int64_t days, int64_t *year, int *month,
                            int *day) {
  days += 719468;
  int64_t era = (days >= 0 ? days : days - 146096) / 146097;
  int64_t day_of_era = days - era * 146097;
  int64_t year_of_era = (day_of_era - day_of_era / 1460 + day_of_era / 36524 -
                         day_of_era / 146096) /
                        365;
  int64_t day_of_year =
      day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
  int64_t month_index = (5 * day_of_year + 2) / 153;
  *day = (int)(day_of_year - (153 * month_index + 2) / 5 + 1);
  *month = (int)(month_index < 10 ? month_index + 3 : month_index - 9);
  *year = year_of_era + era * 400 + (*month <= 2);
}

/* Reads count decimal digits at text. */
static bool read_digits(const char *text, int count, int *value) {
  *value = 0;
  for (int i = 0; i < count; i++) {
    if (text[i] < '0' || '9' < text[i])
      return false;
    *value = *value * 10 + (text[i] - '0');
  }
  return true;
}

/* Reads an RFC 3339 time, "2026-11-01T00:00:00Z" or with a fraction and an
 * offset such as "2026-10-31T19:00:00.5-05:00", as unix seconds in UTC, and
 * whether its fraction is above zero. */
static bool parse_rfc3339(const char *text, int64_t *unix_seconds,
                          bool *has_fraction) {
  int year, month, day, hour, minute, second;
  if (strlen(text) < 20 || !read_digits(text, 4, &year) || text[4] != '-' ||
      !read_digits(text + 5, 2, &month) || text[7] != '-' ||
      !read_digits(text + 8, 2, &day) || (text[10] != 'T' && text[10] != 't') ||
      !read_digits(text + 11, 2, &hour) || text[13] != ':' ||
      !read_digits(text + 14, 2, &minute) || text[16] != ':' ||
      !read_digits(text + 17, 2, &second))
    return false;
  static const int month_days[] = {31, 29, 31, 30, 31, 30,
                                   31, 31, 30, 31, 30, 31};
  bool leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
  if (month < 1 || 12 < month || day < 1 || month_days[month - 1] < day ||
      (month == 2 && day == 29 && !leap) || 23 < hour || 59 < minute ||
      59 < second)
    return false;
  const char *p = text + 19;
  *has_fraction = false;
  if (*p == '.') {
    p++;
    if (*p < '0' || '9' < *p)
      return false;
    for (; '0' <= *p && *p <= '9'; p++) {
      if (*p != '0')
        *has_fraction = true;
    }
  }
  int64_t offset_seconds = 0;
  if ((*p == 'Z' || *p == 'z') && p[1] == 0) {
    offset_seconds = 0;
  } else if ((*p == '+' || *p == '-') && strlen(p) == 6 && p[3] == ':') {
    int offset_hour, offset_minute;
    if (!read_digits(p + 1, 2, &offset_hour) ||
        !read_digits(p + 4, 2, &offset_minute) || 23 < offset_hour ||
        59 < offset_minute)
      return false;
    offset_seconds = (int64_t)offset_hour * 3600 + offset_minute * 60;
    if (*p == '-')
      offset_seconds = -offset_seconds;
  } else {
    return false;
  }
  *unix_seconds = days_from_civil(year, month, day) * 86400 +
                  (int64_t)hour * 3600 + minute * 60 + second - offset_seconds;
  return true;
}

/* "resets YYYY-MM-DD HH:MM UTC" for the monthly cap's period end, in UTC with
 * the seconds rounded up to the next whole minute, so the shown time is never
 * before the real reset. False when the time does not parse. */
bool ur_embed_reset_text(const char *period_end, char *text, size_t capacity) {
  int64_t unix_seconds;
  bool has_fraction;
  if (!period_end || !parse_rfc3339(period_end, &unix_seconds, &has_fraction))
    return false;
  int64_t minutes = unix_seconds / 60 - (unix_seconds % 60 < 0);
  if (unix_seconds - minutes * 60 != 0 || has_fraction)
    minutes += 1;
  int64_t days = minutes / 1440 - (minutes % 1440 < 0);
  int minute_of_day = (int)(minutes - days * 1440);
  int64_t year;
  int month, day;
  civil_from_days(days, &year, &month, &day);
  snprintf(text, capacity, "resets %04" PRId64 "-%02d-%02d %02d:%02d UTC", year,
           month, day, minute_of_day / 60, minute_of_day % 60);
  return true;
}

/* "client limit, retry at HH:MM UTC": the retry time (unix milliseconds)
 * rounded up to the next whole minute in UTC, so the shown time is never
 * before the real retry; a retry time of 0 shows "client limit". Unix time has
 * no leap seconds, so every day is 1440 minutes. */
void ur_embed_client_limit_text(int64_t retry_time, char *text,
                                size_t capacity) {
  if (retry_time <= 0) {
    snprintf(text, capacity, "%s", UR_EMBED_STATUS_CLIENT_LIMIT);
    return;
  }
  int64_t minute_millis = 60 * 1000;
  int64_t retry_minute =
      retry_time / minute_millis + (retry_time % minute_millis != 0);
  int minute_of_day = (int)(retry_minute % (24 * 60));
  snprintf(text, capacity, "%s, retry at %02d:%02d UTC",
           UR_EMBED_STATUS_CLIENT_LIMIT, minute_of_day / 60,
           minute_of_day % 60);
}

/* One optional byte limit: a number, or null or absent for no cap. */
static bool read_limit(ur_json_value object, const char *name, bool *has_limit,
                       int64_t *limit) {
  ur_json_value value = ur_json_member(object, name);
  *has_limit = false;
  *limit = 0;
  if (value.type == UR_JSON_NONE || value.type == UR_JSON_NULL)
    return true;
  *has_limit = true;
  return ur_json_int64(value, limit);
}

/* One used count: a number, or 0 when null or absent. */
static bool read_used(ur_json_value object, const char *name, int64_t *used) {
  ur_json_value value = ur_json_member(object, name);
  *used = 0;
  if (value.type == UR_JSON_NONE || value.type == UR_JSON_NULL)
    return true;
  return ur_json_int64(value, used);
}

/* One string field into a buffer: empty when null or absent, and empty when it
 * does not fit, which no reader then mistakes for a valid value. */
static bool read_text(ur_json_value object, const char *name, char *text,
                      size_t capacity, bool cut) {
  ur_json_value value = ur_json_member(object, name);
  text[0] = 0;
  if (value.type == UR_JSON_NONE || value.type == UR_JSON_NULL)
    return true;
  char *string = ur_json_string(value);
  if (!string)
    return false;
  if (strlen(string) < capacity || cut)
    snprintf(text, capacity, "%s", string);
  free(string);
  return true;
}

/* Reads a cap object. False for anything else: not an object, an error
 * answer, or a field of the wrong type. */
bool ur_embed_parse_cap_value(ur_json_value value, ur_embed_cap *cap) {
  memset(cap, 0, sizeof(*cap));
  if (value.type != UR_JSON_OBJECT)
    return false;
  ur_json_type error_type = ur_json_member(value, "error").type;
  if (error_type != UR_JSON_NONE && error_type != UR_JSON_NULL)
    return false;
  ur_json_value capped = ur_json_member(value, "capped");
  if (capped.type == UR_JSON_TRUE)
    cap->capped = true;
  else if (capped.type != UR_JSON_FALSE && capped.type != UR_JSON_NONE &&
           capped.type != UR_JSON_NULL)
    return false;
  return read_limit(value, "monthly_byte_limit", &cap->has_monthly_byte_limit,
                    &cap->monthly_byte_limit) &&
         read_used(value, "monthly_used_byte_count",
                   &cap->monthly_used_byte_count) &&
         read_text(value, "monthly_period_end", cap->monthly_period_end,
                   sizeof(cap->monthly_period_end), false) &&
         read_limit(value, "total_byte_limit", &cap->has_total_byte_limit,
                    &cap->total_byte_limit) &&
         read_used(value, "total_used_byte_count",
                   &cap->total_used_byte_count) &&
         read_text(value, "capped_reason", cap->capped_reason,
                   sizeof(cap->capped_reason), true);
}

/* Reads a cap object from its json text. */
bool ur_embed_parse_cap(const char *json, size_t length, ur_embed_cap *cap) {
  return ur_embed_parse_cap_value(ur_json_parse(json, length), cap);
}

/* The data fields before any reading: checking. */
void ur_embed_caps_init(ur_embed_caps *caps) {
  memset(caps, 0, sizeof(*caps));
  caps->state = UR_EMBED_CAPS_CHECKING;
}

/* Applies one cap reading; NULL is a failed reading. A reading replaces the
 * last one; a failure before any reading shows unavailable, and a later
 * failure keeps the last reading. */
void ur_embed_caps_apply(ur_embed_caps *caps, const ur_embed_cap *reading) {
  if (reading) {
    caps->state = UR_EMBED_CAPS_READ;
    caps->cap = *reading;
  } else if (caps->state == UR_EMBED_CAPS_CHECKING) {
    caps->state = UR_EMBED_CAPS_UNAVAILABLE;
  }
}

/* One data field: checking, unavailable, no cap for a null limit (never a used
 * count), or "<used> of <limit>". */
void ur_embed_data_field(const ur_embed_caps *caps, bool monthly, char *text,
                         size_t capacity) {
  if (caps->state == UR_EMBED_CAPS_CHECKING) {
    snprintf(text, capacity, "%s", UR_EMBED_DATA_CHECKING);
    return;
  }
  if (caps->state == UR_EMBED_CAPS_UNAVAILABLE) {
    snprintf(text, capacity, "%s", UR_EMBED_DATA_UNAVAILABLE);
    return;
  }
  const ur_embed_cap *cap = &caps->cap;
  bool has_limit =
      monthly ? cap->has_monthly_byte_limit : cap->has_total_byte_limit;
  if (!has_limit) {
    snprintf(text, capacity, "%s", UR_EMBED_DATA_NO_CAP);
    return;
  }
  char used[32];
  char limit[32];
  ur_embed_format_byte_count(
      monthly ? cap->monthly_used_byte_count : cap->total_used_byte_count, used,
      sizeof(used));
  ur_embed_format_byte_count(
      monthly ? cap->monthly_byte_limit : cap->total_byte_limit, limit,
      sizeof(limit));
  snprintf(text, capacity, "%s of %s", used, limit);
}

/* Whether the latest reading is capped by a cap of 0, the one its
 * capped_reason names: a pause. */
static bool paused(const ur_embed_cap *cap) {
  if (!strcmp(cap->capped_reason, UR_EMBED_CAPPED_REASON_MONTHLY))
    return cap->has_monthly_byte_limit && cap->monthly_byte_limit == 0;
  if (!strcmp(cap->capped_reason, UR_EMBED_CAPPED_REASON_TOTAL))
    return cap->has_total_byte_limit && cap->total_byte_limit == 0;
  return false;
}

/* The status: the first rule that applies, in the contract's order. */
void ur_embed_status_text(const ur_embed_status_input *input, char *text,
                          size_t capacity) {
  if (input->signed_out) {
    snprintf(text, capacity, "%s", UR_EMBED_STATUS_SIGNED_OUT);
    return;
  }
  if (!input->started) {
    snprintf(text, capacity, "%s", UR_EMBED_STATUS_STOPPED);
    return;
  }
  if (input->client_limit_status &&
      !strcmp(input->client_limit_status, URNET_CLIENT_LIMIT_STATUS_EXCEEDED)) {
    ur_embed_client_limit_text(input->client_limit_retry_time, text, capacity);
    return;
  }
  if (input->caps->state == UR_EMBED_CAPS_READ && input->caps->cap.capped) {
    const ur_embed_cap *cap = &input->caps->cap;
    if (paused(cap)) {
      snprintf(text, capacity, "%s", UR_EMBED_STATUS_PAUSED);
      return;
    }
    char reset[64];
    if (!strcmp(cap->capped_reason, UR_EMBED_CAPPED_REASON_MONTHLY) &&
        ur_embed_reset_text(cap->monthly_period_end, reset, sizeof(reset))) {
      snprintf(text, capacity, "%s, %s", UR_EMBED_STATUS_DATA_CAP_REACHED,
               reset);
      return;
    }
    /* a total cap, a monthly cap without a readable period end, or an
     * unknown reason */
    snprintf(text, capacity, "%s", UR_EMBED_STATUS_DATA_CAP_REACHED);
    return;
  }
  snprintf(text, capacity, "%s",
           1 <= input->providers_added ? UR_EMBED_STATUS_CONNECTED
                                       : UR_EMBED_STATUS_CONNECTING);
}

/* The console status line, for example "status: connected | data this month:
 * 1.2 GB of 5.0 GB | data total: no cap". */
void ur_embed_status_line(const ur_embed_status_input *input, char *line,
                          size_t capacity) {
  char status[128];
  char monthly[80];
  char total[80];
  ur_embed_status_text(input, status, sizeof(status));
  ur_embed_data_field(input->caps, true, monthly, sizeof(monthly));
  ur_embed_data_field(input->caps, false, total, sizeof(total));
  snprintf(line, capacity, "status: %s | data this month: %s | data total: %s",
           status, monthly, total);
}

/* ProviderStateAdded of the window status json; 0 for NULL (no window yet)
 * and for a missing count. */
int64_t ur_embed_providers_added(const char *window_status_json) {
  if (!window_status_json)
    return 0;
  int64_t providers_added = 0;
  ur_json_int64(
      ur_json_member(
          ur_json_parse(window_status_json, strlen(window_status_json)),
          "ProviderStateAdded"),
      &providers_added);
  return providers_added;
}

/* Reads the client limit status json, {"Status": ..., "RetryTime": ...}.
 * NULL, which the sdk returns only when the call cannot run, reads as no
 * limit. */
void ur_embed_read_client_limit_status(const char *client_limit_status_json,
                                       char *status, size_t capacity,
                                       int64_t *retry_time) {
  status[0] = 0;
  *retry_time = 0;
  if (!client_limit_status_json)
    return;
  ur_json_value value = ur_json_parse(client_limit_status_json,
                                      strlen(client_limit_status_json));
  char *text = ur_json_string(ur_json_member(value, "Status"));
  if (text) {
    snprintf(status, capacity, "%s", text);
    free(text);
  }
  ur_json_int64(ur_json_member(value, "RetryTime"), retry_time);
}
