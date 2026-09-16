module MessageCodec
  SUBPROTOCOL = 4096
  def self.encode(kind, id, text = "")
    payload = text.encode(Encoding::UTF_8)
    raise ArgumentError, "invalid message" unless [1, 2].include?(kind) && (1..0xffffffffffffffff).cover?(id) && payload.valid_encoding? && payload.bytesize <= 4096 && (kind != 2 || payload.empty?)
    ["URMS", 1, kind, payload.bytesize, id].pack("a4CCnQ>") + payload.b
  end
  def self.decode(bytes)
    bytes = bytes.b
    raise ArgumentError, "invalid frame size" unless (16..4112).cover?(bytes.bytesize)
    magic, version, kind, length, id = bytes.unpack("a4CCnQ>")
    raise ArgumentError, "invalid header" unless magic == "URMS" && version == 1 && [1, 2].include?(kind) && id != 0 && length == bytes.bytesize - 16 && (kind != 2 || length == 0)
    text = bytes.byteslice(16..).force_encoding(Encoding::UTF_8)
    raise ArgumentError, "invalid UTF-8" unless text.valid_encoding?
    [kind, id, text]
  end
  def self.self_test
    check = ->(value) { raise "codec check failed" unless value }
    golden = ["55524d530101000200000000000000016869"].pack("H*")
    ack = ["55524d53010200000000000000000001"].pack("H*")
    [[1, 1, "hi"], [2, 1, ""]].each do |frame|
      expected = frame[0] == 1 ? golden : ack
      check.call(encode(*frame) == expected && decode(expected) == frame)
    end
    ["", "é🙂\0", "x" * 4096, "é" * 2048].each { |text| check.call(decode(encode(1, 0xffffffffffffffff, text)) == [1, 0xffffffffffffffff, text]) }
    bad = (0...golden.bytesize).map { |n| golden.byteslice(0, n) }
    bad << encode(1, 1, "x" * 4096) + "x"
    bad << golden + "x" << golden.byteslice(0, 16) + "\xc0\xaf".b
    [[0, 0], [4, 2], [5, 3], [5, 2], [6, 16], [7, 1], [15, 0]].each { |offset, value| changed = golden.dup; changed.setbyte(offset, value); bad << changed }
    reject = lambda do |&block|
      rejected = false
      begin
        block.call
      rescue ArgumentError, EncodingError
        rejected = true
      end
      check.call(rejected)
    end
    bad.each { |bytes| reject.call { decode(bytes) } }
    [[1, 0, ""], [2, 1, "x"], [3, 1, ""], [1, 1, "x" * 4097], [1, 1, "\xff".force_encoding(Encoding::UTF_8)]].each { |frame| reject.call { encode(*frame) } }
    puts "URMS codec self-test passed"
  end
end
