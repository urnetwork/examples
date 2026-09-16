using System.Buffers.Binary;
using System.Text;

public readonly record struct Frame(byte Kind, ulong Id, string Text);
public static class Codec {
  private static readonly UTF8Encoding Utf8 = new(false, true);
  public static byte[] Encode(Frame f) {
    byte[] text = Utf8.GetBytes(f.Text);
    if (f.Id == 0 || f.Kind is not(1 or 2) || text.Length > 4096 ||
        (f.Kind == 2 && text.Length != 0))
      throw new ArgumentException("invalid message");
    byte[] b = new byte[16 + text.Length];
    "URMS"u8.CopyTo(b);
    b[4] = 1;
    b[5] = f.Kind;
    BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(6), (ushort)text.Length);
    BinaryPrimitives.WriteUInt64BigEndian(b.AsSpan(8), f.Id);
    text.CopyTo(b, 16);
    return b;
  }
  public static Frame Decode(ReadOnlySpan<byte> b) {
    if (b.Length < 16 || b.Length > 4112 || !b[..4].SequenceEqual("URMS"u8) ||
        b[4] != 1)
      throw new ArgumentException("invalid frame");
    byte kind = b[5];
    int length = BinaryPrimitives.ReadUInt16BigEndian(b[6..]);
    ulong id = BinaryPrimitives.ReadUInt64BigEndian(b[8..]);
    if (id == 0 || kind is not(1 or 2) || length != b.Length - 16 ||
        (kind == 2 && length != 0))
      throw new ArgumentException("invalid payload");
    return new Frame(kind, id, Utf8.GetString(b[16..]));
  }
  public static void SelfTest() {
    static void Check(bool result) {
      if (!result)
        throw new Exception("codec test failed");
    }
    byte[] golden =
        Convert.FromHexString("55524d530101000200000000000000016869"),
           ack = Convert.FromHexString("55524d53010200000000000000000001");
    foreach (Frame f in new[] { new Frame(1, 1, "hi"), new Frame(2, 1, "") }) {
      byte[] expected = f.Kind == 1 ? golden : ack;
      Check(Encode(f).SequenceEqual(expected));
      Check(Decode(expected) == f);
    }
    foreach (string text in new[] { "", "é🙂\0", new string('x', 4096) }) {
      Frame f = new(1, ulong.MaxValue, text);
      Check(Decode(Encode(f)) == f);
    }
    var bad = new List<byte[]>();
    for (int n = 0; n < golden.Length; n++)
      bad.Add(golden[..n]);
    bad.Add([..Encode(new Frame(1, 1, new string('x', 4096))), 0]);
    bad.Add([..golden, 0]);
    byte[] invalidUtf8 = [..golden];
    invalidUtf8[16] = 0xc0;
    invalidUtf8[17] = 0xaf;
    bad.Add(invalidUtf8);
    foreach (var (offset, value) in new[] { (0, 0), (4, 2), (5, 3), (5, 2),
                                            (6, 16), (7, 1), (15, 0) }) {
      byte[] b = [..golden];
      b[offset] = (byte)value;
      bad.Add(b);
    }
    foreach (byte[] b in bad) {
      bool rejected = false;
      try {
        Decode(b);
      } catch (ArgumentException) {
        rejected = true;
      }
      Check(rejected);
    }
    foreach (Frame f in new[] { new Frame(1, 0, ""), new Frame(2, 1, "x"),
                                new Frame(3, 1, ""),
                                new Frame(1, 1, new string('x', 4097)),
                                new Frame(1, 1, "\ud800") }) {
      bool rejected = false;
      try {
        Encode(f);
      } catch (ArgumentException) {
        rejected = true;
      }
      Check(rejected);
    }
    Console.WriteLine("URMS codec self-test passed");
  }
}
