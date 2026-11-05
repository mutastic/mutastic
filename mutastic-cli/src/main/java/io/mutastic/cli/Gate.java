package io.mutastic.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// `mutastic gate`: a coding agent's task, held to what mutastic says of the lines it changed.
///
/// `gate open` keeps where the task starts: the commit, the ignores, and how many tests run and how
/// many are disabled. `gate check` asks whether it's done: the mutants on the lines changed since
/// are each caught or proposed for a person to ignore, no ignore was added, and no test was taken
/// away or disabled. If not, it says what's left, as the agent format does, so an agent can carry
/// on; after `--tries` of those, it lets the agent stop and says what's still missed, rather than
/// loop. `gate close` forgets the task.
///
/// With `--hook=claude`, each reads a Claude Code hook's input on stdin and answers as a hook does:
/// `open` once per session, `check` with a block and its reason, or a message for the person.
final class Gate {
  private static final String FILE = "gate.properties";

  /// A test, and a test turned off, as JUnit and TestNG write them.
  private static final Pattern TEST =
      Pattern.compile(
          "@(?:[\\w.]+\\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

  /// By its simple name or its full one: `@org.junit.jupiter.api.Disabled` turns a test off too.
  private static final Pattern DISABLED = Pattern.compile("@(?:[\\w.]+\\.)?(Disabled|Ignore)\\b");

  private static final Pattern SESSION =
      Pattern.compile("\"session_id\"\\s*:\\s*\"(?<session>[^\"]*)\"");

  private Gate() {}

  /// Where a task started, and how often the gate has sent the agent back since it last passed.
  ///
  /// @param session the agent's session that opened it, when a hook did: empty otherwise
  record State(String session, String base, Set<String> ignored, Shape shape, int blocks) {
    State {
      ignored = Set.copyOf(ignored);
    }

    State blockedOnce() {
      return new State(session, base, ignored, shape, blocks + 1);
    }

    State passed() {
      return new State(session, base, ignored, shape, 0);
    }

    static Optional<State> read(Path work) {
      var file = work.resolve(FILE);
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      var properties = new Properties();
      try (var in = Files.newBufferedReader(file)) {
        properties.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      var ignored = properties.getProperty("ignored", "");
      return Optional.of(
          new State(
              properties.getProperty("session", ""),
              properties.getProperty("base", ""),
              ignored.isEmpty() ? Set.of() : Set.of(ignored.split(",")),
              new Shape(
                  Integer.parseInt(properties.getProperty("tests", "0")),
                  Integer.parseInt(properties.getProperty("disabled", "0"))),
              Integer.parseInt(properties.getProperty("blocks", "0"))));
    }

    void write(Path work) {
      var properties = new Properties();
      properties.setProperty("session", session);
      properties.setProperty("base", base);
      properties.setProperty("ignored", String.join(",", new TreeSet<>(ignored)));
      properties.setProperty("tests", String.valueOf(shape.tests()));
      properties.setProperty("disabled", String.valueOf(shape.disabled()));
      properties.setProperty("blocks", String.valueOf(blocks));
      try {
        Files.createDirectories(work);
        try (var out = Files.newBufferedWriter(work.resolve(FILE))) {
          properties.store(out, "mutastic gate: where this task started");
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    static boolean close(Path work) {
      try {
        return Files.deleteIfExists(work.resolve(FILE));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /// How many tests the test sources have, and how many of them are disabled.
  record Shape(int tests, int disabled) {
    static Shape of(List<Path> testSources) {
      int tests = 0;
      int disabled = 0;
      for (Path root : testSources) {
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(root)) {
          for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
            var text = Files.readString(file);
            tests += (int) TEST.matcher(text).results().count();
            disabled += (int) DISABLED.matcher(text).results().count();
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return new Shape(tests, disabled);
    }

    /// Whether this has fewer tests running than `before`: fewer, or more of them disabled.
    boolean fewerThan(Shape before) {
      return tests < before.tests || disabled > before.disabled;
    }
  }

  /// Whether the task is done, what to say, and whether that's to the agent (`blocks`) or past it,
  /// to the person.
  record Verdict(boolean passes, boolean gaveUp, String text) {
    static Verdict pass(String text) {
      return new Verdict(true, false, text);
    }

    static Verdict block(String text) {
      return new Verdict(false, false, text);
    }
  }

  /// The session a Claude Code hook's input names, or empty.
  static String session(String hookInput) {
    var found = SESSION.matcher(hookInput);
    return found.find() ? found.group("session") : "";
  }

  /// What a Claude Code hook answers: a block, whose reason is the agent's next instruction, or a
  /// message for the person.
  static String claudeHook(Verdict verdict) {
    return verdict.passes()
        ? "{\"systemMessage\": " + quoted(verdict.text()) + "}"
        : "{\"decision\": \"block\", \"reason\": " + quoted(verdict.text()) + "}";
  }

  /// A JSON string.
  static String quoted(String text) {
    var out = new StringBuilder("\"");
    for (int at = 0; at < text.length(); at++) {
      char ch = text.charAt(at);
      switch (ch) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (ch < 0x20) {
            out.append("\\u%04x".formatted((int) ch));
          } else {
            out.append(ch);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
