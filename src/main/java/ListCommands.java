import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Lists extension: RPUSH (more commands get added here stage by stage). */
public class ListCommands {

  // Each key maps to its own list, e.g. "fruits" -> ["apple", "banana"]
  private static final ConcurrentHashMap<String, List<String>> lists = new ConcurrentHashMap<>();

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "RPUSH":
        return rpush(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // RPUSH key element [element ...]  -> adds to the END, returns the new length
  private static String rpush(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'rpush' command\r\n";
    }
    String key = command.get(1);

    // Get the list for this key, or create an empty one if it doesn't exist yet
    List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());

    int size;
    synchronized (list) {                       // only one thread changes this list at a time
      for (int i = 2; i < command.size(); i++) {
        list.add(command.get(i));
      }
      size = list.size();
    }
    return integer(size);
  }

  // ---------- RESP integer ----------
  // 3 -> :3\r\n
  static String integer(long n) {
    return ":" + n + "\r\n";
  }
}