import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * AOF (Append-Only File) Persistence extension.
 *
 * With --appendonly yes, every successful write command is appended to a file
 * in RESP format. When the server starts, it replays that file to rebuild its data.
 *
 * Files, inside <dir>/<appenddirname>/ :
 *   <appendfilename>.1.incr.aof    the commands, one RESP array after another
 *   <appendfilename>.manifest      lists the AOF files, e.g.
 *                                  "file appendonly.aof.1.incr.aof seq 1 type i\n"
 */
public class Aof {

  // ---------- Config (defaults match real Redis) ----------

  private static String appendOnly = "no";
  private static String appendDirName = "appendonlydir";
  private static String appendFilename = "appendonly.aof";
  private static String appendFsync = "everysec";

  public static void setAppendOnly(String value) { appendOnly = value; }
  public static void setAppendDirName(String value) { appendDirName = value; }
  public static void setAppendFilename(String value) { appendFilename = value; }
  public static void setAppendFsync(String value) { appendFsync = value; }

  /** Value for CONFIG GET, or null if the parameter isn't an AOF option. */
  public static String getConfig(String param) {
    switch (param) {
      case "appendonly":     return appendOnly;
      case "appenddirname":  return appendDirName;
      case "appendfilename": return appendFilename;
      case "appendfsync":    return appendFsync;
      default:               return null;
    }
  }

  private static boolean isEnabled() {
    return appendOnly.equalsIgnoreCase("yes");
  }

  // ---------- State ----------

  private static FileOutputStream aofOut = null;   // open for appending while the server runs
  private static final Object fileLock = new Object();

  // ---------- Start-up ----------

  /**
   * Called once from Main at start-up (after the RDB file is loaded):
   * creates the directory, the AOF file and the manifest, then replays old commands.
   */
  public static void init() {
    if (!isEnabled()) {
      return;                                      // --appendonly no: create nothing
    }

    try {
      // 1. The directory: <dir>/<appenddirname>/
      File aofDir = new File(Persistence.getDir(), appendDirName);
      aofDir.mkdirs();                             // does nothing if it already exists

      // 2. The AOF file: <appendfilename>.1.incr.aof
      String incrName = appendFilename + ".1.incr.aof";
      File incrFile = new File(aofDir, incrName);

      // 3. The manifest: lists the AOF file
      File manifest = new File(aofDir, appendFilename + ".manifest");
      if (!manifest.exists()) {
        String line = "file " + incrName + " seq 1 type i\n";
        Files.writeString(manifest.toPath(), line, StandardCharsets.UTF_8);
      }

      // 4. Replay commands from a previous run, if any
      if (incrFile.exists()) {
        replay(incrFile);
      }

      // 5. Open the file for appending (creates it empty if it doesn't exist)
      aofOut = new FileOutputStream(incrFile, true);

    } catch (IOException e) {
      System.out.println("AOF setup failed: " + e.getMessage());
    }
  }

  /** Runs every command saved in the AOF file, in order, to rebuild the data. */
  private static void replay(File file) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      Main.RespReader reader = new Main.RespReader(in);
      while (true) {
        List<String> command = reader.readCommand();
        if (command == null) break;                // end of file
        if (command.isEmpty()) continue;
        Main.handleCommand(command);               // apply it (this does NOT write to the AOF again)
      }
    }
  }

  // ---------- Writing ----------

  /** Appends a write command to the AOF file. Called by TxCommands after a successful write. */
  public static void append(List<String> command) {
    if (aofOut == null) return;                    // AOF is off
    byte[] data = Replication.encodeArray(command);   // same RESP format replicas receive
    synchronized (fileLock) {
      try {
        aofOut.write(data);
        aofOut.flush();
        if (appendFsync.equalsIgnoreCase("always")) {
          aofOut.getFD().sync();                   // force it onto the disk right now
        }
      } catch (IOException e) {
        System.out.println("AOF write failed: " + e.getMessage());
      }
    }
  }
}