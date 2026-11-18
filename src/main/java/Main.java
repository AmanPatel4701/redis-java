import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class Main {
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
        List<String> command = parseCommand(in);   // e.g. ["ECHO", "hey"]
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
  // Turns  *2\r\n$4\r\nECHO\r\n$3\r\nhey\r\n  into  ["ECHO", "hey"]
  private static List<String> parseCommand(BufferedReader in) throws IOException {
    String line = in.readLine();               // "*2"  -> array of 2 items
    if (line == null) return null;
    if (!line.startsWith("*")) return List.of();

    int count = Integer.parseInt(line.substring(1));
    List<String> parts = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      String lengthLine = in.readLine();       // "$4"  -> next string is 4 bytes
      String value = in.readLine();            // "ECHO"
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

      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // ---------- RESP encoder ----------
  // "hey"  ->  $3\r\nhey\r\n
  private static String bulkString(String value) {
    return "$" + value.getBytes().length + "\r\n" + value + "\r\n";
  }
}