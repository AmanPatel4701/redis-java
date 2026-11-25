import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Geospatial extension: GEOADD, GEOPOS, GEODIST, GEOSEARCH.
 *
 * Locations live INSIDE a sorted set (that's why TYPE says "zset"):
 *   member = the place name,  score = its longitude/latitude packed into one number.
 *
 * Packing ("geohash" score):
 *   1. Turn latitude and longitude into 26-bit whole numbers, by where they fall
 *      inside their allowed range.
 *   2. Interleave the bits: latitude bits go to even positions, longitude bits
 *      to odd positions, giving one 52-bit number. Nearby places get close scores.
 */
public class GeoCommands {

  // Allowed ranges (the latitude limits are the ones Redis uses)
  private static final double MIN_LATITUDE = -85.05112878;
  private static final double MAX_LATITUDE = 85.05112878;
  private static final double MIN_LONGITUDE = -180;
  private static final double MAX_LONGITUDE = 180;

  private static final double LATITUDE_RANGE = MAX_LATITUDE - MIN_LATITUDE;
  private static final double LONGITUDE_RANGE = MAX_LONGITUDE - MIN_LONGITUDE;

  private static final double STEPS = 1 << 26;          // 2^26 cells in each direction

  // The Earth radius Redis uses for distances, in meters
  private static final double EARTH_RADIUS = 6372797.560856;

  // ---------- Entry point ----------

  public static String handle(List<String> command) {
    String name = command.get(0).toUpperCase();
    switch (name) {
      case "GEOADD":
        return geoadd(command);
      case "GEOPOS":
        return geopos(command);
      case "GEODIST":
        return geodist(command);
      case "GEOSEARCH":
        return geosearch(command);
      default:
        return "-ERR unknown command '" + command.get(0) + "'\r\n";
    }
  }

  // ---------- GEOADD ----------

  // GEOADD key longitude latitude member [longitude latitude member ...]
  //   -> number of NEW members added
  private static String geoadd(List<String> command) {
    if (command.size() < 5 || (command.size() - 2) % 3 != 0) {
      return wrongArgs("geoadd");
    }
    String key = command.get(1);

    // Check every location before storing any of them
    List<double[]> points = new ArrayList<>();
    for (int i = 2; i < command.size(); i += 3) {
      double lon, lat;
      try {
        lon = Double.parseDouble(command.get(i));
        lat = Double.parseDouble(command.get(i + 1));
      } catch (NumberFormatException e) {
        return "-ERR value is not a valid float\r\n";
      }
      if (lon < MIN_LONGITUDE || lon > MAX_LONGITUDE
          || lat < MIN_LATITUDE || lat > MAX_LATITUDE) {
        return "-ERR invalid longitude,latitude pair "
            + String.format("%.6f,%.6f", lon, lat) + "\r\n";
      }
      points.add(new double[] {lon, lat});
    }

    int added = 0;
    for (int i = 2, p = 0; i < command.size(); i += 3, p++) {
      double[] point = points.get(p);
      double score = encode(point[0], point[1]);
      if (ZSetCommands.addMember(key, score, command.get(i + 2))) {
        added++;
      }
    }
    return ":" + added + "\r\n";
  }

  // ---------- GEOPOS ----------

  // GEOPOS key member [member ...]
  //   -> for each member: [longitude, latitude], or a null array if it doesn't exist
  private static String geopos(List<String> command) {
    if (command.size() < 3) {
      return wrongArgs("geopos");
    }
    String key = command.get(1);

    StringBuilder sb = new StringBuilder();
    sb.append("*").append(command.size() - 2).append("\r\n");
    for (int i = 2; i < command.size(); i++) {
      Double score = ZSetCommands.scoreOf(key, command.get(i));
      if (score == null) {
        sb.append("*-1\r\n");
      } else {
        double[] lonLat = decode(score);
        sb.append("*2\r\n")
          .append(Main.bulkString(String.valueOf(lonLat[0])))
          .append(Main.bulkString(String.valueOf(lonLat[1])));
      }
    }
    return sb.toString();
  }

  // ---------- GEODIST ----------

  // GEODIST key member1 member2 [m|km|mi|ft]
  //   -> distance as a bulk string with 4 decimals, or null if a member is missing
  private static String geodist(List<String> command) {
    if (command.size() < 4) {
      return wrongArgs("geodist");
    }
    String key = command.get(1);
    Double score1 = ZSetCommands.scoreOf(key, command.get(2));
    Double score2 = ZSetCommands.scoreOf(key, command.get(3));
    if (score1 == null || score2 == null) {
      return Main.bulkString(null);
    }

    double unit = 1;
    if (command.size() >= 5) {
      unit = unitToMeters(command.get(4));
      if (unit < 0) {
        return "-ERR unsupported unit provided. please use M, KM, FT, MI\r\n";
      }
    }

    double[] a = decode(score1);
    double[] b = decode(score2);
    double meters = distance(a[0], a[1], b[0], b[1]);
    return Main.bulkString(String.format("%.4f", meters / unit));
  }

  // ---------- GEOSEARCH ----------

  // GEOSEARCH key FROMLONLAT longitude latitude BYRADIUS radius m|km|mi|ft
  //   -> names of every member within that distance of the point
  private static String geosearch(List<String> command) {
    String key = command.get(1);
    Double centerLon = null;
    Double centerLat = null;
    Double radiusMeters = null;

    try {
      for (int i = 2; i < command.size(); i++) {
        String arg = command.get(i).toUpperCase();
        if (arg.equals("FROMLONLAT") && i + 2 < command.size()) {
          centerLon = Double.parseDouble(command.get(i + 1));
          centerLat = Double.parseDouble(command.get(i + 2));
          i += 2;
        } else if (arg.equals("FROMMEMBER") && i + 1 < command.size()) {
          Double score = ZSetCommands.scoreOf(key, command.get(i + 1));
          if (score == null) {
            return "-ERR could not decode requested zset member\r\n";
          }
          double[] lonLat = decode(score);
          centerLon = lonLat[0];
          centerLat = lonLat[1];
          i += 1;
        } else if (arg.equals("BYRADIUS") && i + 2 < command.size()) {
          double unit = unitToMeters(command.get(i + 2));
          if (unit < 0) {
            return "-ERR unsupported unit provided. please use M, KM, FT, MI\r\n";
          }
          radiusMeters = Double.parseDouble(command.get(i + 1)) * unit;
          i += 2;
        }
      }
    } catch (NumberFormatException e) {
      return "-ERR value is not a valid float\r\n";
    }

    if (centerLon == null || centerLat == null || radiusMeters == null) {
      return "-ERR syntax error\r\n";
    }

    List<String> found = new ArrayList<>();
    for (Map.Entry<String, Double> e : ZSetCommands.allMembers(key).entrySet()) {
      double[] lonLat = decode(e.getValue());
      if (distance(centerLon, centerLat, lonLat[0], lonLat[1]) <= radiusMeters) {
        found.add(e.getKey());
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("*").append(found.size()).append("\r\n");
    for (String member : found) {
      sb.append(Main.bulkString(member));
    }
    return sb.toString();
  }

  // ---------- Encoding: (longitude, latitude) <-> score ----------

  /** Packs a location into one number (the sorted set score). */
  static double encode(double longitude, double latitude) {
    // Position inside the range, as a whole number from 0 to 2^26 - 1
    long latInt = (long) ((latitude - MIN_LATITUDE) / LATITUDE_RANGE * STEPS);
    long lonInt = (long) ((longitude - MIN_LONGITUDE) / LONGITUDE_RANGE * STEPS);
    latInt = Math.min(latInt, (long) STEPS - 1);
    lonInt = Math.min(lonInt, (long) STEPS - 1);

    // Interleave: latitude bits at even positions, longitude bits at odd positions
    return (double) (spread(latInt) | (spread(lonInt) << 1));
  }

  /** Unpacks a score back into {longitude, latitude}: the centre of its grid cell. */
  static double[] decode(double score) {
    long bits = (long) score;
    long latInt = compact(bits);          // even bits
    long lonInt = compact(bits >> 1);     // odd bits

    double latMin = MIN_LATITUDE + LATITUDE_RANGE * (latInt / STEPS);
    double latMax = MIN_LATITUDE + LATITUDE_RANGE * ((latInt + 1) / STEPS);
    double lonMin = MIN_LONGITUDE + LONGITUDE_RANGE * (lonInt / STEPS);
    double lonMax = MIN_LONGITUDE + LONGITUDE_RANGE * ((lonInt + 1) / STEPS);

    return new double[] {(lonMin + lonMax) / 2, (latMin + latMax) / 2};
  }

  /** Spreads a 32-bit number out so there's an empty bit between every bit: abc -> 0a0b0c */
  private static long spread(long v) {
    v &= 0xFFFFFFFFL;
    v = (v | (v << 16)) & 0x0000FFFF0000FFFFL;
    v = (v | (v << 8))  & 0x00FF00FF00FF00FFL;
    v = (v | (v << 4))  & 0x0F0F0F0F0F0F0F0FL;
    v = (v | (v << 2))  & 0x3333333333333333L;
    v = (v | (v << 1))  & 0x5555555555555555L;
    return v;
  }

  /** The opposite of spread: keeps every other bit and squeezes them together. */
  private static long compact(long v) {
    v &= 0x5555555555555555L;
    v = (v | (v >> 1))  & 0x3333333333333333L;
    v = (v | (v >> 2))  & 0x0F0F0F0F0F0F0F0FL;
    v = (v | (v >> 4))  & 0x00FF00FF00FF00FFL;
    v = (v | (v >> 8))  & 0x0000FFFF0000FFFFL;
    v = (v | (v >> 16)) & 0x00000000FFFFFFFFL;
    return v;
  }

  // ---------- Distance ----------

  /** Haversine formula: distance in meters between two points on a sphere. */
  static double distance(double lon1, double lat1, double lon2, double lat2) {
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double dPhi = Math.toRadians(lat2 - lat1);
    double dLambda = Math.toRadians(lon2 - lon1);

    double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
    return 2 * EARTH_RADIUS * Math.asin(Math.sqrt(a));
  }

  /** Meters per unit, or -1 for an unknown unit. */
  private static double unitToMeters(String unit) {
    switch (unit.toLowerCase()) {
      case "m":  return 1;
      case "km": return 1000;
      case "mi": return 1609.34;
      case "ft": return 0.3048;
      default:   return -1;
    }
  }

  private static String wrongArgs(String cmd) {
    return "-ERR wrong number of arguments for '" + cmd + "' command\r\n";
  }
}