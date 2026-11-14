package io.mutastic.report;

import java.util.regex.Pattern;

/// What a suggested test names that its test class may not import: its test annotation and its
/// assertion. Each is written as the class can read it, by its simple name if the class imports it,
/// and in full if not, so the test compiles as pasted.
final class Imports {
  private Imports() {}

  /// `Test` if the source imports `type` (`org.junit.jupiter.api.Test`) or its package's every
  /// type, or, in Kotlin, a `Test` of its own (`kotlin.test.Test`); `type` otherwise.
  static String annotation(String source, String type, boolean kotlin) {
    if (source.isEmpty()) {
      return type.substring(type.lastIndexOf('.') + 1);
    }
    var simple = type.substring(type.lastIndexOf('.') + 1);
    var pack = type.substring(0, type.lastIndexOf('.'));
    boolean imported =
        imports(source, Pattern.quote(type), kotlin)
            || imports(source, Pattern.quote(pack) + "\\.\\*", kotlin)
            || (kotlin && imports(source, "[\\w.]+\\." + simple, true));
    return imported ? simple : type;
  }

  /// The assertion as it is when the source imports its method, `assertFalse(…)` for `import static
  /// org.junit.jupiter.api.Assertions.assertFalse`, a method of that name from elsewhere, or the
  /// class's every static; `Assertions.assertFalse(…)` when it imports the class; and
  /// `org.junit.jupiter.api.Assertions.assertFalse(…)` otherwise.
  static String assertion(String source, String assertion, String assertions, boolean kotlin) {
    if (source.isEmpty()) {
      return assertion;
    }
    int end = 0;
    while (end < assertion.length() && Character.isJavaIdentifierPart(assertion.charAt(end))) {
      end++;
    }
    var method = assertion.substring(0, end);
    var member = (kotlin ? "" : "static\\s+") + "[\\w.]+\\." + Pattern.quote(method);
    var every = (kotlin ? "" : "static\\s+") + Pattern.quote(assertions) + "\\.\\*";
    if (method.isEmpty() || imports(source, member, kotlin) || imports(source, every, kotlin)) {
      return assertion;
    }
    var simple = assertions.substring(assertions.lastIndexOf('.') + 1);
    return (imports(source, Pattern.quote(assertions), kotlin) ? simple : assertions)
        + "."
        + assertion;
  }

  /// Whether the source has an import of what `name` matches: `import name;` in Java, or `import
  /// name` on a line of its own in Kotlin.
  private static boolean imports(String source, String name, boolean kotlin) {
    var end = kotlin ? "\\s*(?:;\\s*)?$" : "\\s*;";
    return Pattern.compile("^\\s*import\\s+" + name + end, Pattern.MULTILINE)
        .matcher(source)
        .find();
  }
}
