import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RDB Persistence extension: --dir / --dbfilename, CONFIG GET, and loading
 * keys from an RDB file when the server starts.
 *
 * RDB file layout (the parts we need):
 *
 *   "REDIS0011"                  header (9 bytes)
 *   FA <name> <value>            metadata (redis version etc.) - skipped
 *   FE <db number>               start of a database
 *   FB <hash size> <expire size> sizes of the database - skipped
 *   [FC <8-byte ms>]             optional expiry in milliseconds (little-endian)
 *   [FD <4-byte sec>]            optional expiry in seconds (little-endian)
 *   00 <key> <value>             a string key/value pair (type 0 = string)
 *   ...
 *   FF <8-byte checksum>         end of file
 */
public class Persistence {

  // ---------- Config ----------

  private static String dir = System.getProperty("user.dir");   // the current directory
  private static String dbFilename = "dump.rdb";

  public static void setDir(String value) {
    dir = value;
  }

  public static void setDbFilename(String value) {
    dbFilename = value;
  }

  public static String getDir() {
    return dir;
  }

  // ---------- Commands (called from Main.handleCommand) ----------

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "CONFIG":
        return config(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // CONFIG GET dir  ->  ["dir", "/tmp/redis-files"]
  private static String config(List<String> command) {
    if (command.size() < 3 || !command.get(1).equalsIgnoreCase("GET")) {
      return "-ERR only CONFIG GET is supported\r\n";
    }
    String param = command.get(2).toLowerCase();
    String value;
    switch (param) {
      case "dir":
        value = dir;
        break;
      case "dbfilename":
        value = dbFilename;
        break;
      default:
        value = Aof.getConfig(param);  // appendonly, appenddirname, appendfilename, appendfsync
        if (value == null) {
          return "*0\r\n";           // unknown parameter -> empty array
        }
    }
    return "*2\r\n" + Main.bulkString(param) + Main.bulkString(value);
  }

  // ---------- Loading the RDB file ----------

  // Opcodes (special bytes that mark sections of the file)
  private static final int OP_AUX = 0xFA;
  private static final int OP_RESIZEDB = 0xFB;
  private static final int OP_EXPIRETIME_MS = 0xFC;
  private static final int OP_EXPIRETIME = 0xFD;
  private static final int OP_SELECTDB = 0xFE;
  private static final int OP_EOF = 0xFF;

  private static final int TYPE_STRING = 0;

  /** Reads dir/dbfilename and puts every key into Main's store. A missing file means an empty database. */
  public static void load() {
    File file = new File(dir, dbFilename);
    if (!file.exists()) {
      return;                        // nothing saved yet: start empty
    }

    try (DataInputStream in = new DataInputStream(
        new BufferedInputStream(new FileInputStream(file)))) {

      // Header: "REDIS" + 4-digit version
      byte[] header = new byte[9];
      in.readFully(header);
      if (!new String(header, 0, 5, StandardCharsets.US_ASCII).equals("REDIS")) {
        System.out.println("Not an RDB file: " + file);
        return;
      }

      long expiresAt = -1;           // expiry for the NEXT key, if an FC/FD came first

      while (true) {
        int op = in.readUnsignedByte();

        switch (op) {
          case OP_EOF:
            return;                  // done (the checksum after it is ignored)

          case OP_AUX:
            readString(in);          // metadata name, e.g. "redis-ver"
            readString(in);          // metadata value, e.g. "7.2.0"
            break;

          case OP_SELECTDB:
            readLength(in);          // database number (we only have one)
            break;

          case OP_RESIZEDB:
            readLength(in);          // number of keys
            readLength(in);          // number of keys with an expiry
            break;

          case OP_EXPIRETIME_MS:
            expiresAt = readLittleEndian(in, 8);          // already milliseconds
            break;

          case OP_EXPIRETIME:
            expiresAt = readLittleEndian(in, 4) * 1000;   // seconds -> milliseconds
            break;

          default:
            // Anything else is a value type followed by a key and a value
            String key = readString(in);
            if (op == TYPE_STRING) {
              String value = readString(in);
              Main.loadKey(key, value, expiresAt);
            } else {
              System.out.println("Skipping unsupported value type " + op + " for key " + key);
              return;                // we can't know how long other types are, so stop here
            }
            expiresAt = -1;          // expiry applies to one key only
            break;
        }
      }
    } catch (EOFException e) {
      System.out.println("RDB file ended early: " + file);
    } catch (IOException | RuntimeException e) {
      System.out.println("Could not read RDB file: " + e.getMessage());
    }
  }

  // ---------- RDB encodings ----------

  /**
   * Length encoding: the first 2 bits of the first byte say how big the number is.
   *   00xxxxxx                 -> 6-bit number
   *   01xxxxxx xxxxxxxx        -> 14-bit number
   *   10000000 + 4 bytes       -> 32-bit number (big-endian)
   *   11xxxxxx                 -> "special": the string is stored as an integer
   * For the special case this returns -1 - format, so readString can tell.
   */
  private static int readLength(DataInputStream in) throws IOException {
    int first = in.readUnsignedByte();
    int type = (first & 0xC0) >> 6;

    switch (type) {
      case 0:
        return first & 0x3F;
      case 1:
        return ((first & 0x3F) << 8) | in.readUnsignedByte();
      case 2:
        return in.readInt();                   // DataInputStream reads big-endian
      default:
        return -1 - (first & 0x3F);            // special format 0..3 -> -1..-4
    }
  }

  /** A string is a length followed by that many bytes, or an integer stored in 1, 2 or 4 bytes. */
  private static String readString(DataInputStream in) throws IOException {
    int length = readLength(in);

    if (length >= 0) {
      byte[] bytes = new byte[length];
      in.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }

    int format = -1 - length;
    switch (format) {
      case 0:
        return String.valueOf(in.readByte());                         // 8-bit integer
      case 1:
        return String.valueOf((short) readLittleEndian(in, 2));       // 16-bit integer
      case 2:
        return String.valueOf((int) readLittleEndian(in, 4));         // 32-bit integer
      default:
        throw new IOException("Compressed (LZF) strings are not supported");
    }
  }

  /** Reads an unsigned little-endian number of 2, 4 or 8 bytes. */
  private static long readLittleEndian(DataInputStream in, int bytes) throws IOException {
    long result = 0;
    for (int i = 0; i < bytes; i++) {
      result |= ((long) in.readUnsignedByte()) << (8 * i);
    }
    return result;
  }
}