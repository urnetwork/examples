import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

public final class MessageCodec {
  public static final int SUBPROTOCOL = 4096;
  public record Frame(int kind, long id, String text) {}
  public interface Codec {
    byte[] encode(Frame frame) throws Exception;
    Frame decode(byte[] bytes) throws Exception;
  }
  public static final Codec CODEC = new Codec() {
    public byte[] encode(Frame f) throws CharacterCodingException {
      var encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(f.text()));
      byte[] text = new byte[encoded.remaining()];
      encoded.get(text);
      if (f.id() == 0 || (f.kind() != 1 && f.kind() != 2) ||
          text.length > 4096 || (f.kind() == 2 && text.length != 0))
        throw new IllegalArgumentException("invalid message");
      return ByteBuffer.allocate(16 + text.length)
          .putInt(0x55524d53)
          .put((byte)1)
          .put((byte)f.kind())
          .putShort((short)text.length)
          .putLong(f.id())
          .put(text)
          .array();
    }
    public Frame decode(byte[] bytes) throws CharacterCodingException {
      if (bytes.length < 16 || bytes.length > 4112)
        throw new IllegalArgumentException("invalid frame size");
      var b = ByteBuffer.wrap(bytes);
      if (b.getInt() != 0x55524d53 || b.get() != 1)
        throw new IllegalArgumentException("invalid header");
      int kind = b.get() & 255, length = b.getShort() & 65535;
      long id = b.getLong();
      if (id == 0 || (kind != 1 && kind != 2) || length != bytes.length - 16 ||
          (kind == 2 && length != 0))
        throw new IllegalArgumentException("invalid payload");
      String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(b)
                        .toString();
      return new Frame(kind, id, text);
    }
  };
  private static void check(boolean value) {
    if (!value)
      throw new AssertionError("codec check failed");
  }
  public static void selfTest(Codec codec) throws Exception {
    byte[] golden =
        HexFormat.of().parseHex("55524d530101000200000000000000016869");
    byte[] ack = HexFormat.of().parseHex("55524d53010200000000000000000001");
    for (var f : new Frame[] {new Frame(1, 1, "hi"), new Frame(2, 1, "")}) {
      byte[] expected = f.kind() == 1 ? golden : ack;
      check(Arrays.equals(codec.encode(f), expected));
      check(codec.decode(expected).equals(f));
    }
    for (String text :
         new String[] {"", "é🙂\u0000", "x".repeat(4096), "é".repeat(2048)}) {
      var f = new Frame(1, -1L, text);
      check(codec.decode(codec.encode(f)).equals(f));
    }
    var bad = new ArrayList<byte[]>();
    bad.add(
        Arrays.copyOf(codec.encode(new Frame(1, 1, "x".repeat(4096))), 4113));
    for (int n = 0; n < golden.length; n++)
      bad.add(Arrays.copyOf(golden, n));
    bad.add(Arrays.copyOf(golden, golden.length + 1));
    var utf8 = golden.clone();
    utf8[16] = (byte)0xc0;
    utf8[17] = (byte)0xaf;
    bad.add(utf8);
    for (int[] edit : new int[][] {
             {0, 0}, {4, 2}, {5, 3}, {5, 2}, {6, 16}, {7, 1}, {15, 0}}) {
      var b = golden.clone();
      b[edit[0]] = (byte)edit[1];
      bad.add(b);
    }
    for (byte[] bytes : bad) {
      boolean rejected = false;
      try {
        codec.decode(bytes);
      } catch (Exception e) {
        rejected = true;
      }
      check(rejected);
    }
    for (var f : new Frame[] {
             new Frame(1, 0, ""), new Frame(2, 1, "x"), new Frame(3, 1, ""),
             new Frame(1, 1, "x".repeat(4097)), new Frame(1, 1, "\ud800")}) {
      boolean rejected = false;
      try {
        codec.encode(f);
      } catch (Exception e) {
        rejected = true;
      }
      check(rejected);
    }
    System.out.println("URMS codec self-test passed");
  }
  public static void main(String[] args) throws Exception { selfTest(CODEC); }
}
