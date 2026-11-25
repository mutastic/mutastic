package io.mutastic.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What a name given to `--exclude-tests` or `exclude-tests:` leaves out: the one rule, used
/// wherever a test is matched to it, the worker's filter, the engine's choice of test classes and
/// the flaky watch alike. A name is any form mutastic prints a test in:
///
/// - a test class by its name or full name, `PricingTest` or `com.example.PricingTest`, which
///   leaves out every test in it;
/// - one method of it, `PricingTest#bigOrders`, with every overload and every row a parameterized
///   test makes of it;
/// - one overload, with its parameter types as a run names the test, by their names or full names:
///   `com.example.PricingTest#bigOrders(com.example.Cart, int)` or `PricingTest#bigOrders(Cart,
///   int)`.
///
/// A test is named as [#id] makes it: `com.example.PricingTest#bigOrders(com.example.Cart, int)`,
/// or `com.example.PricingTest` for its class as a whole. A parameterized test's rows have no name
/// of their own: a run names the method, and the row only as evidence of that run.
public final class TestNames {
  private TestNames() {}

  /// `com.example.PricingTest#bigOrders(com.example.Cart, int)`, the test a run names: its class,
  /// its method, and its parameter types as JUnit gives them (`java.nio.file.Path, int`), when it
  /// has any.
  public static String id(String className, String method, String parameterTypes) {
    return className + "#" + method + (parameterTypes.isEmpty() ? "" : "(" + parameterTypes + ")");
  }

  /// Whether `given` leaves out `test`, a test as [#id] names it or a test class by its full name.
  public static boolean leavesOut(String given, String test) {
    var name = Name.of(given);
    var named = Name.of(test);
    return name.sameClass(named.className())
        && (name.method().isEmpty()
            || (name.method().equals(named.method())
                && (name.parameters().isEmpty()
                    || name.sameParameters(named.parameters().orElse(List.of())))));
  }

  /// Whether `given` leaves out the test class `className` (`com.example.PricingTest`) whole: a
  /// class, not one method of it.
  public static boolean leavesOutClass(String given, String className) {
    var name = Name.of(given);
    return name.method().isEmpty() && name.sameClass(className);
  }

  /// Whether `given` is one method of a class, which only the tests' own run can leave out.
  public static boolean isMethod(String given) {
    return given.indexOf('#') >= 0;
  }

  /// `RacyTest,PricingTest#bigOrders(Cart, int)` → `RacyTest` and `PricingTest#bigOrders(Cart,
  /// int)`: split at each comma outside a parameter list, as a run names an overload with commas in
  /// it. Each is stripped, and a blank one dropped.
  public static List<String> split(String names) {
    var each = new ArrayList<String>();
    int depth = 0;
    int start = 0;
    for (int at = 0; at < names.length(); at++) {
      char next = names.charAt(at);
      if (next == '(') {
        depth++;
      } else if (next == ')') {
        depth = Math.max(0, depth - 1);
      } else if (next == ',' && depth == 0) {
        add(each, names.substring(start, at));
        start = at + 1;
      }
    }
    add(each, names.substring(start));
    return each;
  }

  private static void add(List<String> each, String name) {
    if (!name.isBlank()) {
      each.add(name.strip());
    }
  }

  /// A name taken apart: its class, its method (empty for a class), and its parameter types when it
  /// gives them, each without spaces and in source form (`java.lang.String[]`, not
  /// `[Ljava.lang.String;`).
  private record Name(String className, String method, Optional<List<String>> parameters) {
    static Name of(String text) {
      var name = text.strip();
      int hash = name.indexOf('#');
      if (hash < 0) {
        return new Name(name, "", Optional.empty());
      }
      var className = name.substring(0, hash).strip();
      var rest = name.substring(hash + 1).strip();
      int open = rest.indexOf('(');
      if (open < 0) {
        return new Name(className, rest, Optional.empty());
      }
      int close = rest.lastIndexOf(')');
      var inside = rest.substring(open + 1, close > open ? close : rest.length());
      return new Name(className, rest.substring(0, open).strip(), Optional.of(types(inside)));
    }

    private static List<String> types(String inside) {
      var types = new ArrayList<String>();
      for (String type : split(inside)) {
        types.add(sourceForm(type.replace(" ", "")));
      }
      return types;
    }

    /// Whether a test of the class `full` (`com.example.PricingTest`) is of this name's class: by
    /// its full name, or its name alone.
    boolean sameClass(String full) {
      return className.equals(full) || className.equals(simple(full));
    }

    /// Whether `types`, a test's full parameter types, are this name's, each by its full name or
    /// its name alone.
    boolean sameParameters(List<String> types) {
      var mine = parameters.orElse(List.of());
      if (mine.size() != types.size()) {
        return false;
      }
      for (int i = 0; i < mine.size(); i++) {
        var type = types.get(i);
        if (!mine.get(i).equals(type) && !mine.get(i).equals(simple(type))) {
          return false;
        }
      }
      return true;
    }
  }

  /// `com.example.PricingTest` → `PricingTest`; `java.util.Map$Entry[]` → `Map$Entry[]`.
  private static String simple(String full) {
    return full.substring(full.lastIndexOf('.') + 1);
  }

  /// `[Ljava.lang.String;` → `java.lang.String[]`, `[I` → `int[]`, `String...` → `String[]`: an
  /// array as source writes it, which is how a person types it, whatever form JUnit gave.
  private static String sourceForm(String type) {
    if (type.endsWith("...")) {
      return type.substring(0, type.length() - 3) + "[]";
    }
    if (!type.startsWith("[")) {
      return type;
    }
    int dimensions = 0;
    while (dimensions < type.length() && type.charAt(dimensions) == '[') {
      dimensions++;
    }
    var element = type.substring(dimensions);
    var named =
        element.startsWith("L") && element.endsWith(";")
            ? element.substring(1, element.length() - 1)
            : primitive(element);
    return named + "[]".repeat(dimensions);
  }

  private static String primitive(String code) {
    return switch (code) {
      case "Z" -> "boolean";
      case "B" -> "byte";
      case "C" -> "char";
      case "S" -> "short";
      case "I" -> "int";
      case "J" -> "long";
      case "F" -> "float";
      case "D" -> "double";
      default -> code;
    };
  }
}
