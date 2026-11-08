package io.mutastic.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Tests that failed at a check of the gate, kept for the rest of its session, so that one taken
/// away later holds the gate: deleting a failing test would otherwise pass a task the test said was
/// wrong.
///
/// A test counts as still there when a test of the same name is, in any class, or a test whose body
/// is the same as the failing one's was: a move keeps the name, and a rename keeps the body. One
/// changed in both at once can't be told from a test taken away and another written, so the gate
/// holds it, and says which; an agent that means it can stop, and the gate hands over.
final class RedTests {
  /// A method's declaration, in Java or Kotlin, by its name.
  private static final Pattern DECLARATION =
      Pattern.compile("\\b(?:void|fun)\\s++(?<name>`[^`]++`|\\w++)\\s*+\\(");

  /// A class's declaration, in Java or Kotlin, by its simple name.
  private static final Pattern CLASS = Pattern.compile("\\b(?:class|object)\\s++(?<name>\\w++)");

  private RedTests() {}

  /// The tests in a set of test sources: their names, the classes they're in, and a fingerprint of
  /// each one's body.
  record Found(Set<String> names, Set<String> classes, Set<String> bodies) {
    static Found in(List<Path> testSources) {
      var found = new Found(new HashSet<>(), new HashSet<>(), new HashSet<>());
      for (var file : sourceFiles(testSources)) {
        found.add(read(file));
      }
      return found;
    }

    /// The tests in one source file, `TEST` annotated.
    void add(String source) {
      CLASS.matcher(source).results().forEach(found -> classes.add(found.group("name")));
      tests(source)
          .forEach(
              (name, body) -> {
                names.add(name);
                bodies.add(body);
              });
    }

    /// Whether `test`, as JUnit names it, is still here: by its name, or by its body as it was.
    boolean has(String test, String body) {
      var name = nameOf(test);
      boolean named = test.contains("#") ? names.contains(name) : classes.contains(name);
      return named || (!body.isEmpty() && bodies.contains(body));
    }
  }

  /// The tests in one source file, `TEST` annotated: each name, to its body's fingerprint.
  static Map<String, String> tests(String source) {
    var tests = new TreeMap<String, String>();
    var annotated = Gate.TEST.matcher(source);
    var declared = DECLARATION.matcher(source);
    while (annotated.find()) {
      if (declared.find(annotated.end())) {
        tests.putIfAbsent(
            declared.group("name").replace("`", ""), fingerprint(body(source, declared.end())));
      }
    }
    return tests;
  }

  /// The failing tests to remember, each with its body's fingerprint now, from its class's source.
  /// A failure of an engine as a whole (`[engine:…]`) names no test, so there's nothing of it to
  /// take away.
  static Map<String, String> remember(List<String> failing, List<Path> testSources) {
    var red = new TreeMap<String, String>();
    for (var test : failing) {
      if (!test.startsWith("[")) {
        var id = idOf(test);
        red.put(id, bodyOf(id, testSources));
      }
    }
    return red;
  }

  /// The fingerprint of a test's body, from the file its outermost class is in: empty for a class
  /// as a whole, or a test not found.
  private static String bodyOf(String test, List<Path> testSources) {
    if (!test.contains("#")) {
      return "";
    }
    var type = test.substring(0, test.indexOf('#')).replaceFirst("\\$.*", "");
    for (var root : testSources) {
      for (var extension : List.of(".java", ".kt")) {
        var file = root.resolve(type.replace('.', '/') + extension);
        if (Files.isRegularFile(file)) {
          return tests(read(file)).getOrDefault(nameOf(test), "");
        }
      }
    }
    return "";
  }

  /// The remembered tests no longer there, by name or by body.
  static List<String> gone(Map<String, String> red, Found found) {
    return red.entrySet().stream()
        .filter(test -> !found.has(test.getKey(), test.getValue()))
        .map(Map.Entry::getKey)
        .toList();
  }

  /// A test as the gate remembers it: its class and method, without a parameterised test's
  /// arguments or invocation, `a.BTest#name`.
  static String idOf(String test) {
    int hash = test.indexOf('#');
    return hash < 0 ? test : test.substring(0, hash) + "#" + nameOf(test);
  }

  /// A test's method name, or a class's simple name for a class as a whole.
  private static String nameOf(String test) {
    int hash = test.indexOf('#');
    if (hash < 0) {
      return test.substring(Math.max(test.lastIndexOf('.'), test.lastIndexOf('$')) + 1);
    }
    return test.substring(hash + 1).replaceFirst("[(\\[].*", "");
  }

  /// The body of a method whose declaration ends at `from`: its braces and what's between them,
  /// strings skipped, so a brace in one doesn't end it.
  static String body(String source, int from) {
    int open = source.indexOf('{', from);
    if (open < 0) {
      return "";
    }
    int depth = 0;
    boolean quoted = false;
    for (int at = open; at < source.length(); at++) {
      char ch = source.charAt(at);
      if (quoted) {
        quoted = ch != '"' || source.charAt(at - 1) == '\\';
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == '{' || ch == '}') {
        depth += ch == '{' ? 1 : -1;
        if (depth == 0) {
          return source.substring(open, at + 1);
        }
      }
    }
    return source.substring(open);
  }

  /// A body, its layout left out, as a short hash: the same test reformatted is the same test.
  static String fingerprint(String body) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest(body.replaceAll("\\s++", "").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK has SHA-256", e);
    }
  }

  private static List<Path> sourceFiles(List<Path> testSources) {
    var files = new java.util.ArrayList<Path>();
    for (var root : testSources.stream().filter(Files::isDirectory).toList()) {
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(RedTests::isSource).forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files;
  }

  private static boolean isSource(Path file) {
    var name = file.toString();
    return name.endsWith(".java") || name.endsWith(".kt");
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
