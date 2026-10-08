# frozen_string_literal: true

# The run loop's events and the reader of the client's own data caps
# (EMBED_CONTRACT.md, "App lifecycle"): at start, every five minutes, and at
# once after a contract status change. Plain Ruby without FFI, so the tests run
# the reader without the ffi gem.

module Embed
  # How often the status is read, and the longest gap between status lines.
  STATUS_POLL_INTERVAL_SECONDS = 1
  STATUS_REPEAT_INTERVAL_SECONDS = 60

  # How often the app reads its own caps. A contract status change reads them
  # at once.
  CAP_READ_INTERVAL_SECONDS = 5 * 60

  # run loop events, each an array that starts with one of these
  EVENT_STOP = :stop
  EVENT_JWT_REFRESHED = :jwt_refreshed
  EVENT_AUTH_LOGOUT = :auth_logout
  EVENT_CONTRACT_STATUS_CHANGED = :contract_status_changed
  EVENT_CAPS_READ = :caps_read

  # Reads the client's own caps on a thread of its own: at start unless a
  # first reading is known, every five minutes, and at once when woken. Each
  # reading, nil for a failed read, reaches the run loop as an
  # EVENT_CAPS_READ event.
  class CapReader
    # read.call returns a CapReading or nil and may block on the network.
    def initialize(read, events, interval_seconds = CAP_READ_INTERVAL_SECONDS)
      @read = read
      @events = events
      @interval_seconds = interval_seconds
      @wake = Queue.new
      @stopped = false
      @thread = nil
    end

    # Starts the reader; read_now reads at once instead of after one interval.
    def start(read_now)
      # the process does not wait for this thread: a read blocked on the
      # network never holds up the exit
      @thread = Thread.new { read_loop(read_now) }
    end

    # Reads at once, as after a contract status change.
    def wake
      @wake.push(:wake)
    end

    # Stops reading; a read in progress finishes on its own.
    def stop
      @stopped = true
      @wake.push(:stop)
    end

    private

    def read_loop(read_now)
      delay = read_now ? 0 : @interval_seconds
      until @stopped
        @wake.pop(timeout: delay) if delay.positive?
        # one read answers every wake that arrived meanwhile
        @wake.clear
        return if @stopped

        reading =
          begin
            @read.call
          rescue StandardError
            # a failed read keeps the last value; the reader keeps running
            nil
          end
        @events.push([EVENT_CAPS_READ, reading])
        delay = @interval_seconds
      end
    end
  end
end
