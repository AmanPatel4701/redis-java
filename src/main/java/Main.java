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

  // Where all keys live. Shared by every client thread.
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

      while (true) {
        List<String> command = parseCommand(in);   // e.g. ["SET", "foo", "bar", "PX", "100"]
        if (command == null) break;                // client disconnected
        if (command.isEmpty()) continue;           // ignore junk input

        String response = handleCommand(command);
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
  private static String handleCommand(List<String> command) {
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

  // ---------- RESP encoder ----------
  // "bar" -> $3\r\nbar\r\n     null -> $-1\r\n  (shown as "(nil)" in redis-cli)
  private static String bulkString(String value) {
    if (value == null) {
      return "$-1\r\n";
    }
    return "$" + value.getBytes().length + "\r\n" + value + "\r\n";
  }
}