import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

  // A stored value plus the time it expires (-1 means it never expires)
  record Entry(String value, long expiresAt) {
    boolean isExpired() {
      return expiresAt != -1 && System.currentTimeMillis() >= expiresAt;
    }
  }

  // Where all string keys live. Shared by every client thread.
  private static final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

  public static void main(String[] args) {
    // ----- Start-up options -----
    //   --port 6380                  -> listen on another port
    //   --replicaof "localhost 6379" -> run as a replica of that master
    //   --dir /tmp/redis-files       -> folder of the RDB file
    //   --dbfilename dump.rdb        -> name of the RDB file
    //   --appendonly yes             -> turn on AOF persistence
    //   --appenddirname / --appendfilename / --appendfsync -> AOF options
    int port = 6379;
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals("--port")) {
        port = Integer.parseInt(args[i + 1]);
      } else if (args[i].equals("--replicaof")) {
        Replication.setReplicaOf(args[i + 1]);
      } else if (args[i].equals("--dir")) {
        Persistence.setDir(args[i + 1]);
      } else if (args[i].equals("--dbfilename")) {
        Persistence.setDbFilename(args[i + 1]);
      } else if (args[i].equals("--appendonly")) {
        Aof.setAppendOnly(args[i + 1]);
      } else if (args[i].equals("--appenddirname")) {
        Aof.setAppendDirName(args[i + 1]);
      } else if (args[i].equals("--appendfilename")) {
        Aof.setAppendFilename(args[i + 1]);
      } else if (args[i].equals("--appendfsync")) {
        Aof.setAppendFsync(args[i + 1]);
      }
    }

    // Load saved keys from the RDB file (if there is one)
    Persistence.load();

    // AOF: create its directory/files and replay saved commands (only with --appendonly yes)
    Aof.init();

    // A replica connects to its master in the background
    if (Replication.isReplica()) {
      Replication.startReplica(port);
    }

    try (ServerSocket serverSocket = new ServerSocket(port)) {
      serverSocket.setReuseAddress(true);

      while (true) {
        Socket clientSocket = serverSocket.accept();
        new Thread(() -> handleClient(clientSocket)).start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static void handleClient(Socket clientSocket) {
    Replication.ReplicaLink link = null;     // set if this connection turns out to be a replica
    PubSub.ClientSub sub = null;

    try (clientSocket) {
      RespReader in = new RespReader(clientSocket.getInputStream());
      OutputStream out = clientSocket.getOutputStream();
      TxCommands.ClientState tx = new TxCommands.ClientState();
      sub = new PubSub.ClientSub(out);       // this client's pub/sub state

      while (true) {
        List<String> command = in.readCommand();   // e.g. ["SET", "foo", "bar"]
        if (command == null) break;                // client disconnected
        if (command.isEmpty()) continue;           // ignore junk input

        String name = command.get(0).toUpperCase();

        // A replica asking for a full copy of the data
        if (name.equals("PSYNC")) {
          link = Replication.startFullResync(out);
          continue;
        }

        // A replica reporting how much it has processed: no reply is sent
        if (name.equals("REPLCONF") && command.size() >= 3
            && command.get(1).equalsIgnoreCase("ACK")) {
          if (link != null) {
            Replication.recordAck(link, Long.parseLong(command.get(2)));
          }
          continue;
        }

        // SUBSCRIBE / UNSUBSCRIBE, and the rules of subscribed mode
        String response = PubSub.process(command, sub);
        if (response == null) {
          response = TxCommands.process(command, tx);   // everything else
        }
        synchronized (out) {               // propagation may also write to this socket
          out.write(response.getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      }
    } catch (IOException | NumberFormatException e) {
      System.out.println("Client error: " + e.getMessage());
    } finally {
      if (link != null) {
        Replication.removeReplica(link);
      }
      if (sub != null) {
        PubSub.disconnect(sub);              // stop sending messages to a closed connection
      }
    }
  }

  // ---------- Commands ----------
  // Not private: TxCommands and Replication call this to run commands.
  static String handleCommand(List<String> command) {
    String name = command.get(0).toUpperCase();   // commands are case-insensitive

    switch (name) {
      case "PING":
        return "+PONG\r\n";

      case "ECHO":
        if (command.size() < 2) {
          return "-ERR wrong number of arguments for 'echo' command\r\n";
        }
        return bulkString(command.get(1));

      case "SET":
        return handleSet(command);

      case "GET":
        if (command.size() < 2) {
          return "-ERR wrong number of arguments for 'get' command\r\n";
        }
        return handleGet(command.get(1));

      case "INCR":
        if (command.size() < 2) {
          return "-ERR wrong number of arguments for 'incr' command\r\n";
        }
        return handleIncr(command.get(1));

      case "KEYS":
        if (command.size() < 2) {
          return "-ERR wrong number of arguments for 'keys' command\r\n";
        }
        return handleKeys(command.get(1));

      case "TYPE":
        if (command.size() < 2) {
          return "-ERR wrong number of arguments for 'type' command\r\n";
        }
        return handleType(command.get(1));

      // ----- Lists extension (ListCommands.java) -----
      case "RPUSH":
      case "LPUSH":
      case "LRANGE":
      case "LLEN":
      case "LPOP":
      case "BLPOP":
        return ListCommands.handle(command);

      // ----- Streams extension (StreamCommands.java) -----
      case "XADD":
      case "XRANGE":
      case "XREAD":
        return StreamCommands.handle(command);

      // ----- Replication extension (Replication.java) -----
      case "INFO":
      case "REPLCONF":
      case "WAIT":
        return Replication.handle(command);

      // ----- Pub/Sub extension (PubSub.java) -----
      case "PUBLISH":
        return PubSub.publish(command);

      // ----- RDB Persistence extension (Persistence.java) -----
      case "CONFIG":
        return Persistence.handle(command);

      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // SET key value [PX milliseconds | EX seconds]
  private static String handleSet(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'set' command\r\n";
    }
    String key = command.get(1);
    String value = command.get(2);
    long expiresAt = -1;   // -1 = never expires

    if (command.size() >= 5) {
      String option = command.get(3).toUpperCase();
      long amount;
      try {
        amount = Long.parseLong(command.get(4));
      } catch (NumberFormatException e) {
        return "-ERR value is not an integer or out of range\r\n";
      }

      if (option.equals("PX")) {
        expiresAt = System.currentTimeMillis() + amount;          // milliseconds
      } else if (option.equals("EX")) {
        expiresAt = System.currentTimeMillis() + amount * 1000;   // seconds -> ms
      } else {
        return "-ERR syntax error\r\n";
      }
    }

    store.put(key, new Entry(value, expiresAt));
    return "+OK\r\n";
  }

  // GET key -> value, or null if missing or expired
  private static String handleGet(String key) {
    Entry entry = store.get(key);
    if (entry == null) {
      return bulkString(null);
    }
    if (entry.isExpired()) {
      store.remove(key, entry);   // clean up the expired key
      return bulkString(null);
    }
    return bulkString(entry.value());
  }

  // INCR key -> adds 1 to a number stored as a string
  //   missing key -> becomes 1,  "abc" -> error
  private static String handleIncr(String key) {
    final String[] error = {null};
    final long[] result = {0};

    // compute() does read + update as ONE step, so two clients can't both
    // read 5 and both write 6
    store.compute(key, (k, entry) -> {
      if (entry == null || entry.isExpired()) {
        result[0] = 1;
        return new Entry("1", -1);
      }
      try {
        long n = Long.parseLong(entry.value()) + 1;
        result[0] = n;
        return new Entry(String.valueOf(n), entry.expiresAt());   // keep the old expiry
      } catch (NumberFormatException e) {
        error[0] = "-ERR value is not an integer or out of range\r\n";
        return entry;                                             // leave the value unchanged
      }
    });

    if (error[0] != null) return error[0];
    return ":" + result[0] + "\r\n";
  }

  // KEYS pattern -> every (non-expired) string key that matches, e.g. KEYS * or KEYS user:*
  private static String handleKeys(String pattern) {
    // Turn the glob pattern into a regex: * = anything, ? = one character
    StringBuilder regex = new StringBuilder();
    for (char c : pattern.toCharArray()) {
      if (c == '*') regex.append(".*");
      else if (c == '?') regex.append(".");
      else regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
    }
    String compiled = regex.toString();

    List<String> keys = new ArrayList<>();
    for (var e : store.entrySet()) {
      if (!e.getValue().isExpired() && e.getKey().matches(compiled)) {
        keys.add(e.getKey());
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("*").append(keys.size()).append("\r\n");
    for (String k : keys) {
      sb.append(bulkString(k));
    }
    return sb.toString();
  }

  /** Called by Persistence while loading the RDB file. Keys that already expired are skipped. */
  static void loadKey(String key, String value, long expiresAt) {
    if (expiresAt != -1 && System.currentTimeMillis() >= expiresAt) {
      return;
    }
    store.put(key, new Entry(value, expiresAt));
  }

  // TYPE key -> string, list, stream, or none
  private static String handleType(String key) {
    Entry entry = store.get(key);
    if (entry != null && !entry.isExpired()) {
      return "+string\r\n";
    }
    if (ListCommands.hasKey(key)) {
      return "+list\r\n";
    }
    if (StreamCommands.hasKey(key)) {
      return "+stream\r\n";
    }
    return "+none\r\n";
  }

  // ---------- RESP encoder ----------
  // "bar" -> $3\r\nbar\r\n     null -> $-1\r\n  (shown as "(nil)" in redis-cli)
  // Not private, so the extension files can reuse it.
  static String bulkString(String value) {
    if (value == null) {
      return "$-1\r\n";
    }
    return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n";
  }

  // ---------- RESP reader ----------
  /**
   * Reads RESP byte by byte and counts every byte it reads.
   * The count matters for replication: a replica must report exactly how many
   * bytes of commands it has processed. Reading raw bytes (instead of
   * BufferedReader.readLine) also lets us read the binary RDB file safely.
   */
  static class RespReader {
    private final InputStream in;
    private long bytesRead = 0;

    RespReader(InputStream in) {
      this.in = new BufferedInputStream(in);
    }

    long bytesRead() {
      return bytesRead;
    }

    /** Reads one line ending in \r\n (returned without it), or null at end of stream. */
    String readLine() throws IOException {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      int b;
      while ((b = in.read()) != -1) {
        bytesRead++;
        if (b == '\n') {
          byte[] bytes = buf.toByteArray();
          int len = bytes.length;
          if (len > 0 && bytes[len - 1] == '\r') len--;
          return new String(bytes, 0, len, StandardCharsets.UTF_8);
        }
        buf.write(b);
      }
      return null;
    }

    /**
     * Turns  *2\r\n$4\r\nECHO\r\n$3\r\nhey\r\n  into  ["ECHO", "hey"].
     * Returns null when the connection closes, or an empty list for junk input.
     */
    List<String> readCommand() throws IOException {
      String line = readLine();
      if (line == null) return null;
      if (line.isEmpty() || line.charAt(0) != '*') return List.of();

      int count = Integer.parseInt(line.substring(1));
      List<String> parts = new ArrayList<>();

      for (int i = 0; i < count; i++) {
        String header = readLine();                    // "$4" -> next string is 4 bytes
        if (header == null) return null;
        int len = Integer.parseInt(header.substring(1));
        byte[] data = readExact(len);                  // read exactly 4 bytes: "ECHO"
        if (data == null) return null;
        readLine();                                    // the \r\n after the data
        parts.add(new String(data, StandardCharsets.UTF_8));
      }
      return parts;
    }

    /** Reads the RDB file a master sends: $<length>\r\n<bytes>  (no \r\n at the end). */
    void readRdb() throws IOException {
      String header = readLine();
      if (header == null || !header.startsWith("$")) {
        throw new IOException("Expected RDB file, got: " + header);
      }
      readExact(Integer.parseInt(header.substring(1)));
    }

    private byte[] readExact(int len) throws IOException {
      byte[] data = in.readNBytes(len);
      bytesRead += data.length;
      return data.length == len ? data : null;
    }
  }
}