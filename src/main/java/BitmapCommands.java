import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bitmaps extension: SETBIT, GETBIT, BITCOUNT, BITOP (AND / OR / XOR / NOT).
 *
 * A bitmap is NOT a separate type: it is a normal string key, read as a row of bits.
 *   "a" = byte 0x61 = 0 1 1 0 0 0 0 1
 *                     ^ bit 0 is the LEFTMOST (most significant) bit of byte 0
 * So SET key "a" then GETBIT key 1 -> 1, and SETBIT can build a string bit by bit.
 *
 * Bytes are converted with ISO-8859-1 (Latin-1), where each char is exactly one
 * byte (0-255). That keeps the bytes unchanged on the way in and out of the store.
 */
public class BitmapCommands {

  // Makes read -> change -> write of a bitmap one step
  private static final Object lock = new Object();

  // ---------- Entry point ----------

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "SETBIT":
        return setbit(command);
      case "GETBIT":
        return getbit(command);
      case "BITCOUNT":
        return bitcount(command);
      case "BITOP":
        return bitop(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // ---------- SETBIT ----------

  // SETBIT key offset 0|1 -> the bit's OLD value. The string grows (with zero bytes) if needed.
  private static String setbit(List<String> command) {
    if (command.size() < 4) {
      return wrongArgs("setbit");
    }
    String key = command.get(1);

    long offset;
    try {
      offset = Long.parseLong(command.get(2));
    } catch (NumberFormatException e) {
      return "-ERR bit offset is not an integer or out of range\r\n";
    }
    if (offset < 0 || offset >= 4L * 1024 * 1024 * 1024) {   // Redis limit: 512 MB of bits
      return "-ERR bit offset is not an integer or out of range\r\n";
    }

    String bitArg = command.get(3);
    if (!bitArg.equals("0") && !bitArg.equals("1")) {
      return "-ERR bit is not an integer or out of range\r\n";
    }
    boolean newBit = bitArg.equals("1");

    synchronized (lock) {
      byte[] bytes = toBytes(Main.getString(key));
      int byteIndex = (int) (offset / 8);
      int bitInByte = 7 - (int) (offset % 8);       // bit 0 = leftmost = value 128

      // Grow the bitmap if the offset is past the end
      if (byteIndex >= bytes.length) {
        byte[] bigger = new byte[byteIndex + 1];    // new bytes start as 0
        System.arraycopy(bytes, 0, bigger, 0, bytes.length);
        bytes = bigger;
      }

      int oldBit = (bytes[byteIndex] >> bitInByte) & 1;
      if (newBit) {
        bytes[byteIndex] |= (byte) (1 << bitInByte);    // turn the bit on
      } else {
        bytes[byteIndex] &= (byte) ~(1 << bitInByte);   // turn the bit off
      }

      Main.setStringKeepTtl(key, fromBytes(bytes));
      return integer(oldBit);
    }
  }

  // ---------- GETBIT ----------

  // GETBIT key offset -> 0 or 1 (past the end of the string = 0)
  private static String getbit(List<String> command) {
    if (command.size() < 3) {
      return wrongArgs("getbit");
    }
    long offset;
    try {
      offset = Long.parseLong(command.get(2));
    } catch (NumberFormatException e) {
      return "-ERR bit offset is not an integer or out of range\r\n";
    }
    if (offset < 0) {
      return "-ERR bit offset is not an integer or out of range\r\n";
    }

    synchronized (lock) {
      byte[] bytes = toBytes(Main.getString(command.get(1)));
      long byteIndex = offset / 8;
      if (byteIndex >= bytes.length) {
        return integer(0);
      }
      int bitInByte = 7 - (int) (offset % 8);
      return integer((bytes[(int) byteIndex] >> bitInByte) & 1);
    }
  }

  // ---------- BITCOUNT ----------

  // BITCOUNT key [start end] -> how many bits are 1.
  // start/end are BYTE positions (not bits); negative counts from the end, like LRANGE.
  private static String bitcount(List<String> command) {
    if (command.size() != 2 && command.size() != 4) {
      return wrongArgs("bitcount");
    }

    synchronized (lock) {
      byte[] bytes = toBytes(Main.getString(command.get(1)));
      int start = 0;
      int end = bytes.length - 1;

      if (command.size() == 4) {
        try {
          start = Integer.parseInt(command.get(2));
          end = Integer.parseInt(command.get(3));
        } catch (NumberFormatException e) {
          return "-ERR value is not an integer or out of range\r\n";
        }
        if (start < 0) start = bytes.length + start;
        if (end < 0) end = bytes.length + end;
        if (start < 0) start = 0;
        if (end >= bytes.length) end = bytes.length - 1;
      }

      int count = 0;
      for (int i = start; i <= end; i++) {
        count += Integer.bitCount(bytes[i] & 0xFF);   // number of 1-bits in this byte
      }
      return integer(count);
    }
  }

  // ---------- BITOP ----------

  // BITOP AND|OR|XOR destkey srckey [srckey ...]
  // BITOP NOT destkey srckey
  //   -> stores the result in destkey and returns its length in bytes.
  //   Shorter (or missing) keys are treated as if padded with zero bytes.
  private static String bitop(List<String> command) {
    if (command.size() < 4) {
      return wrongArgs("bitop");
    }
    String op = command.get(1).toUpperCase();
    String dest = command.get(2);
    List<String> sources = command.subList(3, command.size());

    if (!op.equals("AND") && !op.equals("OR") && !op.equals("XOR") && !op.equals("NOT")) {
      return "-ERR syntax error\r\n";
    }
    if (op.equals("NOT") && sources.size() != 1) {
      return "-ERR BITOP NOT must be called with a single source key.\r\n";
    }

    synchronized (lock) {
      // Read every source and find the longest
      List<byte[]> inputs = new ArrayList<>();
      int maxLen = 0;
      for (String src : sources) {
        byte[] b = toBytes(Main.getString(src));
        inputs.add(b);
        maxLen = Math.max(maxLen, b.length);
      }

      byte[] result = new byte[maxLen];
      for (int i = 0; i < maxLen; i++) {
        int value = byteAt(inputs.get(0), i);
        if (op.equals("NOT")) {
          value = ~value;
        } else {
          for (int s = 1; s < inputs.size(); s++) {
            int other = byteAt(inputs.get(s), i);
            switch (op) {
              case "AND" -> value &= other;
              case "OR"  -> value |= other;
              case "XOR" -> value ^= other;
            }
          }
        }
        result[i] = (byte) value;
      }

      if (maxLen == 0) {
        Main.deleteString(dest);                    // empty result: the key is removed
      } else {
        Main.setString(dest, fromBytes(result));    // a fresh key, no expiry
      }
      return integer(maxLen);
    }
  }

  /** The byte at position i, or 0 if the bitmap is shorter (zero padding). */
  private static int byteAt(byte[] bytes, int i) {
    return i < bytes.length ? (bytes[i] & 0xFF) : 0;
  }

  // ---------- Conversions ----------

  private static byte[] toBytes(String value) {
    return value == null ? new byte[0] : value.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static String fromBytes(byte[] bytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  // ---------- RESP helpers ----------

  private static String integer(long n) {
    return ":" + n + "\r\n";
  }

  private static String wrongArgs(String cmd) {
    return "-ERR wrong number of arguments for '" + cmd + "' command\r\n";
  }
}