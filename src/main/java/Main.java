import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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
    int port = 6379;
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
    try (clientSocket) {
      BufferedReader in = new BufferedReader(
          new InputStreamReader(clientSocket.getInputStream()));
      OutputStream out = clientSocket.getOutputStream();
      TxCommands.ClientState tx = new TxCommands.ClientState();   // ← NEW: this client's transaction state

      while (true) {
        List<String> command = parseCommand(in);
        if (command == null) break;
        if (command.isEmpty()) continue;

        String response = TxCommands.process(command, tx);       // ← CHANGED (was handleCommand(command))
        out.write(response.getBytes());
        out.flush();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  // ---------- RESP parser ----------
  // Turns  *3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n  into  ["SET", "foo", "bar"]
  private static List<String> parseCommand(BufferedReader in) throws IOException {
    String line = in.readLine();               // "*3"  -> array of 3 items
    if (line == null) return null;
    if (!line.startsWith("*")) return List.of();

    int count = Integer.parseInt(line.substring(1));
    List<String> parts = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      String lengthLine = in.readLine();       // "$3"  -> next string is 3 bytes
      String value = in.readLine();            // "SET"
      if (lengthLine == null || value == null) return null;
      parts.add(value);
    }
    return parts;
  }

  // ---------- Commands ----------
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

    // Look for an optional PX or EX after the value
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
      store.remove(key);          // clean up the expired key
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
  
  // TYPE key -> string, list, or none (stream gets added with the Streams extension)
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
    return "$" + value.getBytes().length + "\r\n" + value + "\r\n";
  }
}