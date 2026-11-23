import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Lists extension: RPUSH, LRANGE. */
public class ListCommands {

  // Each key maps to its own list, e.g. "fruits" -> ["apple", "banana"]
  private static final ConcurrentHashMap<String, List<String>> lists = new ConcurrentHashMap<>();

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "RPUSH":
        return rpush(command);
      case "LRANGE":
        return lrange(command);
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
    List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());

    int size;
    synchronized (list) {
      for (int i = 2; i < command.size(); i++) {
        list.add(command.get(i));
      }
      size = list.size();
    }
    return integer(size);
  }

  // LRANGE key start stop  -> elements from start to stop (stop is included)
  private static String lrange(List<String> command) {
    if (command.size() < 4) {
      return "-ERR wrong number of arguments for 'lrange' command\r\n";
    }
    String key = command.get(1);
    int start, stop;
    try {
      start = Integer.parseInt(command.get(2));
      stop = Integer.parseInt(command.get(3));
    } catch (NumberFormatException e) {
      return "-ERR value is not an integer or out of range\r\n";
    }

    List<String> list = lists.get(key);
    if (list == null) {
      return array(List.of());              // missing key -> empty array
    }

    List<String> result;
    synchronized (list) {
      int size = list.size();

      // Negative indexes count from the end: -1 = last item, -2 = second last
      if (start < 0) start = size + start;
      if (stop < 0) stop = size + stop;

      // Keep the indexes inside the list
      if (start < 0) start = 0;
      if (stop >= size) stop = size - 1;

      if (start > stop || start >= size) {
        return array(List.of());            // nothing in that range
      }

      // subList's end is exclusive, so +1 to include stop
      result = new ArrayList<>(list.subList(start, stop + 1));
    }
    return array(result);
  }

  // ---------- RESP helpers ----------

  // 3 -> :3\r\n
  static String integer(long n) {
    return ":" + n + "\r\n";
  }

  // ["a","b"] -> *2\r\n$1\r\na\r\n$1\r\nb\r\n
  static String array(List<String> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("*").append(items.size()).append("\r\n");
    for (String item : items) {
      sb.append(Main.bulkString(item));      // reuses your encoder from Main.java
    }
    return sb.toString();
  }
}