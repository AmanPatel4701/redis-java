import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replication extension.
 *
 * MASTER side:
 *   - answers INFO, REPLCONF, PSYNC (sends +FULLRESYNC and an empty RDB file)
 *   - propagates every write command to all connected replicas
 *   - WAIT: asks replicas how far they've got (REPLCONF GETACK) and counts the answers
 *
 * REPLICA side (started with --replicaof "host port"):
 *   - handshake: PING -> REPLCONF listening-port -> REPLCONF capa -> PSYNC
 *   - receives the RDB file, then applies every command the master sends, silently
 *   - answers REPLCONF GETACK with REPLCONF ACK <bytes processed>
 */
public class Replication {

  // ---------- State ----------

  private static String role = "master";       // "master" or "slave"
  private static String masterHost = null;      // only set on a replica
  private static int masterPort = -1;

  // A 40-character ID for this master's dataset
  private static final String replId = "8371b4fb1155b71f4a04d3e1bc3e18c4a990aeeb";

  // MASTER: total bytes of commands sent to replicas so far
  private static long replOffset = 0;

  // MASTER: all connected replicas
  private static final List<ReplicaLink> replicas = new CopyOnWriteArrayList<>();

  // Guards replOffset and the order of bytes sent to replicas
  private static final Object replLock = new Object();

  // WAIT sleeps on this; incoming ACKs wake it up
  private static final Object ackLock = new Object();

  // An empty RDB file (a valid snapshot with no keys), sent during PSYNC
  private static final byte[] EMPTY_RDB = HexFormat.of().parseHex(
      "524544495330303131fa0972656469732d76657205372e322e30fa0a72656469732d62697473c040"
      + "fa056374696d65c26d08bc65fa08757365642d6d656dc2b0c41000fa08616f662d62617365c000"
      + "fff06e3bfec0ff5aa2");

  /** One connected replica, as seen from the master. */
  public static class ReplicaLink {
    final OutputStream out;
    volatile long ackOffset = 0;     // last offset this replica confirmed

    ReplicaLink(OutputStream out) {
      this.out = out;
    }

    void send(byte[] data) {
      try {
        synchronized (out) {         // same lock Main uses when replying on this socket
          out.write(data);
          out.flush();
        }
      } catch (IOException e) {
        replicas.remove(this);       // replica went away
      }
    }
  }

  // ---------- Setup ----------

  /** Called from Main when started with  --replicaof "localhost 6379" */
  public static void setReplicaOf(String value) {
    String[] parts = value.trim().split("\\s+");   // "localhost 6379" -> ["localhost", "6379"]
    masterHost = parts[0];
    masterPort = Integer.parseInt(parts[1]);
    role = "slave";
  }

  public static boolean isReplica() {
    return role.equals("slave");
  }

  // ---------- Commands (called from Main.handleCommand) ----------

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "INFO":
        return info();
      case "REPLCONF":
        return "+OK\r\n";            // listening-port / capa during the handshake
      case "WAIT":
        return waitCommand(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // INFO [replication] -> one bulk string with "key:value" lines
  private static String info() {
    long offset;
    synchronized (replLock) {
      offset = replOffset;
    }
    String body = "role:" + role + "\r\n"
        + "master_replid:" + replId + "\r\n"
        + "master_repl_offset:" + offset;
    return Main.bulkString(body);
  }

  // ---------- MASTER: full resync ----------

  /** Answers PSYNC: +FULLRESYNC, then the RDB file. The connection becomes a replica link. */
  public static ReplicaLink startFullResync(OutputStream out) throws IOException {
    ReplicaLink link = new ReplicaLink(out);
    synchronized (replLock) {        // no propagation can slip in before the RDB
      synchronized (out) {
        out.write(("+FULLRESYNC " + replId + " 0\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("$" + EMPTY_RDB.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(EMPTY_RDB);        // note: no \r\n after the RDB bytes
        out.flush();
      }
      replicas.add(link);
    }
    return link;
  }

  public static void removeReplica(ReplicaLink link) {
    replicas.remove(link);
  }

  // ---------- MASTER: propagation ----------

  /** Sends a write command to every replica. Called by TxCommands after a successful write. */
  public static void propagate(List<String> command) {
    if (isReplica()) return;         // replicas never propagate
    byte[] data = encodeArray(command);
    synchronized (replLock) {
      replOffset += data.length;
      for (ReplicaLink link : replicas) {
        link.send(data);
      }
    }
  }

  // ---------- MASTER: WAIT ----------

  // WAIT numreplicas timeout
  //   -> how many replicas have processed every write sent so far.
  //   Returns early once numreplicas have confirmed, otherwise after timeout ms.
  private static String waitCommand(List<String> command) {
    if (command.size() < 3) {
      return "-ERR wrong number of arguments for 'wait' command\r\n";
    }
    int needed;
    long timeout;
    try {
      needed = Integer.parseInt(command.get(1));
      timeout = Long.parseLong(command.get(2));
    } catch (NumberFormatException e) {
      return "-ERR value is not an integer or out of range\r\n";
    }

    long target;
    synchronized (replLock) {
      target = replOffset;

      // Nothing written yet: every connected replica is trivially up to date
      if (target == 0) {
        return ":" + replicas.size() + "\r\n";
      }

      // Ask every replica to report its offset
      byte[] getAck = encodeArray(List.of("REPLCONF", "GETACK", "*"));
      for (ReplicaLink link : replicas) {
        link.send(getAck);
      }
      replOffset += getAck.length;   // GETACK counts too, but isn't part of the target
    }

    long deadline = System.currentTimeMillis() + timeout;
    synchronized (ackLock) {
      while (true) {
        int acked = countAcked(target);
        if (acked >= needed) {
          return ":" + acked + "\r\n";
        }
        long remaining = deadline - System.currentTimeMillis();
        if (timeout != 0 && remaining <= 0) {
          return ":" + acked + "\r\n";        // timed out: report how many made it
        }
        try {
          ackLock.wait(timeout == 0 ? 0 : remaining);   // 0 = wait until an ACK arrives
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return ":" + countAcked(target) + "\r\n";
        }
      }
    }
  }

  private static int countAcked(long target) {
    int count = 0;
    for (ReplicaLink link : replicas) {
      if (link.ackOffset >= target) count++;
    }
    return count;
  }

  /** Called from Main when a replica sends  REPLCONF ACK <offset> */
  public static void recordAck(ReplicaLink link, long offset) {
    synchronized (ackLock) {
      link.ackOffset = offset;
      ackLock.notifyAll();           // wake any WAIT that's counting
    }
  }

  // ---------- REPLICA side ----------

  /** Connects to the master in a background thread (the replica still serves its own clients). */
  public static void startReplica(int myPort) {
    Thread t = new Thread(() -> {
      try {
        runReplica(myPort);
      } catch (IOException | RuntimeException e) {
        System.out.println("Replication error: " + e.getMessage());
      }
    });
    t.setDaemon(true);
    t.start();
  }

  private static void runReplica(int myPort) throws IOException {
    try (Socket master = new Socket(masterHost, masterPort)) {
      OutputStream out = master.getOutputStream();
      Main.RespReader in = new Main.RespReader(master.getInputStream());

      // ----- Handshake -----
      send(out, List.of("PING"));
      in.readLine();                                                   // +PONG
      send(out, List.of("REPLCONF", "listening-port", String.valueOf(myPort)));
      in.readLine();                                                   // +OK
      send(out, List.of("REPLCONF", "capa", "psync2"));
      in.readLine();                                                   // +OK
      send(out, List.of("PSYNC", "?", "-1"));
      in.readLine();                                                   // +FULLRESYNC <id> 0
      in.readRdb();                                                    // the snapshot

      // ----- Apply commands from the master -----
      long processed = 0;            // bytes of commands handled so far (our offset)
      while (true) {
        long before = in.bytesRead();
        List<String> command = in.readCommand();
        if (command == null) break;  // master disconnected
        long size = in.bytesRead() - before;

        if (!command.isEmpty()) {
          boolean isGetAck = command.get(0).equalsIgnoreCase("REPLCONF")
              && command.size() >= 2
              && command.get(1).equalsIgnoreCase("GETACK");

          if (isGetAck) {
            // Report the offset BEFORE this GETACK
            send(out, List.of("REPLCONF", "ACK", String.valueOf(processed)));
          } else {
            Main.handleCommand(command);     // apply it, but never reply to the master
          }
        }
        processed += size;           // every command counts, including PING and GETACK
      }
    }
  }

  private static void send(OutputStream out, List<String> command) throws IOException {
    out.write(encodeArray(command));
    out.flush();
  }

  // ---------- RESP helper ----------

  // ["SET","foo","bar"] -> *3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
  static byte[] encodeArray(List<String> parts) {
    StringBuilder sb = new StringBuilder();
    sb.append("*").append(parts.size()).append("\r\n");
    for (String p : parts) {
      sb.append(Main.bulkString(p));
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }
}