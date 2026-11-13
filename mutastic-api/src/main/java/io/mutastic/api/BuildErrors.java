package io.mutastic.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// What a build tool said when the project didn't build, before any mutant was in it: the
/// compiler's first errors, each at the person's own file and line, or else what the tool says went
/// wrong, without the advice it gives about itself (`--stacktrace`, `--scan`, `-e`, its wiki).
/// Gradle and Maven say it in the same words, and end in the command that builds it as mutastic
/// did.
@Internal
public final class BuildErrors {
  /// The most errors a message quotes; the log has the rest.
  public static final int MOST = 3;

  private BuildErrors() {}

  /// javac: `/abs/Pricing.java:18: error: ';' expected`, then the line and a caret.
  private static final Pattern JAVAC =
      Pattern.compile("^(?<file>\\S.*?\\.java):(?<line>\\d+): error: (?<message>.*)$");

  /// Kotlin: `e: file:///abs/Pricing.kt:18:5 Unresolved reference 'x'.`
  private static final Pattern KOTLIN =
      Pattern.compile(
          "^e: (?:file://)?(?<file>\\S.*?\\.kts?):(?<line>\\d+):(?<column>\\d+):? (?<message>.*)$");

  /// Maven's compiler plugin: `[ERROR] /abs/Pricing.java:[18,24] ';' expected`.
  private static final Pattern MAVEN =
      Pattern.compile(
          "^\\[ERROR\\] (?<file>\\S.*?\\.(?:java|kt)):\\[(?<line>\\d+),(?<column>\\d+)\\]"
              + " (?<message>.*)$");

  /// The message: the project doesn't build; its first errors, or what the tool says went wrong;
  /// where its output is; and the command that builds it as mutastic did. In the Rust edition's
  /// words.
  ///
  /// @param log what the build said, a line at a time
  public static String failed(Build build, List<String> log) {
    var here = here();
    var tool = build.tool();
    var errors = compilerErrors(log, here);
    var headline = build.project() + " doesn't build, before any mutant is in it.";
    String said;
    if (errors.size() == 1) {
      said = headline + "\nThe error is in " + errors.getFirst() + ".";
    } else if (!errors.isEmpty()) {
      var first = errors.subList(0, Math.min(MOST, errors.size()));
      said =
          "%s Its first %d errors, of %d:\n  %s"
              .formatted(headline, first.size(), errors.size(), String.join("\n  ", first));
    } else {
      said =
          whatWentWrong(log)
              .map(lines -> "%s %s says:\n%s".formatted(headline, tool, lines))
              .orElse(headline);
    }
    return "%s\n%s's output is in %s. To see it in full:\n  %s"
        .formatted(said, tool, shown(build.log(), here), build.command());
  }

  /// A build that failed.
  ///
  /// @param tool `Gradle` or `Maven`
  /// @param project the project as runs name it, `pricing` or `modules/core`
  /// @param log the file its output went to
  /// @param command the command that builds what mutastic built, as the person would type it
  public record Build(String tool, String project, Path log, String command) {}

  /// Where the person is, that paths and commands are shown from.
  private static Path here() {
    return Path.of("").toAbsolutePath().normalize();
  }

  /// Each compiler error, `src/main/java/Pricing.java, line 18: ';' expected`, in the order the
  /// compiler gave them, each once.
  static List<String> compilerErrors(List<String> log, Path here) {
    var errors = new java.util.LinkedHashSet<String>();
    for (String line : log) {
      matching(line)
          .ifPresent(
              error ->
                  errors.add(
                      "%s, line %s: %s"
                          .formatted(
                              shown(error.group("file"), here),
                              error.group("line"),
                              error.group("message").strip())));
    }
    return List.copyOf(errors);
  }

  private static Optional<Matcher> matching(String line) {
    for (Pattern pattern : List.of(JAVAC, KOTLIN, MAVEN)) {
      var matcher = pattern.matcher(line.strip());
      if (matcher.matches()) {
        return Optional.of(matcher);
      }
    }
    return Optional.empty();
  }

  /// What the tool says went wrong, indented: Gradle's `* What went wrong:` section, or Maven's
  /// `[ERROR]` lines, without the advice that follows them; else the end of what it said, as when
  /// its wrapper failed before the build began.
  static Optional<String> whatWentWrong(List<String> log) {
    int start = log.indexOf("* What went wrong:");
    var errors = log.stream().filter(line -> line.startsWith("[ERROR] ")).toList();
    List<String> lines;
    if (start >= 0) {
      lines =
          log.subList(start + 1, log.size()).stream()
              .takeWhile(line -> !line.startsWith("* Try:") && !line.startsWith("* Exception is:"))
              .toList();
    } else if (!errors.isEmpty()) {
      lines =
          errors.stream()
              .map(line -> line.substring("[ERROR] ".length()))
              .takeWhile(line -> !MAVEN_ADVICE.matcher(line).find())
              .toList();
    } else {
      var said = log.stream().filter(line -> !line.isBlank()).toList();
      lines = said.subList(Math.max(0, said.size() - LINES), said.size());
    }
    var kept = lines.stream().filter(line -> !line.isBlank()).limit(LINES).map(line -> "  " + line);
    var said = String.join("\n", kept.toList());
    return said.isEmpty() ? Optional.empty() : Optional.of(said);
  }

  /// The most lines of what went wrong a message quotes.
  private static final int LINES = 8;

  /// Where Maven's advice about itself starts: after the error, before `[Help 1]`.
  private static final Pattern MAVEN_ADVICE =
      Pattern.compile(
          "^(?:To see the full stack trace|Re-run Maven using|For more information about the"
              + " errors|-> \\[Help)");

  /// A build tool's command as the person would type it from `here`: the tool, `./gradlew` from
  /// where it is or the one on the PATH; the build's directory, after `flag` (`-p`, `-f`), unless
  /// it's `here`; then `rest`.
  public static String command(String tool, Path build, String flag, List<String> rest) {
    var here = real(here());
    var words = new ArrayList<String>();
    var program = Path.of(tool);
    if (!program.isAbsolute()) {
      words.add(tool);
    } else {
      var parent = real(program.toAbsolutePath().normalize()).getParent();
      words.add(here.equals(parent) ? "./" + program.getFileName() : shown(program, here));
    }
    if (!real(build.toAbsolutePath().normalize()).equals(here)) {
      words.add(flag);
      words.add(shown(build, here));
    }
    words.addAll(rest);
    return String.join(" ", words);
  }

  /// A file as a compiler named it, from `here`: a URI's path on Windows, `/C:/a`, is `C:/a`; a
  /// name that isn't a path here is shown as it was given.
  private static String shown(String file, Path here) {
    var named = file.matches("/[A-Za-z]:.*") ? file.substring(1) : file;
    try {
      return shown(Path.of(named), here);
    } catch (java.nio.file.InvalidPathException e) {
      return named;
    }
  }

  /// `path` from `here`, when it's inside it; else as it is.
  private static String shown(Path path, Path here) {
    var absolute = real(path.toAbsolutePath().normalize());
    var from = real(here.toAbsolutePath().normalize());
    return absolute.startsWith(from) && !absolute.equals(from)
        ? from.relativize(absolute).toString()
        : absolute.toString();
  }

  /// `path` with its links resolved, so `/var` and `/private/var` compare equal; as it is when it
  /// isn't there.
  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (java.io.IOException e) {
      return path;
    }
  }
}
