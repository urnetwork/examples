// A small strict JSON reader and writer (RFC 8259) for the values that cross
// the SDK's C ABI as JSON, the token server's answers and the cap objects. The
// JDK has no JSON package; an app that already uses a JSON library can read the
// same shapes with it.
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses one JSON text into Java values: an object becomes a {@code Map<String, Object>} in member
 * order, an array a {@code List<Object>}, a number a {@link BigDecimal}, a string a {@link String},
 * {@code true} and {@code false} a {@link Boolean}, and {@code null} Java {@code null}. A duplicate
 * member keeps its last value. {@link #write} writes the same types back as compact JSON. Safe for
 * concurrent use: each parse has its own reader.
 */
final class Json {
  /** Text that is not one valid JSON value. */
  static final class ParseException extends Exception {
    private static final long serialVersionUID = 1;

    /** A parse failure with its reason. */
    ParseException(String message) { super(message); }
  }

  // at most this many nested objects and arrays, which bounds the reader's
  // recursion
  private static final int DEPTH_LIMIT = 64;

  private final String text;
  private int index;

  /** A reader positioned at the start of text. */
  private Json(String text) { this.text = text; }

  /** The value of one complete JSON text, with optional surrounding whitespace. */
  static Object parse(String text) throws ParseException {
    Json reader = new Json(text);
    reader.skipWhitespace();
    Object value = reader.readValue(0);
    reader.skipWhitespace();
    if (reader.index != text.length()) {
      throw reader.error("unexpected text after the value");
    }
    return value;
  }

  /** The members of a JSON text that must be one object. */
  static Map<String, Object> parseObject(String text) throws ParseException {
    Map<String, Object> members = parseNullableObject(text);
    if (members == null) {
      throw new ParseException("not a JSON object");
    }
    return members;
  }

  /**
   * The members of a JSON text that is one object or null: null for a null text (a NULL string of
   * the c abi) and for JSON null.
   */
  static Map<String, Object> parseNullableObject(String text)
      throws ParseException {
    if (text == null) {
      return null;
    }
    Object value = parse(text);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> members)) {
      throw new ParseException("not a JSON object");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typedMembers = (Map<String, Object>)members;
    return typedMembers;
  }

  /** A string member; null when it is absent or null. */
  static String string(Map<String, Object> members, String name)
      throws ParseException {
    Object value = members.get(name);
    if (value == null || value instanceof String) {
      return (String)value;
    }
    throw new ParseException(name + " is not a string");
  }

  /** An integer member that fits a long; defaultValue when it is absent or null. */
  static long longValue(Map<String, Object> members, String name,
                        long defaultValue) throws ParseException {
    Object value = members.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof BigDecimal number) {
      try {
        return number.longValueExact();
      } catch (ArithmeticException e) {
        // a fraction or a value beyond a long, refused below
      }
    }
    throw new ParseException(name + " is not an integer");
  }

  /** An object member; null when it is absent or null. */
  static Map<String, Object> object(Map<String, Object> members, String name)
      throws ParseException {
    Object value = members.get(name);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> object)) {
      throw new ParseException(name + " is not an object");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typedObject = (Map<String, Object>)object;
    return typedObject;
  }

  /**
   * Compact JSON text of a value made of maps with string keys, lists, strings, booleans, null and
   * Integer, Long or BigDecimal numbers.
   */
  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeValue(out, value);
    return out.toString();
  }

  /** Appends one value. */
  private static void writeValue(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String string) {
      writeString(out, string);
    } else if (value instanceof Boolean || value instanceof Integer ||
               value instanceof Long || value instanceof BigDecimal) {
      out.append(value);
    } else if (value instanceof Map<?, ?> members) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> member : members.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        writeString(out, (String)member.getKey());
        out.append(':');
        writeValue(out, member.getValue());
      }
      out.append('}');
    } else if (value instanceof List<?> items) {
      out.append('[');
      for (int i = 0; i < items.size(); i += 1) {
        if (0 < i) {
          out.append(',');
        }
        writeValue(out, items.get(i));
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException("not a JSON value: " +
                                         value.getClass().getName());
    }
  }

  /** Appends a quoted string, escaping quotes, backslashes and control characters. */
  private static void writeString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i += 1) {
      char c = value.charAt(i);
      switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      case '\t' -> out.append("\\t");
      case '\b' -> out.append("\\b");
      case '\f' -> out.append("\\f");
      default -> {
        if (c < 0x20) {
          out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
        } else {
          out.append(c);
        }
      }
      }
    }
    out.append('"');
  }

  /** Reads the value at the current position, inside depth objects and arrays. */
  private Object readValue(int depth) throws ParseException {
    if (text.length() <= index) {
      throw error("unexpected end");
    }
    char c = text.charAt(index);
    switch (c) {
    case '{':
      return readObject(depth);
    case '[':
      return readArray(depth);
    case '"':
      return readString();
    case 't':
      readLiteral("true");
      return Boolean.TRUE;
    case 'f':
      readLiteral("false");
      return Boolean.FALSE;
    case 'n':
      readLiteral("null");
      return null;
    default:
      if (c == '-' || isDigit(c)) {
        return readNumber();
      }
      throw error("unexpected character");
    }
  }

  /** Reads an object; the position is at its opening brace. */
  private Map<String, Object> readObject(int depth) throws ParseException {
    if (DEPTH_LIMIT <= depth) {
      throw error("nested too deeply");
    }
    Map<String, Object> members = new LinkedHashMap<>();
    index += 1;
    skipWhitespace();
    if (consume('}')) {
      return members;
    }
    while (true) {
      skipWhitespace();
      if (text.length() <= index || text.charAt(index) != '"') {
        throw error("expected a member name");
      }
      String name = readString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      members.put(name, readValue(depth + 1));
      skipWhitespace();
      if (consume('}')) {
        return members;
      }
      expect(',');
    }
  }

  /** Reads an array; the position is at its opening bracket. */
  private List<Object> readArray(int depth) throws ParseException {
    if (DEPTH_LIMIT <= depth) {
      throw error("nested too deeply");
    }
    List<Object> items = new ArrayList<>();
    index += 1;
    skipWhitespace();
    if (consume(']')) {
      return items;
    }
    while (true) {
      skipWhitespace();
      items.add(readValue(depth + 1));
      skipWhitespace();
      if (consume(']')) {
        return items;
      }
      expect(',');
    }
  }

  /** Reads a string; the position is at its opening quote. */
  private String readString() throws ParseException {
    index += 1;
    StringBuilder value = new StringBuilder();
    while (true) {
      if (text.length() <= index) {
        throw error("unterminated string");
      }
      char c = text.charAt(index);
      index += 1;
      if (c == '"') {
        return value.toString();
      }
      if (c < 0x20) {
        throw error("control character in a string");
      }
      if (c != '\\') {
        value.append(c);
        continue;
      }
      if (text.length() <= index) {
        throw error("unterminated escape");
      }
      char escape = text.charAt(index);
      index += 1;
      switch (escape) {
      case '"', '\\', '/' -> value.append(escape);
      case 'b' -> value.append('\b');
      case 'f' -> value.append('\f');
      case 'n' -> value.append('\n');
      case 'r' -> value.append('\r');
      case 't' -> value.append('\t');
      case 'u' -> {
        if (text.length() < index + 4) {
          throw error("short unicode escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i += 1) {
          int digit = hexDigit(text.charAt(index + i));
          if (digit < 0) {
            throw error("invalid unicode escape");
          }
          code = code * 16 + digit;
        }
        index += 4;
        value.append((char)code);
      }
      default -> throw error("invalid escape");
      }
    }
  }

  /** Reads a number: an optional minus, an integer without leading zeros, a fraction, an exponent. */
  private BigDecimal readNumber() throws ParseException {
    int start = index;
    consume('-');
    if (!consume('0') && !readDigits()) {
      throw error("invalid number");
    }
    if (consume('.') && !readDigits()) {
      throw error("invalid number");
    }
    if (consume('e') || consume('E')) {
      if (!consume('+')) {
        consume('-');
      }
      if (!readDigits()) {
        throw error("invalid number");
      }
    }
    try {
      return new BigDecimal(text.substring(start, index));
    } catch (NumberFormatException e) {
      // an exponent beyond the range of BigDecimal
      throw error("invalid number");
    }
  }

  /** Reads one or more ASCII digits; false when there is none. */
  private boolean readDigits() {
    int start = index;
    while (index < text.length() && isDigit(text.charAt(index))) {
      index += 1;
    }
    return start < index;
  }

  /** Reads the exact literal text. */
  private void readLiteral(String literal) throws ParseException {
    if (!text.startsWith(literal, index)) {
      throw error("invalid literal");
    }
    index += literal.length();
  }

  /** Skips JSON whitespace: space, tab, line feed and carriage return. */
  private void skipWhitespace() {
    while (index < text.length()) {
      char c = text.charAt(index);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return;
      }
      index += 1;
    }
  }

  /** Reads c if it is next. */
  private boolean consume(char c) {
    if (index < text.length() && text.charAt(index) == c) {
      index += 1;
      return true;
    }
    return false;
  }

  /** Reads c, which must be next. */
  private void expect(char c) throws ParseException {
    if (!consume(c)) {
      throw error("expected '" + c + "'");
    }
  }

  /** A parse failure at the current position. */
  private ParseException error(String reason) {
    return new ParseException(reason + " at offset " + index);
  }

  /** An ASCII digit; other Unicode digits are not JSON. */
  private static boolean isDigit(char c) { return '0' <= c && c <= '9'; }

  /** The value of an ASCII hex digit, or -1. */
  private static int hexDigit(char c) {
    if ('0' <= c && c <= '9') {
      return c - '0';
    }
    if ('a' <= c && c <= 'f') {
      return c - 'a' + 10;
    }
    if ('A' <= c && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }
}
