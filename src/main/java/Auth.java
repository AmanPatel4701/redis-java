import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication extension: ACL WHOAMI, ACL GETUSER, ACL SETUSER, AUTH,
 * and blocking commands until a client has logged in.
 *
 * There is one built-in user, "default". At start it has the "nopass" flag,
 * meaning anyone is logged in automatically. Once it gets a password
 * (ACL SETUSER default >secret), NEW connections must run AUTH before
 * anything else, or they get a NOAUTH error.
 *
 * Passwords are never stored as plain text: we keep their SHA-256 hash,
 * like real Redis.
 */
public class Auth {

  /** One user account. */
  private static class User {
    final String name;
    boolean nopass = true;                                  // anyone can log in without a password
    final Set<String> passwordHashes = new LinkedHashSet<>();   // SHA-256 hex of each password

    User(String name) {
      this.name = name;
    }
  }

  /** Per-connection login state. Created in Main.handleClient. */
  public static class ClientAuth {
    String username = "default";
    boolean authenticated;

    private ClientAuth(boolean authenticated) {
      this.authenticated = authenticated;
    }
  }

  private static final Map<String, User> users = new ConcurrentHashMap<>();

  static {
    users.put("default", new User("default"));
  }

  /** A new connection is logged in automatically only if "default" has nopass. */
  public static ClientAuth newClient() {
    User def = users.get("default");
    synchronized (def) {
      return new ClientAuth(def.nopass);
    }
  }

  // ---------- Entry point (called from Main.handleClient for every command) ----------

  /**
   * Handles AUTH and ACL, and blocks everything else until the client is logged in.
   * Returns the reply, or null to let the command run normally.
   */
  public static String process(List<String> command, ClientAuth client) {
    String name = command.get(0).toUpperCase();

    if (name.equals("AUTH")) {
      return auth(command, client);
    }
    if (!client.authenticated) {
      return "-NOAUTH Authentication required.\r\n";
    }
    if (name.equals("ACL")) {
      return acl(command, client);
    }
    return null;
  }

  // ---------- AUTH ----------

  // AUTH password            (logs in as "default")
  // AUTH username password
  private static String auth(List<String> command, ClientAuth client) {
    String username;
    String password;
    if (command.size() == 2) {
      username = "default";
      password = command.get(1);
    } else if (command.size() == 3) {
      username = command.get(1);
      password = command.get(2);
    } else {
      return "-ERR wrong number of arguments for 'auth' command\r\n";
    }

    User user = users.get(username);
    if (user != null) {
      synchronized (user) {
        if (user.nopass || user.passwordHashes.contains(sha256(password))) {
          client.username = username;
          client.authenticated = true;
          return "+OK\r\n";
        }
      }
    }
    return "-WRONGPASS invalid username-password pair or user is disabled.\r\n";
  }

  // ---------- ACL ----------

  private static String acl(List<String> command, ClientAuth client) {
    if (command.size() < 2) {
      return "-ERR wrong number of arguments for 'acl' command\r\n";
    }
    String sub = command.get(1).toUpperCase();
    switch (sub) {
      case "WHOAMI":
        return Main.bulkString(client.username);
      case "GETUSER":
        return getUser(command);
      case "SETUSER":
        return setUser(command);
      default:
        return "-ERR unknown subcommand '" + command.get(1) + "'\r\n";
    }
  }

  // ACL GETUSER name -> ["flags", [...], "passwords", [...]]   (null if the user doesn't exist)
  private static String getUser(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'acl|getuser' command\r\n";
    }
    User user = users.get(command.get(2));
    if (user == null) {
      return Main.bulkString(null);
    }

    List<String> flags = new ArrayList<>();
    List<String> passwords;
    synchronized (user) {
      if (user.nopass) {
        flags.add("nopass");
      }
      passwords = new ArrayList<>(user.passwordHashes);
    }

    return "*4\r\n"
        + Main.bulkString("flags") + stringArray(flags)
        + Main.bulkString("passwords") + stringArray(passwords);
  }

  // ACL SETUSER name rule [rule ...]
  //   >password   add a password (and turn off nopass)
  //   <password   remove a password
  //   nopass      allow logging in without a password (clears all passwords)
  //   resetpass   remove all passwords and turn off nopass
  private static String setUser(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'acl|setuser' command\r\n";
    }
    User user = users.computeIfAbsent(command.get(2), User::new);

    synchronized (user) {
      for (int i = 3; i < command.size(); i++) {
        String rule = command.get(i);
        if (rule.startsWith(">")) {
          user.passwordHashes.add(sha256(rule.substring(1)));
          user.nopass = false;
        } else if (rule.startsWith("<")) {
          user.passwordHashes.remove(sha256(rule.substring(1)));
        } else if (rule.equalsIgnoreCase("nopass")) {
          user.nopass = true;
          user.passwordHashes.clear();
        } else if (rule.equalsIgnoreCase("resetpass")) {
          user.nopass = false;
          user.passwordHashes.clear();
        } else {
          return "-ERR Error in ACL SETUSER modifier '" + rule + "': Syntax error\r\n";
        }
      }
    }
    return "+OK\r\n";
  }

  // ---------- Helpers ----------

  /** SHA-256 of the password, as 64 lowercase hex characters. */
  private static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String stringArray(List<String> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("*").append(items.size()).append("\r\n");
    for (String item : items) {
      sb.append(Main.bulkString(item));
    }
    return sb.toString();
  }
}