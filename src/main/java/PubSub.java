import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pub/Sub extension: SUBSCRIBE, UNSUBSCRIBE, PUBLISH, and "subscribed mode".
 *
 * A client SUBSCRIBEs to channels. Any client can PUBLISH a message to a
 * channel, and every subscriber of that channel receives it immediately:
 *   ["message", <channel>, <message>]
 *
 * While a client is subscribed to at least one channel it is in "subscribed
 * mode": only SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PING, QUIT and
 * RESET are allowed.
 */
public class PubSub {

  /** One client connection's pub/sub state. Created in Main.handleClient. */
  public static class ClientSub {
    private final OutputStream out;
    // Channels this client is subscribed to, in the order it subscribed
    private final Set<String> channels = new LinkedHashSet<>();

    public ClientSub(OutputStream out) {
      this.out = out;
    }

    boolean isSubscribed() {
      return !channels.isEmpty();
    }

    /** Pushes a message to this client. Synchronized on the same lock Main uses for replies. */
    void send(byte[] data) {
      try {
        synchronized (out) {
          out.write(data);
          out.flush();
        }
      } catch (IOException e) {
        // client went away; it gets removed when its connection closes
      }
    }
  }

  // channel -> every client subscribed to it
  private static final ConcurrentHashMap<String, Set<ClientSub>> subscribers = new ConcurrentHashMap<>();

  // Commands allowed in subscribed mode
  private static final Set<String> ALLOWED_WHILE_SUBSCRIBED = Set.of(
      "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PING", "QUIT", "RESET");

  // ---------- Entry point (called from Main.handleClient for every command) ----------

  /**
   * Handles pub/sub commands and the subscribed-mode rules.
   * Returns the reply, or null if this command isn't pub/sub's business
   * (then Main handles it normally).
   */
  public static String process(List<String> command, ClientSub client) {
    String name = command.get(0).toUpperCase();

    switch (name) {
      case "SUBSCRIBE":
        return subscribe(command, client);
      case "UNSUBSCRIBE":
        return unsubscribe(command, client);
    }

    if (client.isSubscribed()) {
      if (name.equals("PING")) {
        // In subscribed mode PING replies with an array: ["pong", ""]
        return "*2\r\n" + Main.bulkString("pong") + Main.bulkString("");
      }
      if (!ALLOWED_WHILE_SUBSCRIBED.contains(name)) {
        return "-ERR Can't execute '" + command.get(0).toLowerCase()
            + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET"
            + " are allowed in this context\r\n";
      }
    }
    return null;                     // not subscribed: run the command as usual
  }

  // ---------- SUBSCRIBE ----------

  // SUBSCRIBE channel [channel ...]
  //   -> one reply per channel: ["subscribe", <channel>, <number of channels now subscribed>]
  private static String subscribe(List<String> command, ClientSub client) {
    if (command.size() < 2) {
      return "-ERR wrong number of arguments for 'subscribe' command\r\n";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < command.size(); i++) {
      String channel = command.get(i);
      if (client.channels.add(channel)) {           // false if already subscribed: count stays the same
        subscribers.computeIfAbsent(channel, c -> ConcurrentHashMap.newKeySet()).add(client);
      }
      sb.append("*3\r\n")
        .append(Main.bulkString("subscribe"))
        .append(Main.bulkString(channel))
        .append(":").append(client.channels.size()).append("\r\n");
    }
    return sb.toString();
  }

  // ---------- UNSUBSCRIBE ----------

  // UNSUBSCRIBE [channel ...]   (no channels = unsubscribe from all)
  //   -> one reply per channel: ["unsubscribe", <channel>, <number of channels still subscribed>]
  private static String unsubscribe(List<String> command, ClientSub client) {
    List<String> toRemove = new ArrayList<>();
    if (command.size() >= 2) {
      toRemove.addAll(command.subList(1, command.size()));
    } else {
      toRemove.addAll(client.channels);
    }

    // Unsubscribing with no channels at all still gets one reply
    if (toRemove.isEmpty()) {
      return "*3\r\n" + Main.bulkString("unsubscribe") + Main.bulkString(null) + ":0\r\n";
    }

    StringBuilder sb = new StringBuilder();
    for (String channel : toRemove) {
      if (client.channels.remove(channel)) {
        removeFromChannel(channel, client);
      }
      sb.append("*3\r\n")
        .append(Main.bulkString("unsubscribe"))
        .append(Main.bulkString(channel))
        .append(":").append(client.channels.size()).append("\r\n");
    }
    return sb.toString();            // at 0 channels the client leaves subscribed mode
  }

  private static void removeFromChannel(String channel, ClientSub client) {
    Set<ClientSub> set = subscribers.get(channel);
    if (set != null) {
      set.remove(client);
      if (set.isEmpty()) {
        subscribers.remove(channel, set);
      }
    }
  }

  // ---------- PUBLISH (called from Main.handleCommand) ----------

  // PUBLISH channel message
  //   -> sends ["message", <channel>, <message>] to every subscriber,
  //      replies with how many clients received it
  public static String publish(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'publish' command\r\n";
    }
    String channel = command.get(1);
    String message = command.get(2);

    Set<ClientSub> set = subscribers.get(channel);
    if (set == null || set.isEmpty()) {
      return ":0\r\n";
    }

    byte[] data = ("*3\r\n"
        + Main.bulkString("message")
        + Main.bulkString(channel)
        + Main.bulkString(message)).getBytes(StandardCharsets.UTF_8);

    int delivered = 0;
    for (ClientSub client : set) {
      client.send(data);
      delivered++;
    }
    return ":" + delivered + "\r\n";
  }

  // ---------- Cleanup ----------

  /** Called from Main when a connection closes, so PUBLISH stops sending to it. */
  public static void disconnect(ClientSub client) {
    for (String channel : client.channels) {
      removeFromChannel(channel, client);
    }
    client.channels.clear();
  }
}