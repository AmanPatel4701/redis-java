import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transactions extension: MULTI, EXEC, DISCARD, WATCH, UNWATCH.
 *
 * MULTI starts a transaction. After that, commands are not run right away:
 * they are queued (reply +QUEUED). EXEC runs the whole queue in order and
 * replies with an array of every command's result. DISCARD throws the queue away.
 *
 * WATCH adds "optimistic locking": if any watched key is changed by anyone
 * between WATCH and EXEC, EXEC refuses to run and replies with a null array.
 *
 * Transactions belong to ONE client connection, so every connection gets its
 * own ClientState (created in Main.handleClient).
 */
public class TxCommands {

  /** Per-connection transaction state. */
  public static class ClientState {
    boolean inTransaction = false;
    final List<List<String>> queued = new ArrayList<>();
    // key -> the key's version number at the moment it was WATCHed
    final Map<String, Long> watched = new HashMap<>();
  }

  // ---------- Key versions (how WATCH notices changes) ----------
  // Every time a key is written, its version goes up by 1.
  // A key that was never written has version 0, so watching a missing key
  // still works: if someone creates it later, its version changes.
  private static final ConcurrentHashMap<String, Long> versions = new ConcurrentHashMap<>();

  // Commands that change the key in command.get(1)
  private static final Set<String> WRITE_COMMANDS = Set.of(
      "SET", "INCR", "RPUSH", "LPUSH", "LPOP", "BLPOP", "XADD");

  // Commands that can wait for a long time. They must not hold execLock,
  // or every other client would be stuck behind them.
  private static final Set<String> BLOCKING_COMMANDS = Set.of("BLPOP", "XREAD", "WAIT");

  // Makes "check watched keys + run the transaction" one uninterruptible step,
  // so no other client can sneak a write in between.
  private static final Object execLock = new Object();

  private static long versionOf(String key) {
    return versions.getOrDefault(key, 0L);
  }

  private static void markModified(String key) {
    versions.merge(key, 1L, Long::sum);
  }

  // ---------- Entry point: every command from a client goes through here ----------

  public static String process(List<String> command, ClientState state) {
    String name = command.get(0).toUpperCase();

    switch (name) {
      case "MULTI":
        if (state.inTransaction) {
          return "-ERR MULTI calls can not be nested\r\n";
        }
        state.inTransaction = true;
        state.queued.clear();
        return "+OK\r\n";

      case "EXEC":
        if (!state.inTransaction) {
          return "-ERR EXEC without MULTI\r\n";
        }
        return exec(state);

      case "DISCARD":
        if (!state.inTransaction) {
          return "-ERR DISCARD without MULTI\r\n";
        }
        state.inTransaction = false;
        state.queued.clear();
        state.watched.clear();               // DISCARD also unwatches everything
        return "+OK\r\n";

      case "WATCH":
        if (state.inTransaction) {
          return "-ERR WATCH inside MULTI is not allowed\r\n";
        }
        if (command.size() < 2) {
          return "-ERR wrong number of arguments for 'watch' command\r\n";
        }
        for (int i = 1; i < command.size(); i++) {
          String key = command.get(i);
          // Remember the version only the first time, so watching twice
          // doesn't hide a change that happened in between
          state.watched.putIfAbsent(key, versionOf(key));
        }
        return "+OK\r\n";

      case "UNWATCH":
        state.watched.clear();
        return "+OK\r\n";

      default:
        if (state.inTransaction) {
          state.queued.add(command);         // don't run it yet, just remember it
          return "+QUEUED\r\n";
        }
        return run(command);                 // normal command, run it now
    }
  }

  // ---------- EXEC ----------

  // Runs every queued command in order and returns all replies as one array.
  // If a watched key changed, nothing runs and the reply is a null array (*-1).
  // If one command fails (e.g. INCR on a non-number), its slot holds the error
  // and the other commands still run, just like real Redis.
  private static String exec(ClientState state) {
    List<List<String>> toRun = new ArrayList<>(state.queued);
    Map<String, Long> watched = new HashMap<>(state.watched);

    // EXEC always ends the transaction and unwatches, whatever happens
    state.inTransaction = false;
    state.queued.clear();
    state.watched.clear();

    synchronized (execLock) {
      // 1. Has any watched key changed since WATCH?
      for (Map.Entry<String, Long> w : watched.entrySet()) {
        if (versionOf(w.getKey()) != w.getValue()) {
          return "*-1\r\n";                  // transaction aborted
        }
      }

      // 2. Nothing changed: run everything
      StringBuilder sb = new StringBuilder();
      sb.append("*").append(toRun.size()).append("\r\n");   // empty transaction -> *0\r\n
      for (List<String> cmd : toRun) {
        sb.append(execute(cmd));             // each reply is already valid RESP
      }
      return sb.toString();
    }
  }

  // ---------- Running a single command ----------

  /** Runs a command outside a transaction. */
  private static String run(List<String> command) {
    String name = command.get(0).toUpperCase();
    if (BLOCKING_COMMANDS.contains(name)) {
      return execute(command);               // may wait a long time: don't hold execLock
    }
    synchronized (execLock) {
      return execute(command);
    }
  }

  /** Runs the command and bumps the key's version if it was a write. */
  private static String execute(List<String> command) {
    String response = Main.handleCommand(command);
    String name = command.get(0).toUpperCase();
    if (WRITE_COMMANDS.contains(name) && command.size() >= 2 && !response.startsWith("-")) {
      markModified(command.get(1));          // failed commands don't count as changes
      Replication.propagate(command);        // copy the write to every replica
      Aof.append(command);                   // save the write to the AOF file
    }
    return response;
  }
}