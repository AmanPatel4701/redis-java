import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
  public static void main(String[] args) {
    int port = 6379;
    try (ServerSocket serverSocket = new ServerSocket(port)) {
      // Since the tester restarts your program quite often, setting SO_REUSEADDR
      // ensures that we don't run into 'Address already in use' errors
      serverSocket.setReuseAddress(true);

      // CHANGE 1: keep accepting new clients forever
      while (true) {
        Socket clientSocket = serverSocket.accept();

        // CHANGE 2: handle this client in its own thread,
        // so the loop can go straight back to accept() for the next one
        new Thread(() -> handleClient(clientSocket)).start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  // Your old read/PONG loop, moved into its own method
  private static void handleClient(Socket clientSocket) {
    try (clientSocket) {
      InputStream inputStream = clientSocket.getInputStream();
      OutputStream outputStream = clientSocket.getOutputStream();
      byte[] buffer = new byte[1024];

      while (inputStream.read(buffer) != -1) {
        outputStream.write("+PONG\r\n".getBytes());
        outputStream.flush();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }
}