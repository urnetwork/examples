# frozen_string_literal: true

# The status that every embed example shows, with the exact rules of
# EMBED_CONTRACT.md ("Status"): the status field, data this month and data
# total. Plain Ruby, so the self-test checks every rule and golden vector
# without the native SDK, a network or credentials.

require "date"
require_relative "caps"

module Embed
  # the status field, in rule order. Console examples never show the first two.
  STATUS_SIGNED_OUT = "signed out"
  STATUS_STOPPED = "stopped"
  # the platform disconnected this client for its network's concurrent client
  # limit, and the SDK holds its platform connections until the retry time
  STATUS_CLIENT_LIMIT = "client limit"
  STATUS_PAUSED = "paused"
  STATUS_DATA_CAP_REACHED = "data cap reached"
  STATUS_CONNECTED = "connected"
  STATUS_CONNECTING = "connecting"

  # the data fields before a reading, when the first reading failed, and for a
  # cap that is not set
  DATA_CHECKING = "checking"
  DATA_UNAVAILABLE = "unavailable"
  DATA_NO_CAP = "no cap"

  # client limit status values (URNET_CLIENT_LIMIT_STATUS_NONE and _EXCEEDED)
  CLIENT_LIMIT_STATUS_NONE = ""
  CLIENT_LIMIT_STATUS_EXCEEDED = "client_limit_exceeded"

  # decimal units, because data plans and the Embed plan's budget are sold in them
  DECIMAL_UNITS = %w[kB MB GB TB PB EB].freeze

  # RFC 3339 with optional fractional seconds, as Go writes time.Time in JSON
  RFC3339_PATTERN = /\A(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(?:([Zz])|([+-])(\d{2}):(\d{2}))\z/.freeze

  MINUTE_MILLIS = 60 * 1000
  MINUTES_PER_DAY = 24 * 60

  # Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "1.2 GB". The
  # tenths round half to even on the exact integer, never on a binary
  # fraction, so 1050 bytes is "1.0 kB" and 1150 bytes "1.2 kB"; a value that
  # rounds to 1000.0 moves to the next unit, so 999999 bytes is "1.0 MB".
  def self.format_byte_count(byte_count)
    return "#{byte_count} B" if byte_count < 1000

    # the bytes in a tenth of the unit
    tenth = 100
    DECIMAL_UNITS.each_with_index do |unit, unit_index|
      tenths, remainder = byte_count.divmod(tenth)
      tenths += 1 if tenth < 2 * remainder || (2 * remainder == tenth && tenths.odd?)
      return "#{tenths / 10}.#{tenths % 10} #{unit}" if tenths < 10_000 || unit_index == DECIMAL_UNITS.length - 1

      tenth *= 1000
    end
  end

  # The line the console app prints once the device runs (EMBED_CONTRACT.md,
  # "Status").
  def self.start_line(client_id, instance_id)
    "embed client #{client_id}, installation #{instance_id}"
  end

  # The kind of app whose licenses --licenses prints, from RbConfig's
  # host_os: "apple" on macOS, "windows" on Windows, "linux" elsewhere.
  def self.license_app(host_os)
    case host_os
    when /darwin/i then "apple"
    when /mswin|mingw|cygwin/i then "windows"
    else "linux"
    end
  end

  # "resets YYYY-MM-DD HH:MM UTC" for a monthly period end in RFC 3339: in
  # UTC, with the seconds rounded up to the next whole minute, so the shown
  # time is never before the reset. nil when the text does not parse.
  def self.reset_time_text(monthly_period_end)
    match = monthly_period_end.is_a?(String) ? RFC3339_PATTERN.match(monthly_period_end) : nil
    return nil if match.nil?

    year, month, day, hour, minute, second = match.captures[0, 6].map(&:to_i)
    fraction = match[7] || ""
    return nil unless Date.valid_date?(year, month, day) && hour <= 23 && minute <= 59 && second <= 59

    offset_seconds = 0
    unless match[8]
      offset_hours = match[10].to_i
      offset_minutes = match[11].to_i
      return nil if offset_hours > 23 || offset_minutes > 59

      offset_seconds = (offset_hours * 3600) + (offset_minutes * 60)
      offset_seconds = -offset_seconds if match[9] == "-"
    end
    reset = Time.utc(year, month, day, hour, minute, 0) - offset_seconds
    reset += 60 if second.positive? || fraction.match?(/[1-9]/)
    "resets #{reset.strftime('%Y-%m-%d %H:%M')} UTC"
  end

  # "client limit, retry at HH:MM UTC": the retry time (unix milliseconds)
  # rounded up to the next whole minute in UTC, as the provider contract
  # rounds it, so the shown time is never before the retry; 0 shows "client
  # limit".
  def self.client_limit_text(client_limit_retry_time)
    return STATUS_CLIENT_LIMIT if client_limit_retry_time <= 0

    retry_minute = (client_limit_retry_time + MINUTE_MILLIS - 1) / MINUTE_MILLIS
    # unix time has no leap seconds, so every utc day is 24 * 60 minutes
    minute_of_day = retry_minute % MINUTES_PER_DAY
    format("%s, retry at %02d:%02d UTC", STATUS_CLIENT_LIMIT, minute_of_day / 60, minute_of_day % 60)
  end

  # The limit of the cap that capped_reason names; nil for an unknown reason
  # or an unset cap.
  def self.capped_limit(reading)
    case reading.capped_reason
    when CAPPED_REASON_MONTHLY then reading.monthly_byte_limit
    when CAPPED_REASON_TOTAL then reading.total_byte_limit
    end
  end

  # The status field: the first rule that applies. signed out; stopped;
  # client limit while the SDK holds this client off; paused while the latest
  # reading is capped and the cap that capped_reason names is 0; data cap
  # reached while capped, with the reset time for the monthly cap; connected
  # once the window has a provider added; connecting otherwise.
  def self.embed_status(started, signed_out, client_limit_status, client_limit_retry_time, reading, providers_added)
    return STATUS_SIGNED_OUT if signed_out
    return STATUS_STOPPED unless started
    return client_limit_text(client_limit_retry_time) if client_limit_status == CLIENT_LIMIT_STATUS_EXCEEDED

    if reading&.capped
      return STATUS_PAUSED if capped_limit(reading)&.zero?

      if reading.capped_reason == CAPPED_REASON_MONTHLY
        reset = reset_time_text(reading.monthly_period_end)
        return "#{STATUS_DATA_CAP_REACHED}, #{reset}" unless reset.nil?
      end
      return STATUS_DATA_CAP_REACHED
    end
    providers_added >= 1 ? STATUS_CONNECTED : STATUS_CONNECTING
  end

  # The cap readings behind the data fields: none yet, a first reading that
  # failed, or the latest successful reading, which a later failure keeps.
  # Owned by the run loop's thread.
  class CapState
    attr_reader :reading

    # No reading yet, or a first reading such as the token server's data_cap.
    def initialize(reading = nil)
      @reading = reading
      @failed = false
    end

    # Records a reading; nil is a failed read, and EMBED_NOT_ENABLED, the
    # Embed-not-enabled refusal, clears the last reading, so both data fields
    # read unavailable and the status rules see no cap reading.
    def record(reading)
      if reading == EMBED_NOT_ENABLED
        @reading = nil
        @failed = true
      elsif reading.nil?
        @failed = true if @reading.nil?
      else
        @reading = reading
        @failed = false
      end
    end

    def failed?
      @failed
    end
  end

  # Data this month (monthly) or data total: checking until the first
  # reading; unavailable when it failed; no cap for an unset cap, never a used
  # count; otherwise "<used> of <limit>".
  def self.data_field_text(cap_state, monthly)
    reading = cap_state.reading
    return cap_state.failed? ? DATA_UNAVAILABLE : DATA_CHECKING if reading.nil?

    limit, used = monthly ? [reading.monthly_byte_limit, reading.monthly_used_byte_count] : [reading.total_byte_limit, reading.total_used_byte_count]
    return DATA_NO_CAP if limit.nil?

    "#{format_byte_count(used)} of #{format_byte_count(limit)}"
  end

  # One status snapshot.
  Status = Struct.new(:status, :data_this_month, :data_total, keyword_init: true) do
    # The console status line.
    def line
      "status: #{status} | data this month: #{data_this_month} | data total: #{data_total}"
    end
  end

  # The three status fields.
  def self.status_snapshot(started, signed_out, client_limit_status, client_limit_retry_time, cap_state, providers_added)
    Status.new(
      status: embed_status(started, signed_out, client_limit_status, client_limit_retry_time, cap_state.reading, providers_added),
      data_this_month: data_field_text(cap_state, true),
      data_total: data_field_text(cap_state, false),
    )
  end

  # A JSON integer field, or 0 when it is missing or not an integer.
  def self.json_int(value)
    value.is_a?(Integer) ? value : 0
  end

  # A JSON string field, or "" when it is missing or not a string.
  def self.json_text(value)
    value.is_a?(String) ? value : ""
  end

  # The client limit status and its retry time (unix milliseconds) from the
  # JSON of urnet_device_get_client_limit_status; NULL reads as no limit.
  def self.parse_client_limit_status(status_json)
    status = parse_json_object(status_json)
    return [CLIENT_LIMIT_STATUS_NONE, 0] if status.nil?

    [json_text(status["Status"]), json_int(status["RetryTime"])]
  end

  # ProviderStateAdded from the JSON of urnet_device_get_window_status; 0
  # before the window exists (NULL).
  def self.parse_providers_added(window_status_json)
    window_status = parse_json_object(window_status_json)
    window_status.nil? ? 0 : json_int(window_status["ProviderStateAdded"])
  end
end
