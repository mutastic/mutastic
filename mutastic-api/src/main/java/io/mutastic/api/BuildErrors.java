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
    return failed(build, log, Optional.empty());
  }

  /// As [#failed(Build, List)], with what likely made it fail said after what went wrong: a build
  /// run with the tool on the PATH, for want of a wrapper, may be one made for another version. Not
  /// after a compiler's errors, which are the code's.
  public static String failed(Build build, List<String> log, Optional<String> likely) {
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
    var why = errors.isEmpty() ? likely.map(cause -> cause + "\n").orElse("") : "";
    return said + "\n" + why + seeing(build, here);
  }

  /// Where the build's output is, and the command that builds it as mutastic did.
  private static String seeing(Build build, Path here) {
    return "%s's output is in %s. To see it in full:\n  %s"
        .formatted(build.tool(), shown(build.log(), here), build.command());
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
  /// compiler gave them, each once. javac says what it can't find on a line of its own after the
  /// error, so that comes too: `cannot find symbol: class Test`.
  static List<String> compilerErrors(List<String> log, Path here) {
    var errors = new java.util.LinkedHashSet<String>();
    for (int at = 0; at < log.size(); at++) {
      var error = matching(log.get(at));
      if (error.isPresent()) {
        errors.add(
            "%s, line %s: %s%s"
                .formatted(
                    shown(error.get().group("file"), here),
                    error.get().group("line"),
                    error.get().group("message").strip(),
                    symbol(error.get().group("message"), log, at + 1)
                        .map(": "::concat)
                        .orElse("")));
      }
    }
    return List.copyOf(errors);
  }

  /// javac's `symbol: class Test` among the lines after a `cannot find symbol`, before the next
  /// error: `class Test`.
  private static Optional<String> symbol(String message, List<String> log, int from) {
    if (!message.strip().equals("cannot find symbol")) {
      return Optional.empty();
    }
    for (int at = from; at < Math.min(log.size(), from + SYMBOL_WITHIN); at++) {
      var line = log.get(at);
      if (matching(line).isPresent()) {
        return Optional.empty();
      }
      var found = SYMBOL.matcher(line);
      if (found.find()) {
        return Optional.of(found.group("symbol").strip());
      }
    }
    return Optional.empty();
  }

  /// ` symbol: class Test`, or Maven's `[ERROR] symbol: class Test`.
  private static final Pattern SYMBOL =
      Pattern.compile("^(?:\\[ERROR\\])?\\s+symbol:\\s+(?<symbol>\\S.*)$");

  /// How far after an error javac says its symbol: the line, its caret, then the symbol.
  private static final int SYMBOL_WITHIN = 4;

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

  /// The JDK a build's toolchain asks for, in the build's own words.
  ///
  /// @param version `21`, or a range such as Maven's `[17,)`
  /// @param vendor the vendor asked for, `Eclipse Temurin`: empty for any
  public record Toolchain(String version, Optional<String> vendor) {
    /// `JDK 21`, `JDK 21 (Eclipse Temurin)`, or for a range `a JDK of version [17,)`.
    public String named() {
      var jdk =
          version.matches("\\d+(?:\\.\\d+)*") ? "JDK " + version : "a JDK of version " + version;
      return jdk + vendor.map(name -> " (" + name + ")").orElse("");
    }
  }

  /// A build that stopped for want of the JDK its toolchain asks for: that, in a line; what to do;
  /// the run again with the argument that tells the tool where one is; and how to give it to every
  /// run. A run whose arguments came from `MUTASTIC_BUILD_ARGS` or `mutastic.yaml` loses them after
  /// `--`, which replaces them, so it's said to add it there.
  ///
  /// @param run the run, as [#run(String, String, BuildArguments, String)] has it
  /// @param arguments the build's arguments, which the run was given
  /// @param log the file the tool's output went to
  public static String noToolchain(
      Missing missing, String run, BuildArguments arguments, Path log) {
    var replaced = !arguments.words().isEmpty() && arguments.typed().isEmpty();
    var every =
        replaced
            ? "After --, it replaces the arguments in MUTASTIC_BUILD_ARGS or mutastic.yaml: add it"
                + " there instead."
            : "For every run, add it to build: args: in mutastic.yaml, or to MUTASTIC_BUILD_ARGS.";
    return ("the build needs %s for its toolchain, and %s found none%s.\n%s\n  %s\n"
            + "%s %s's output is in %s.")
        .formatted(
            missing.jdk().named(),
            missing.tool(),
            missing.where(),
            missing.remedy(),
            run,
            every,
            missing.tool(),
            shown(log, here()));
  }

  /// What a build tool found no JDK for, and what to do.
  ///
  /// @param tool `Gradle` or `Maven`
  /// @param jdk the JDK the toolchain asks for
  /// @param where where the tool looked, said after "found none": empty, or ` in
  ///   ~/.m2/toolchains.xml`
  /// @param remedy what to do, ending in a colon: `Install one, or tell Gradle where it is:`
  public record Missing(String tool, Toolchain jdk, String where, String remedy) {}

  /// `mutastic run app :core -- -Pci <argument>`: the run on the module, `typed` as the person
  /// would type its directory (empty where they are), with the arguments typed after `--` and then
  /// `argument`, which may have a `<…>` to fill in.
  public static String run(String typed, String module, BuildArguments arguments, String argument) {
    var words = new StringBuilder("mutastic run");
    if (!typed.isEmpty()) {
      words.append(' ').append(typed);
    }
    if (!module.equals(":")) {
      words.append(' ').append(module);
    }
    var ending = arguments.ending();
    return words.append(ending.isEmpty() ? " --" : ending).append(' ').append(argument).toString();
  }

  /// A build tool's command as the person would type it from `here`: the tool, `./gradlew` from
  /// where it is or the one on the PATH; the build's directory, after `flag` (`-p`, `-f`), unless
  /// it's `here`; then `rest`.
  public static String command(String tool, Path build, String flag, List<String> rest) {
    return command(List.of(tool), build, flag, rest);
  }

  /// As [#command(String, Path, String, List)], for a tool whose command has words before its
  /// program: `sh ./gradlew`, a wrapper that isn't executable.
  public static String command(List<String> tool, Path build, String flag, List<String> rest) {
    var here = RealPath.of(here());
    var words = new ArrayList<>(tool.subList(0, tool.size() - 1));
    var program = Path.of(tool.getLast());
    if (!program.isAbsolute()) {
      words.add(typed(tool.getLast()));
    } else {
      var parent = RealPath.of(program).getParent();
      words.add(typed(here.equals(parent) ? "./" + program.getFileName() : shown(program, here)));
    }
    if (!RealPath.of(build).equals(here)) {
      words.add(flag);
      words.add(typed(shown(build, here)));
    }
    words.addAll(rest);
    return String.join(" ", words);
  }

  /// A path as a command mutastic prints types it, so a shell reads it as one word: as it is when
  /// it's plain, `/work/app/gradlew`; else in quotes, `'/Users/me/My Projects/gradlew'`. A shell's
  /// single quotes run nothing in them. For cmd, on Windows or for a `.bat` or `.cmd` file, double
  /// quotes: `"C:\My Projects\gradlew.bat"`.
  public static String typed(String path) {
    boolean cmd = Wrappers.WINDOWS || CMD_FILE.matcher(path).find();
    if (path.matches(cmd ? PLAIN_FOR_CMD : PLAIN)) {
      return path;
    }
    return cmd ? '"' + path + '"' : "'" + path.replace("'", "'\\''") + "'";
  }

  /// A file cmd runs: `gradlew.bat`, `mvnw.cmd`.
  private static final Pattern CMD_FILE =
      Pattern.compile("\\.(?:bat|cmd)$", Pattern.CASE_INSENSITIVE);

  /// The characters a path can have and need no quotes.
  private static final String PLAIN = "[A-Za-z0-9_./:@%+=,-]+";

  /// For cmd, where a path's `\` and the `~` of a short name (`C:\Users\RUNNER~1`) are typed as
  /// they are.
  private static final String PLAIN_FOR_CMD = "[A-Za-z0-9_./:@%+=,~\\\\-]+";

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
    var absolute = RealPath.of(path);
    var from = RealPath.of(here);
    return absolute.startsWith(from) && !absolute.equals(from)
        ? from.relativize(absolute).toString()
        : absolute.toString();
  }
}
