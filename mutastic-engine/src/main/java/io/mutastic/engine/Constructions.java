package io.mutastic.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// How a covering test makes the receiver a suggested call needs, read from its source: `new
/// Delivery(50)`, with whole numbers only. A suggestion made the same way is one its reader knows,
/// and its value for a field the receiver keeps is the one the tests use, not one mutastic picked.
final class Constructions {
  /// A receiver a test makes.
  ///
  /// @param test the test that makes it, as a card names it: `DeliveryTest#smallOrders`, or the
  ///   class, `DeliveryTest`, when the class makes it outside any test
  /// @param arguments its constructor's arguments
  /// @param text how it's written, `new Delivery(50)`
  record Built(String test, List<Long> arguments, String text) {}

  private static final Pattern WHOLE = Pattern.compile("-?[\\d_]+[lL]?");

  private Constructions() {}

  /// The first of `tests` (`com.example.DeliveryTest#smallOrders`) whose method makes a `type` with
  /// `arity` whole numbers; else the first test class that makes exactly one, outside its tests, as
  /// a field does.
  static Optional<Built> in(List<Path> testSources, List<String> tests, String type, int arity) {
    var made =
        Pattern.compile("\\bnew\\s+" + Pattern.quote(type) + "\\s*\\((?<arguments>[^()]*)\\)");
    for (String test : tests.stream().filter(test -> test.contains("#")).toList()) {
      var className = test.substring(0, test.indexOf('#'));
      var method = test.substring(test.indexOf('#') + 1).replaceAll("\\(.*$", "");
      var source = source(testSources, className);
      var found = source.flatMap(text -> first(made, body(text, method), arity));
      if (found.isPresent()) {
        return found.map(arguments -> built(shortName(className) + "#" + method, type, arguments));
      }
      var whole = source.map(text -> all(made, text, arity)).orElse(List.of());
      if (whole.size() == 1) {
        return Optional.of(built(shortName(className), type, whole.getFirst()));
      }
    }
    return Optional.empty();
  }

  private static Built built(String test, String type, List<Long> arguments) {
    var written = arguments.stream().map(String::valueOf).toList();
    return new Built(test, arguments, "new %s(%s)".formatted(type, String.join(", ", written)));
  }

  private static Optional<List<Long>> first(Pattern made, String text, int arity) {
    var all = all(made, text, arity);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
  }

  /// Each distinct list of arguments `text` makes the type with, in order.
  private static List<List<Long>> all(Pattern made, String text, int arity) {
    var found = new ArrayList<List<Long>>();
    var matcher = made.matcher(text);
    while (matcher.find()) {
      var arguments = numbers(matcher.group("arguments"));
      if (arguments.isPresent() && arguments.get().size() == arity) {
        if (!found.contains(arguments.get())) {
          found.add(arguments.get());
        }
      }
    }
    return found;
  }

  /// `50, 2L` as numbers; empty if any isn't a whole number written out.
  private static Optional<List<Long>> numbers(String arguments) {
    if (arguments.isBlank()) {
      return Optional.of(List.of());
    }
    var values = new ArrayList<Long>();
    for (String argument : arguments.split(",", -1)) {
      var written = argument.strip();
      if (!WHOLE.matcher(written).matches()) {
        return Optional.empty();
      }
      try {
        values.add(Long.parseLong(written.replace("_", "").replaceAll("[lL]$", "")));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    return Optional.of(values);
  }

  /// A method's body, from its name to its closing brace; empty if it isn't there.
  private static String body(String source, String method) {
    var declared = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\([^)]*\\)[^{;]*\\{");
    var found = declared.matcher(source);
    if (!found.find()) {
      return "";
    }
    int depth = 1;
    int at = found.end();
    while (at < source.length() && depth > 0) {
      char each = source.charAt(at);
      depth += each == '{' ? 1 : each == '}' ? -1 : 0;
      at++;
    }
    return source.substring(found.end(), at);
  }

  /// The test class's source, by its binary name: the top-level class's file.
  private static Optional<String> source(List<Path> testSources, String className) {
    var topLevel =
        className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
    var file = topLevel.replace('.', '/') + ".java";
    for (Path root : testSources) {
      var path = root.resolve(file);
      if (Files.isRegularFile(path)) {
        try {
          return Optional.of(Files.readString(path));
        } catch (IOException e) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  private static String shortName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }
}
