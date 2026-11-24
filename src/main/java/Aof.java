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

      // 2. The manifest says which AOF file to use. If there is one, trust it:
      //    its file name doesn't have to match <appendfilename>.
      File manifest = new File(aofDir, appendFilename + ".manifest");
      String incrName = null;
      if (manifest.exists()) {
        incrName = incrFileFromManifest(manifest);
      }

      // 3. No manifest (or no file listed in it): use the default name and write a manifest
      if (incrName == null) {
        incrName = appendFilename + ".1.incr.aof";
        String line = "file " + incrName + " seq 1 type i\n";
        Files.writeString(manifest.toPath(), line, StandardCharsets.UTF_8);
      }
      File incrFile = new File(aofDir, incrName);

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

  /**
   * Reads a manifest like
   *   file strawberry.aof.1.incr.aof seq 1 type i
   * and returns the name of the incremental ("type i") file, or null if none is listed.
   */
  private static String incrFileFromManifest(File manifest) throws IOException {
    String found = null;
    for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
      String[] tokens = line.trim().split("\\s+");
      String name = null;
      String type = null;
      // Tokens come in pairs: "file <name>", "seq <n>", "type <t>"
      for (int i = 0; i + 1 < tokens.length; i += 2) {
        if (tokens[i].equals("file")) name = tokens[i + 1];
        if (tokens[i].equals("type")) type = tokens[i + 1];
      }
      if (name != null && "i".equals(type)) {
        found = name;                              // if there are several, the last one is the newest
      }
    }
    return found;
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