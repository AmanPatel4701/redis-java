import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lists extension: RPUSH, LPUSH, LRANGE, LLEN, LPOP, BLPOP. */
public class ListCommands {

  // One lock guards ALL list data. BLPOP waits on it; pushes wake it up.
  private static final Object lock = new Object();

  // key -> list of elements, e.g. "fruits" -> ["apple", "banana"]
  private static final Map<String, List<String>> lists = new HashMap<>();

  // key -> clients currently blocked in BLPOP on that key, oldest first
  private static final Map<String, Deque<Waiter>> waiters = new HashMap<>();

  /** A client blocked in BLPOP, waiting for an element. */
  private static class Waiter {
    String value;          // filled in by a push
    boolean served;        // true once a push handed this waiter an element
  }

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "RPUSH":
        return push(command, false);
      case "LPUSH":
        return push(command, true);
      case "LRANGE":
        return lrange(command);
      case "LLEN":
        return llen(command);
      case "LPOP":
        return lpop(command);
      case "BLPOP":
        return blpop(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // RPUSH / LPUSH key element [element ...]  -> returns the new length
  private static String push(List<String> command, boolean toFront) {
    if (command.size() < 3) {
      String cmd = toFront ? "lpush" : "rpush";
      return "-ERR wrong number of arguments for '" + cmd + "' command\r\n";
    }
    String key = command.get(1);

    synchronized (lock) {
      List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());
      for (int i = 2; i < command.size(); i++) {
        if (toFront) {
          list.add(0, command.get(i));      // LPUSH: insert at the front
        } else {
          list.add(command.get(i));         // RPUSH: append at the end
        }
      }
      int size = list.size();               // length AFTER the push (what Redis returns)

      serveWaiters(key, list);              // hand elements to any blocked BLPOP clients
      return integer(size);
    }
  }

  /** Gives elements to blocked clients, oldest first. Must be called while holding the lock. */
  private static void serveWaiters(String key, List<String> list) {
    Deque<Waiter> queue = waiters.get(key);
    if (queue == null) return;

    boolean anyServed = false;
    while (!queue.isEmpty() && !list.isEmpty()) {
      Waiter w = queue.pollFirst();
      w.value = list.remove(0);
      w.served = true;
      anyServed = true;
    }
    if (queue.isEmpty()) waiters.remove(key);
    if (list.isEmpty()) lists.remove(key);
    if (anyServed) lock.notifyAll();        // wake the blocked threads
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

    synchronized (lock) {
      List<String> list = lists.get(key);
      if (list == null) {
        return array(List.of());            // missing key -> empty array
      }
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
      return array(new ArrayList<>(list.subList(start, stop + 1)));
    }
  }

  // LLEN key  -> number of elements (0 if the key doesn't exist)
  private static String llen(List<String> command) {
    if (command.size() < 2) {
      return "-ERR wrong number of arguments for 'llen' command\r\n";
    }
    synchronized (lock) {
      List<String> list = lists.get(command.get(1));
      return integer(list == null ? 0 : list.size());
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

    List<String> removed = new ArrayList<>();
    synchronized (lock) {
      List<String> list = lists.get(key);
      if (list == null) {
        return Main.bulkString(null);       // missing key -> $-1\r\n
      }
      for (int i = 0; i < count && !list.isEmpty(); i++) {
        removed.add(list.remove(0));        // take from the front
      }
      if (list.isEmpty()) {
        lists.remove(key);                  // Redis deletes a list once it's empty
      }
    }

    if (hasCount) {
      return array(removed);
    }
    return Main.bulkString(removed.isEmpty() ? null : removed.get(0));
  }

  // BLPOP key timeout
  //   list has items -> pop immediately, reply [key, element]
  //   list is empty  -> wait until a push arrives or the timeout runs out
  //   timeout 0      -> wait forever;  on timeout reply with a null array *-1\r\n
  private static String blpop(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'blpop' command\r\n";
    }
    String key = command.get(1);

    long timeoutMillis;
    try {
      double seconds = Double.parseDouble(command.get(2));   // can be a decimal, e.g. 0.5
      if (seconds < 0) {
        return "-ERR timeout is negative\r\n";
      }
      timeoutMillis = (long) (seconds * 1000);
    } catch (NumberFormatException e) {
      return "-ERR timeout is not a float or out of range\r\n";
    }

    synchronized (lock) {
      // 1. Something is already there: pop right away
      List<String> list = lists.get(key);
      if (list != null && !list.isEmpty()) {
        String value = list.remove(0);
        if (list.isEmpty()) lists.remove(key);
        return array(List.of(key, value));
      }

      // 2. Nothing there: join the queue for this key and wait
      Waiter me = new Waiter();
      waiters.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(me);

      long deadline = System.currentTimeMillis() + timeoutMillis;
      try {
        while (!me.served) {
          if (timeoutMillis == 0) {
            lock.wait();                        // wait forever, until a push wakes us
          } else {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;          // timed out
            lock.wait(remaining);               // sleep, but no longer than what's left
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // 3. Either a push served us, or we timed out
      if (me.served) {
        return array(List.of(key, me.value));
      }

      // Timed out: leave the queue so no push tries to serve us later
      Deque<Waiter> queue = waiters.get(key);
      if (queue != null) {
        queue.remove(me);
        if (queue.isEmpty()) waiters.remove(key);
      }
      return "*-1\r\n";                         // null array
    }
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
      sb.append(Main.bulkString(item));         // reuses your encoder from Main.java
    }
    return sb.toString();
  }
}