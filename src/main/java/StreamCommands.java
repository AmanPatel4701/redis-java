import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streams extension: XADD, XRANGE, XREAD (with BLOCK and $).
 *
 * A stream is an append-only log. Every entry has an ID like "1526919030474-0"
 * (milliseconds-sequence) and a list of field/value pairs.
 */
public class StreamCommands {

  // One lock guards all stream data. XREAD BLOCK waits on it; XADD wakes it up.
  private static final Object lock = new Object();

  // key -> entries, always kept in increasing ID order
  private static final Map<String, List<StreamEntry>> streams = new HashMap<>();

  // ---------- Data types ----------

  /** An entry ID: milliseconds + sequence number, e.g. 1526919030474-0 */
  record StreamId(long ms, long seq) implements Comparable<StreamId> {
    static final StreamId ZERO = new StreamId(0, 0);
    static final StreamId MAX = new StreamId(Long.MAX_VALUE, Long.MAX_VALUE);

    @Override
    public int compareTo(StreamId other) {
      if (ms != other.ms) return Long.compare(ms, other.ms);
      return Long.compare(seq, other.seq);
    }

    @Override
    public String toString() {
      return ms + "-" + seq;
    }
  }

  /** One stream entry: its ID and its field/value pairs, e.g. ["temperature","36","humidity","95"] */
  record StreamEntry(StreamId id, List<String> fields) {}

  // ---------- Entry point ----------

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "XADD":
        return xadd(command);
      case "XRANGE":
        return xrange(command);
      case "XREAD":
        return xread(command);
      default:
        return error("ERR unknown command '" + command.get(0) + "'");
    }
  }

  /** True if a stream exists for this key. Used by the TYPE command. */
  public static boolean hasKey(String key) {
    synchronized (lock) {
      return streams.containsKey(key);
    }
  }

  // ---------- XADD ----------

  // XADD key id field value [field value ...]
  //   id can be:  1526919030474-0  (explicit)
  //               1526919030474-*  (server picks the sequence)
  //               *                (server picks everything)
  private static String xadd(List<String> command) {
    if (command.size() < 5 || (command.size() - 3) % 2 != 0) {
      return error("ERR wrong number of arguments for 'xadd' command");
    }
    String key = command.get(1);
    String rawId = command.get(2);
    List<String> fields = new ArrayList<>(command.subList(3, command.size()));

    synchronized (lock) {
      List<StreamEntry> stream = streams.get(key);
      StreamId last = (stream == null || stream.isEmpty())
          ? StreamId.ZERO
          : stream.get(stream.size() - 1).id();

      // 1. Work out the actual ID
      StreamId id;
      try {
        id = resolveId(rawId, last);
      } catch (NumberFormatException e) {
        return error("ERR Invalid stream ID specified as stream command argument");
      }

      // 2. Validate it
      if (id.compareTo(StreamId.ZERO) == 0) {
        return error("ERR The ID specified in XADD must be greater than 0-0");
      }
      if (id.compareTo(last) <= 0) {
        return error("ERR The ID specified in XADD is equal or smaller than the target stream top item");
      }

      // 3. Store it and wake up any blocked XREAD clients
      if (stream == null) {
        stream = new ArrayList<>();
        streams.put(key, stream);
      }
      stream.add(new StreamEntry(id, fields));
      lock.notifyAll();

      return Main.bulkString(id.toString());
    }
  }

  /** Turns "*", "123-*" or "123-4" into a real ID, based on the stream's last ID. */
  private static StreamId resolveId(String rawId, StreamId last) {
    // Fully auto-generated: current time + next sequence
    if (rawId.equals("*")) {
      long now = System.currentTimeMillis();
      long ms = Math.max(now, last.ms());          // never go backwards
      long seq = (ms == last.ms()) ? last.seq() + 1 : 0;
      return new StreamId(ms, seq);
    }

    String[] parts = rawId.split("-", 2);
    long ms = Long.parseLong(parts[0]);

    // Partially auto-generated: we pick the sequence number
    if (parts.length == 2 && parts[1].equals("*")) {
      long seq;
      if (ms == last.ms()) {
        seq = last.seq() + 1;                      // same millisecond -> next sequence
      } else {
        seq = (ms == 0) ? 1 : 0;                   // 0-0 isn't allowed, so time 0 starts at 1
      }
      return new StreamId(ms, seq);
    }

    // Explicit: "ms-seq" (or just "ms", which means seq 0)
    long seq = (parts.length == 2) ? Long.parseLong(parts[1]) : 0;
    return new StreamId(ms, seq);
  }

  // ---------- XRANGE ----------

  // XRANGE key start end
  //   "-" = from the very first entry,  "+" = up to the very last entry
  //   "1526919030474" without a sequence = seq 0 for start, max seq for end
  private static String xrange(List<String> command) {
    if (command.size() < 4) {
      return error("ERR wrong number of arguments for 'xrange' command");
    }
    String key = command.get(1);

    StreamId start, end;
    try {
      start = parseRangeId(command.get(2), true);
      end = parseRangeId(command.get(3), false);
    } catch (NumberFormatException e) {
      return error("ERR Invalid stream ID specified as stream command argument");
    }

    List<StreamEntry> result = new ArrayList<>();
    synchronized (lock) {
      List<StreamEntry> stream = streams.get(key);
      if (stream != null) {
        for (StreamEntry entry : stream) {
          if (entry.id().compareTo(start) >= 0 && entry.id().compareTo(end) <= 0) {
            result.add(entry);
          }
        }
      }
    }
    return encodeEntries(result);
  }

  private static StreamId parseRangeId(String raw, boolean isStart) {
    if (raw.equals("-")) return StreamId.ZERO;
    if (raw.equals("+")) return StreamId.MAX;

    String[] parts = raw.split("-", 2);
    long ms = Long.parseLong(parts[0]);
    if (parts.length == 2) {
      return new StreamId(ms, Long.parseLong(parts[1]));
    }
    return new StreamId(ms, isStart ? 0 : Long.MAX_VALUE);
  }

  // ---------- XREAD ----------

  // XREAD [BLOCK ms] STREAMS key1 key2 ... id1 id2 ...
  //   Returns entries with an ID strictly GREATER than the given ID.
  //   "$" = only entries added after this command starts.
  //   BLOCK 0 = wait forever.  Timeout with nothing new -> null array.
  private static String xread(List<String> command) {
    long blockMillis = -1;      // -1 = don't block
    int streamsIndex = -1;

    // Find the BLOCK and STREAMS options
    for (int i = 1; i < command.size(); i++) {
      String arg = command.get(i).toUpperCase();
      if (arg.equals("BLOCK") && i + 1 < command.size()) {
        try {
          blockMillis = Long.parseLong(command.get(i + 1));
        } catch (NumberFormatException e) {
          return error("ERR timeout is not an integer or out of range");
        }
        i++;
      } else if (arg.equals("STREAMS")) {
        streamsIndex = i;
        break;
      }
    }

    if (streamsIndex == -1) {
      return error("ERR syntax error");
    }

    // After STREAMS: first half are keys, second half are IDs
    List<String> rest = command.subList(streamsIndex + 1, command.size());
    if (rest.isEmpty() || rest.size() % 2 != 0) {
      return error("ERR Unbalanced 'xread' list of streams: for each stream key an ID must be specified");
    }
    int n = rest.size() / 2;
    List<String> keys = rest.subList(0, n);
    List<String> rawIds = rest.subList(n, rest.size());

    synchronized (lock) {
      // Resolve every starting ID now ("$" = the stream's current last ID)
      List<StreamId> afterIds = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        String raw = rawIds.get(i);
        if (raw.equals("$")) {
          afterIds.add(lastId(keys.get(i)));
        } else {
          try {
            afterIds.add(parseRangeId(raw, true));
          } catch (NumberFormatException e) {
            return error("ERR Invalid stream ID specified as stream command argument");
          }
        }
      }

      long deadline = System.currentTimeMillis() + blockMillis;

      while (true) {
        String result = collectNewEntries(keys, afterIds);
        if (result != null) {
          return result;                          // found something
        }
        if (blockMillis < 0) {
          return "*-1\r\n";                       // not blocking: nothing new -> null
        }

        try {
          if (blockMillis == 0) {
            lock.wait();                          // BLOCK 0: wait until an XADD wakes us
          } else {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
              return "*-1\r\n";                   // timed out
            }
            lock.wait(remaining);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return "*-1\r\n";
        }
      }
    }
  }

  /** Builds the XREAD reply, or returns null if no stream has new entries. Caller holds the lock. */
  private static String collectNewEntries(List<String> keys, List<StreamId> afterIds) {
    StringBuilder body = new StringBuilder();
    int streamsWithData = 0;

    for (int i = 0; i < keys.size(); i++) {
      List<StreamEntry> newer = new ArrayList<>();
      List<StreamEntry> stream = streams.get(keys.get(i));
      if (stream != null) {
        for (StreamEntry entry : stream) {
          if (entry.id().compareTo(afterIds.get(i)) > 0) {   // strictly greater
            newer.add(entry);
          }
        }
      }
      if (!newer.isEmpty()) {
        // Each stream in the reply: [key, [entries...]]
        body.append("*2\r\n");
        body.append(Main.bulkString(keys.get(i)));
        body.append(encodeEntries(newer));
        streamsWithData++;
      }
    }

    if (streamsWithData == 0) return null;
    return "*" + streamsWithData + "\r\n" + body;
  }

  /** The last ID in a stream, or 0-0 if it doesn't exist. Caller holds the lock. */
  private static StreamId lastId(String key) {
    List<StreamEntry> stream = streams.get(key);
    if (stream == null || stream.isEmpty()) return StreamId.ZERO;
    return stream.get(stream.size() - 1).id();
  }

  // ---------- RESP helpers ----------

  // A list of entries:
  // *N
  //   *2
  //     $id
  //     *fields
  //       $field $value ...
  private static String encodeEntries(List<StreamEntry> entries) {
    StringBuilder sb = new StringBuilder();
    sb.append("*").append(entries.size()).append("\r\n");
    for (StreamEntry entry : entries) {
      sb.append("*2\r\n");
      sb.append(Main.bulkString(entry.id().toString()));
      sb.append("*").append(entry.fields().size()).append("\r\n");
      for (String f : entry.fields()) {
        sb.append(Main.bulkString(f));
      }
    }
    return sb.toString();
  }

  private static String error(String message) {
    return "-" + message + "\r\n";
  }
}