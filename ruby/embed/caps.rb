# frozen_string_literal: true

# The client's data caps (EMBED_CONTRACT.md, "Backend: per-user data caps"):
# the cap object, and the app's read of its own caps with its client JWT. The
# backend sets caps with the root credential; the app only reads them. Plain
# Ruby apart from the transport it is given (transport.rb).

require_relative "state"

module Embed
  # GET with the client JWT reads that client's own caps
  CAP_ROUTE = "/network/client-data-cap"

  CAPPED_REASON_MONTHLY = "monthly"
  CAPPED_REASON_TOTAL = "total"

  # The server refuses the cap read with this message while the team has not
  # enabled Embed for the network (EMBED_CONTRACT.md, "Embed enablement").
  EMBED_NOT_ENABLED_MESSAGE = "Embed isn't enabled for this network."

  # What read_own_caps returns for the Embed-not-enabled refusal. Unlike a
  # failed read, it clears the last reading, so both data fields read
  # unavailable.
  EMBED_NOT_ENABLED = :embed_not_enabled

  # One cap object. A limit is nil when that cap is not set; a used count is
  # in bytes. monthly_period_end is RFC 3339 in UTC; capped is true while a cap
  # is reached; capped_reason is "monthly", "total", or "" when not capped.
  CapReading = Struct.new(
    :client_id,
    :monthly_byte_limit,
    :monthly_used_byte_count,
    :monthly_period_end,
    :total_byte_limit,
    :total_used_byte_count,
    :capped,
    :capped_reason,
    keyword_init: true,
  )

  # Parses cap objects and reads the app's own caps.
  module Caps
    # a field value of the wrong type
    INVALID = Object.new.freeze

    module_function

    # A byte limit: nil for null or absent, an integer of 0 or more, or INVALID.
    def byte_limit(value)
      return nil if value.nil?
      return value if value.is_a?(Integer) && value >= 0

      INVALID
    end

    # A used byte count: 0 for null or absent, an integer of 0 or more, or INVALID.
    def byte_count(value)
      value.nil? ? 0 : byte_limit(value)
    end

    # A text field: "" for null or absent, the string, or INVALID.
    def text(value)
      return "" if value.nil?

      value.is_a?(String) ? value : INVALID
    end

    # The cap object in parsed JSON, or nil when it is not one: not an object,
    # an error answer, or a field of the wrong type. A null or absent limit is
    # no cap, and a null or absent used count is 0. An unknown capped_reason is
    # kept as it is: the status reads it as capped without a reset time.
    def parse_cap_object(fields)
      return nil unless fields.is_a?(Hash) && fields["error"].nil?

      values = {
        client_id: text(fields["client_id"]),
        monthly_byte_limit: byte_limit(fields["monthly_byte_limit"]),
        monthly_used_byte_count: byte_count(fields["monthly_used_byte_count"]),
        monthly_period_end: text(fields["monthly_period_end"]),
        total_byte_limit: byte_limit(fields["total_byte_limit"]),
        total_used_byte_count: byte_count(fields["total_used_byte_count"]),
        capped_reason: text(fields["capped_reason"]),
      }
      capped = fields["capped"]
      capped = false if capped.nil?
      return nil unless [true, false].include?(capped)
      return nil if values.values.any? { |value| value.equal?(INVALID) }

      CapReading.new(capped: capped, **values)
    end

    # Whether parsed JSON is the Embed-not-enabled refusal,
    # {"error": {"message": "Embed isn't enabled for this network."}}.
    def embed_not_enabled?(fields)
      error = fields.is_a?(Hash) ? fields["error"] : nil
      error.is_a?(Hash) && error["message"] == EMBED_NOT_ENABLED_MESSAGE
    end

    # The cap object in an answer body, or nil.
    def parse_cap_answer(answer)
      parse_cap_object(JSON.parse(answer))
    rescue JSON::ParserError, EncodingError, TypeError
      nil
    end

    # GET /network/client-data-cap with the client JWT: this client's own caps.
    # nil for any failure: the API unreachable, an HTTP error (a server without
    # the cap routes answers 404), or an answer that is not a cap object;
    # EMBED_NOT_ENABLED for the Embed-not-enabled refusal.
    def read_own_caps(api_origin, client_jwt, transport)
      headers = {"Authorization" => "Bearer #{client_jwt}", "Accept" => "application/json"}
      begin
        status, answer = transport.call("GET", api_origin + CAP_ROUTE, headers, nil)
      rescue TransportError
        return nil
      end
      return nil unless status == 200

      fields =
        begin
          JSON.parse(answer)
        rescue JSON::ParserError, EncodingError, TypeError
          return nil
        end
      return EMBED_NOT_ENABLED if embed_not_enabled?(fields)

      parse_cap_object(fields)
    end
  end

  # A server that could not be reached (defined here so that caps.rb does not
  # need the HTTP client; transport.rb raises it).
  class TransportError < StandardError; end
end
