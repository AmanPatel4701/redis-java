import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Lists extension: RPUSH, LPUSH, LRANGE, LLEN, LPOP. */
public class ListCommands {

  // Each key maps to its own list, e.g. "fruits" -> ["apple", "banana"]
  private static final ConcurrentHashMap<String, List<String>> lists = new ConcurrentHashMap<>();

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "RPUSH":
        return rpush(command);
      case "LPUSH":
        return lpush(command);
      case "LRANGE":
        return lrange(command);
      case "LLEN":
        return llen(command);
      case "LPOP":
        return lpop(command);
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
    synchronized (list) {                   // only one thread changes this list at a time
      for (int i = 2; i < command.size(); i++) {
        list.add(command.get(i));
      }
      size = list.size();
    }
    return integer(size);
  }

  // LPUSH key element [element ...]  -> adds to the START, returns the new length
  private static String lpush(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'lpush' command\r\n";
    }
    String key = command.get(1);
    List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());

    int size;
    synchronized (list) {
      for (int i = 2; i < command.size(); i++) {
        list.add(0, command.get(i));        // insert at index 0 = the front
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

  // LLEN key  -> number of elements (0 if the key doesn't exist)
  private static String llen(List<String> command) {
    if (command.size() < 2) {
      return "-ERR wrong number of arguments for 'llen' command\r\n";
    }
    List<String> list = lists.get(command.get(1));
    if (list == null) {
      return integer(0);
    }
    synchronized (list) {
      return integer(list.size());
    }
  }

  // LPOP key [count]
  //   no count -> removes the first element, returns it as a bulk string (or null)
  //   count    -> removes up to count elements, returns them as an array
  private static String lpop(List<String> command) {
    if (command.size() < 2) {
      return "-ERR wrong number of arguments for 'lpop' command\r\n";
    }
    String key = command.get(1);
    boolean hasCount = command.size() >= 3;

    int count = 1;
    if (hasCount) {
      try {
        count = Integer.parseInt(command.get(2));
      } catch (NumberFormatException e) {
        return "-ERR value is not an integer or out of range\r\n";
      }
    }

    List<String> list = lists.get(key);
    if (list == null) {
      return Main.bulkString(null);         // missing key -> $-1\r\n
    }

    List<String> removed = new ArrayList<>();
    synchronized (list) {
      for (int i = 0; i < count && !list.isEmpty(); i++) {
        removed.add(list.remove(0));        // take from the front
      }
      if (list.isEmpty()) {
        lists.remove(key, list);            // Redis deletes a list once it's empty
      }
    }

    if (hasCount) {
      return array(removed);                // LPOP key 2 -> array of elements
    }
    return Main.bulkString(removed.isEmpty() ? null : removed.get(0));
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
      sb.append(Main.bulkString(item));     // reuses your encoder from Main.java
    }
    return sb.toString();
  }
}