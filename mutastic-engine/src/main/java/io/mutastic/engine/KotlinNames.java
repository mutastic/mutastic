package io.mutastic.engine;

import java.util.List;
import java.util.regex.Pattern;

/// What kotlinc calls things, as the source does. `x$iv` is `x` in the inline function it was
/// copied from; `$this$doubled` is an extension's receiver, `<set-?>` a setter's value. A name with
/// no source to go by, `local3` for a slot the table of locals doesn't name, is no help to anyone
/// reading a card, so what depends on it says less.
final class KotlinNames {
  /// `x$iv`, `x$iv$iv`: a variable of an inline function, copied in.
  private static final Pattern INLINED = Pattern.compile("(\\$iv)+\\b");

  private static final Pattern THIS = Pattern.compile("\\$this\\$\\w+");
  private static final Pattern UNNAMED = Pattern.compile("\\blocal\\d+\\b|\\$");

  private KotlinNames() {}

  /// A parameter's name: what's left of it, for a test's name and a list of inputs.
  static String parameter(String name) {
    if (name.startsWith("$this$")) {
      return "receiver";
    }
    return name.equals("<set-?>") ? "value" : name;
  }

  /// What an instruction works on, each as the source names it: nothing if any of it can't be.
  static List<String> operands(List<String> operands) {
    var named =
        operands.stream()
            .map(operand -> INLINED.matcher(operand).replaceAll(""))
            .map(operand -> THIS.matcher(operand).replaceAll("this"))
            .toList();
    return named.stream().anyMatch(operand -> UNNAMED.matcher(operand).find()) ? List.of() : named;
  }
}
