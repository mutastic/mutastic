package io.mutastic.report;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A file as the person would type it from where mutastic ran: relative when it's below there, as
/// `testng/build/mutastic/mutation.html` from `samples`, else whole. Paths relative to the project
/// read wrong from anywhere else, and a terminal guessing a link from one opened `/build/…`.
public final class Here {
  private Here() {}

  /// `file` as typed from the directory mutastic runs in.
  public static String shown(Path file) {
    return shown(Path.of("").toAbsolutePath().normalize(), file);
  }

  /// `file` as typed from `here`, through a link such as macOS's `/var` as well.
  static String shown(Path here, Path file) {
    var absolute = file.toAbsolutePath().normalize();
    var real = real(absolute);
    var realHere = real(here);
    if (absolute.startsWith(here) && !absolute.equals(here)) {
      return slashed(here.relativize(absolute));
    }
    if (real.startsWith(realHere) && !real.equals(realHere)) {
      return slashed(realHere.relativize(real));
    }
    return absolute.toString();
  }

  /// A message's logs, `Its output is in /…/build/mutastic/worker.log.`, each as typed from where
  /// mutastic ran and, with links on, linked to the file itself. A relative one is linked only when
  /// it's there from here.
  public static String logs(String said, Style style) {
    return LOG.matcher(said).replaceAll(found -> Matcher.quoteReplacement(log(found, style)));
  }

  private static String log(MatchResult found, Style style) {
    var named = found.group("log");
    Path file;
    try {
      file = Path.of(named);
    } catch (InvalidPathException e) {
      return found.group();
    }
    var whole = file.toAbsolutePath().normalize();
    if (!file.isAbsolute() && !java.nio.file.Files.isRegularFile(whole)) {
      return found.group(); // not there from here: as said
    }
    return found.group("lead") + style.link(whole, shown(whole));
  }

  /// `output is in <file>`, as each message naming a log says it, up to a sentence's full stop.
  private static final Pattern LOG =
      Pattern.compile("(?<lead>output is in )(?<log>\\S+?)(?=\\.?(?:\\s|$))", Pattern.MULTILINE);

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (java.io.IOException e) {
      return path; // not there: as given
    }
  }

  private static String slashed(Path relative) {
    return relative.toString().replace(java.io.File.separatorChar, '/');
  }
}
