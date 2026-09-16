require_relative "codec"
$stdout.sync = true
if ARGV == ["--self-test"]
  MessageCodec.self_test
  exit
end
require "bundler/setup"
require "securerandom"
require_relative "../integration/client"
CALLBACK_ROOTS = [] # One bounded set: C trampolines survive asynchronous Device close.

def show_peers(json)
  peers = json && JSON.parse(json)
  unless peers
    puts "peers unavailable (no snapshot)"
    return
  end
  puts "disconnected: #{peers.fetch('DisconnectedCount')}"
  puts "connected peers unavailable" if peers["Connected"].nil?
  (peers["Connected"] || []).each do |peer|
    color = URnetwork.take_string(URnetwork::Raw.urnet_get_color_hex(peer["ClientId"] || ""))
    puts JSON.generate(peer.merge("Color" => color))
  end
end

def run
  if ARGV == ["--version"]
    puts URnetwork.version
    return
  end
  mode = ARGV[0]
  raise "usage: --self-test | --version | self | peers | watch | send CLIENT_ID TEXT" unless %w[self peers watch send].include?(mode) && (mode != "send" || ARGV.length >= 3)
  destination = mode == "send" ? ARGV[1].downcase : nil
  raise "invalid client UUID" if destination && !/\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/.match?(destination)
  events = SizedQueue.new(256)
  put = lambda do |event|
    events.push(event, true)
  rescue ThreadError
    warn "receive queue full; event dropped"
  end
  # Strong references remain live through device close; no ephemeral pointer escapes.
  on_message = proc { |_, protocol, source, pointer, length| put.call([:message, source.dup, pointer.get_bytes(0, length)]) if protocol == 4096 && (16..4112).cover?(length) }
  on_peers = proc { |_, json| put.call([:peers, json&.dup]) }
  on_query = proc { |_, json, ok| put.call([:query, ok, json&.dup]) }
  callbacks = [on_message, on_peers, on_query]
  CALLBACK_ROOTS.concat(callbacks)
  session, subscriptions = nil, []
  raw = URnetwork::Raw
  begin
    session = UrSession.new(connect: false)
    handle = session.device.handle
    error = FFI::MemoryPointer.new(:pointer)
    subscription = raw.urnet_device_local_enable_subprotocol(handle, 4096, on_message, nil, error)
    message = URnetwork.take_string(error.read_pointer)
    raise(message || "subprotocol registration failed") if message || subscription == 0
    subscriptions << subscription
    subscriptions << raw.urnet_device_add_network_peers_change_listener(handle, on_peers, nil)
    raw.urnet_device_set_provide_mode(handle, 1) # URNET_PROVIDE_MODE_NETWORK
    puts "self: #{URnetwork.take_string(raw.urnet_device_get_client_id(handle))}"
    return if mode == "self"
    snapshot = URnetwork.take_string(raw.urnet_device_get_network_peers(handle))
    show_peers(snapshot)
    return if mode == "peers" && snapshot && JSON.parse(snapshot)
    monotonic = -> { Process.clock_gettime(Process::CLOCK_MONOTONIC) }
    deadline, queried, pending = monotonic.call + 30, false, SecureRandom.random_number(0xffffffffffffffff) + 1
    send_frame = lambda do |target, bytes|
      memory = FFI::MemoryPointer.new(:uint8, bytes.bytesize)
      memory.put_bytes(0, bytes)
      raise "SDK did not enqueue message" unless raw.urnet_device_local_send_subprotocol_bytes(handle, 4096, target, memory, bytes.bytesize)
    end
    loop do
      if destination && !queried && raw.urnet_device_local_get_provider_connected(handle)
        queried = true
        raw.urnet_device_local_query_subprotocols(handle, destination, 10_000, on_query, nil)
      end
      event = begin events.pop(true) rescue ThreadError; nil end
      if event
        case event[0]
        when :peers
          show_peers(event[1])
          return if mode == "peers" && event[1] && JSON.parse(event[1])
        when :query
          raise "peer query failed or peer does not advertise 4096" unless event[1] && (JSON.parse(event[2] || "null") || []).include?(4096)
          send_frame.call(destination, MessageCodec.encode(1, pending, ARGV[2..].join(" ")))
          puts "sent: #{pending} waiting for ACK"
          deadline = monotonic.call + 10
        when :message
          begin
            kind, id, text = MessageCodec.decode(event[2])
          rescue ArgumentError => error
            warn "rejected frame: #{error}"
            next
          end
          puts "kind=#{kind} source=#{event[1]} id=#{id} text=#{text.inspect}"
          if kind == 1
            send_frame.call(event[1], MessageCodec.encode(2, id))
          elsif event[1] == destination && id == pending
            return
          end
        end
      else
        sleep 0.05
      end
      raise "connection, peer snapshot, query, or ACK timed out" if mode != "watch" && monotonic.call > deadline
    end
  ensure
    subscriptions.reverse_each { |sub| raw.urnet_sub_close(sub); raw.urnet_release(sub) }
    raw.urnet_device_local_disable_subprotocol(session.device.handle, 4096) if session
    session&.close
    callbacks.each { |callback| callback.object_id } # Retain through cleanup.
  end
end
begin
  run
rescue Interrupt
rescue StandardError => error
  warn error.message
  exit 1
end
