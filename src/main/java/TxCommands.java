import java.util.ArrayList;
import java.util.List;

/**
 * Transactions extension: MULTI, EXEC, DISCARD.
 *
 * MULTI starts a transaction. After that, commands are not run right away:
 * they are queued (reply +QUEUED). EXEC runs the whole queue in order and
 * replies with an array of every command's result. DISCARD throws the queue away.
 *
 * Transactions belong to ONE client connection, so every connection gets its
 * own ClientState (created in Main.handleClient).
 */
public class TxCommands {

  /** Per-connection transaction state. */
  public static class ClientState {
    boolean inTransaction = false;
    final List<List<String>> queued = new ArrayList<>();
  }

  /** Every command from a client goes through here first. */
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
        return "+OK\r\n";

      default:
        if (state.inTransaction) {
          state.queued.add(command);       // don't run it yet, just remember it
          return "+QUEUED\r\n";
        }
        return Main.handleCommand(command); // normal command, run it now
    }
  }

  // Runs every queued command in order and returns all replies as one array.
  // If one command fails (e.g. INCR on a non-number), its slot holds the error
  // and the other commands still run, just like real Redis.
  private static String exec(ClientState state) {
    List<List<String>> toRun = new ArrayList<>(state.queued);
    state.inTransaction = false;
    state.queued.clear();

    StringBuilder sb = new StringBuilder();
    sb.append("*").append(toRun.size()).append("\r\n");   // empty transaction -> *0\r\n
    for (List<String> cmd : toRun) {
      sb.append(Main.handleCommand(cmd));   // each reply is already valid RESP
    }
    return sb.toString();
  }
}