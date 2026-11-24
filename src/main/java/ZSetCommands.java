import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Sorted Sets extension: ZADD, ZRANK, ZRANGE, ZCARD, ZSCORE, ZREM.
 *
 * A sorted set holds unique members, each with a number (its score).
 * Members are kept ordered by score, and members with the same score are
 * ordered alphabetically.
 *
 * Each sorted set uses two structures:
 *   - a HashMap   member -> score    (fast "what is this member's score?")
 *   - a TreeSet   ordered by (score, member)   (fast ordering for ranks and ranges)
 */
public class ZSetCommands {

  /** One member with its score, used inside the TreeSet. */
  private record Member(double score, String name) {}

  // Order by score first, then by member name
  private static final Comparator<Member> ORDER =
      Comparator.comparingDouble(Member::score).thenComparing(Member::name);

  /** One sorted set. */
  private static class SortedSet {
    final Map<String, Double> scores = new HashMap<>();
    final TreeSet<Member> ordered = new TreeSet<>(ORDER);
  }

  // One lock guards all sorted set data (same idea as ListCommands)
  private static final Object lock = new Object();

  // key -> its sorted set
  private static final Map<String, SortedSet> sets = new HashMap<>();

  // ---------- Entry point ----------

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "ZADD":
        return zadd(command);
      case "ZRANK":
        return zrank(command);
      case "ZRANGE":
        return zrange(command);
      case "ZCARD":
        return zcard(command);
      case "ZSCORE":
        return zscore(command);
      case "ZREM":
        return zrem(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  /** True if a sorted set exists for this key. Used by the TYPE command. */
  public static boolean hasKey(String key) {
    synchronized (lock) {
      return sets.containsKey(key);
    }
  }

  // ---------- ZADD ----------

  // ZADD key score member [score member ...]
  //   -> number of NEW members added (changing an existing member's score doesn't count)
  private static String zadd(List<String> command) {
    if (command.size() < 4 || (command.size() - 2) % 2 != 0) {
      return wrongArgs("zadd");
    }
    String key = command.get(1);

    // Check every score first, so a bad one doesn't leave a half-done ZADD
    List<Double> newScores = new ArrayList<>();
    for (int i = 2; i < command.size(); i += 2) {
      try {
        newScores.add(Double.parseDouble(command.get(i)));
      } catch (NumberFormatException e) {
        return "-ERR value is not a valid float\r\n";
      }
    }

    synchronized (lock) {
      SortedSet set = sets.computeIfAbsent(key, k -> new SortedSet());
      int added = 0;

      for (int i = 2, s = 0; i < command.size(); i += 2, s++) {
        double score = newScores.get(s);
        String member = command.get(i + 1);

        Double old = set.scores.put(member, score);
        if (old == null) {
          added++;                                        // brand-new member
        } else {
          set.ordered.remove(new Member(old, member));    // take out the old position
        }
        set.ordered.add(new Member(score, member));       // insert at the new position
      }
      return integer(added);
    }
  }

  // ---------- ZRANK ----------

  // ZRANK key member -> 0-based position in the order, or null if missing
  private static String zrank(List<String> command) {
    if (command.size() < 3) {
      return wrongArgs("zrank");
    }
    synchronized (lock) {
      SortedSet set = sets.get(command.get(1));
      if (set == null) return Main.bulkString(null);

      String member = command.get(2);
      Double score = set.scores.get(member);
      if (score == null) return Main.bulkString(null);

      // Rank = how many members come before this one
      int rank = set.ordered.headSet(new Member(score, member)).size();
      return integer(rank);
    }
  }

  // ---------- ZRANGE ----------

  // ZRANGE key start stop -> members from start to stop (stop included), like LRANGE
  private static String zrange(List<String> command) {
    if (command.size() < 4) {
      return wrongArgs("zrange");
    }
    int start, stop;
    try {
      start = Integer.parseInt(command.get(2));
      stop = Integer.parseInt(command.get(3));
    } catch (NumberFormatException e) {
      return "-ERR value is not an integer or out of range\r\n";
    }

    synchronized (lock) {
      SortedSet set = sets.get(command.get(1));
      if (set == null) return array(List.of());          // missing key -> empty array

      int size = set.ordered.size();

      // Negative indexes count from the end: -1 = last member
      if (start < 0) start = size + start;
      if (stop < 0) stop = size + stop;

      // Keep the indexes inside the set
      if (start < 0) start = 0;
      if (stop >= size) stop = size - 1;

      if (start > stop || start >= size) {
        return array(List.of());                          // nothing in that range
      }

      List<String> result = new ArrayList<>();
      int index = 0;
      for (Member m : set.ordered) {                      // walks members in sorted order
        if (index > stop) break;
        if (index >= start) result.add(m.name());
        index++;
      }
      return array(result);
    }
  }

  // ---------- ZCARD ----------

  // ZCARD key -> number of members (0 if the key doesn't exist)
  private static String zcard(List<String> command) {
    if (command.size() < 2) {
      return wrongArgs("zcard");
    }
    synchronized (lock) {
      SortedSet set = sets.get(command.get(1));
      return integer(set == null ? 0 : set.scores.size());
    }
  }

  // ---------- ZSCORE ----------

  // ZSCORE key member -> the score as a bulk string, or null if missing
  private static String zscore(List<String> command) {
    if (command.size() < 3) {
      return wrongArgs("zscore");
    }
    synchronized (lock) {
      SortedSet set = sets.get(command.get(1));
      if (set == null) return Main.bulkString(null);

      Double score = set.scores.get(command.get(2));
      if (score == null) return Main.bulkString(null);
      return Main.bulkString(formatScore(score));
    }
  }

  /** 24.34 -> "24.34",  10.0 -> "10"  (no trailing ".0", no scientific notation) */
  private static String formatScore(double score) {
    if (Double.isInfinite(score)) {
      return score > 0 ? "inf" : "-inf";
    }
    return BigDecimal.valueOf(score).stripTrailingZeros().toPlainString();
  }

  // ---------- ZREM ----------

  // ZREM key member [member ...] -> number of members actually removed
  private static String zrem(List<String> command) {
    if (command.size() < 3) {
      return wrongArgs("zrem");
    }
    String key = command.get(1);

    synchronized (lock) {
      SortedSet set = sets.get(key);
      if (set == null) return integer(0);

      int removed = 0;
      for (int i = 2; i < command.size(); i++) {
        String member = command.get(i);
        Double score = set.scores.remove(member);
        if (score != null) {
          set.ordered.remove(new Member(score, member));
          removed++;
        }
      }
      if (set.scores.isEmpty()) {
        sets.remove(key);                                 // an empty sorted set disappears
      }
      return integer(removed);
    }
  }

  // ---------- RESP helpers ----------

  private static String integer(long n) {
    return ":" + n + "\r\n";
  }

  private static String array(List<String> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("*").append(items.size()).append("\r\n");
    for (String item : items) {
      sb.append(Main.bulkString(item));
    }
    return sb.toString();
  }

  private static String wrongArgs(String cmd) {
    return "-ERR wrong number of arguments for '" + cmd + "' command\r\n";
  }
}