package io.mutastic.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Lines of a source file that `--at` asks for, as typed:
/// `src/main/java/com/example/Pricing.java:17`, or `:17-20` for lines 17 to 20, 1-based and
/// inclusive. The lines are split from the right, so a Windows path's drive,
/// `C:\work\Pricing.java:17`, stays in the path. Which file the path names is found once the
/// project is read ([AtFiles]).
///
/// @param path the file as typed: absolute, or from where mutastic runs or from the project
record At(String path, int from, int to) {
  /// `:17` or `:17-20` at the end of what's typed.
  private static final Pattern LINES = Pattern.compile(":(?<from>\\d+)(?:-(?<to>\\d+))?$");

  /// A column after the line, `Pricing.java:17:15`, as `mutants` prints a place.
  private static final Pattern COLUMN = Pattern.compile(".+:(?<line>\\d+)$");

  /// The most a line's number can be: no source file is longer.
  private static final int MOST = 10_000_000;

  /// What `--at` takes, for its errors.
  static final String TAKES =
      "--at takes a source file and a line, as --at=src/main/java/com/example/Pricing.java:17, or"
          + " :17-20 for lines 17 to 20";

  /// Each of `--at=PATH:LINE[-LINE][,…]`'s files and lines.
  static List<At> parse(String given) {
    if (given.isBlank()) {
      throw refused("--at needs a value. " + TAKES);
    }
    var all = new ArrayList<At>();
    for (String each : given.split(",", -1)) {
      if (each.isBlank()) {
        throw refused("--at=%s has an empty one between its commas. %s".formatted(given, TAKES));
      }
      all.add(one(each.strip()));
    }
    return List.copyOf(all);
  }

  /// One file and its lines.
  private static At one(String typed) {
    var lines = LINES.matcher(typed);
    if (!lines.find() || lines.start() == 0) {
      throw refused("--at=%s has no line. %s".formatted(typed, TAKES));
    }
    var path = typed.substring(0, lines.start());
    var column = COLUMN.matcher(path);
    if (column.matches()) {
      throw refused(
          "--at takes a line, not a column: --at=%s:%s"
              .formatted(path.substring(0, path.lastIndexOf(':')), column.group("line")));
    }
    int from = line(typed, lines.group("from"));
    var to = lines.group("to");
    int last = to == null ? from : line(typed, to);
    if (last < from) {
      throw refused(
          "--at=%s ends before it starts: --at=%s:%d-%d".formatted(typed, path, last, from));
    }
    return new At(path, from, last);
  }

  /// A line's number, from 1.
  private static int line(String typed, String number) {
    int line = number.length() > String.valueOf(MOST).length() ? 0 : Integer.parseInt(number);
    if (line < 1 || line > MOST) {
      throw refused(
          "--at=%s: a file's lines are numbered from 1, as an editor shows them".formatted(typed));
    }
    return line;
  }

  private static Main.UsageException refused(String message) {
    return new Main.UsageException(Failure.USAGE, message);
  }

  /// `Pricing.java:17`, or `Pricing.java:17-20`: as `--at` takes it, with `file` for the path.
  String typed(String file) {
    return file + ":" + lines();
  }

  /// `17`, or `17-20`.
  String lines() {
    return from == to ? String.valueOf(from) : from + "-" + to;
  }
}
