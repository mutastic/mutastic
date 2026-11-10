package io.mutastic.report;

import io.mutastic.api.Mutant;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// The whole numbers the covering tests passed, by parameter, and which of the parameters are
/// `char`s: those are written as characters, `'/'`, not as the numbers the bytecode has.
///
/// The values come by name, in the order of the method's whole-number parameters, without the ones
/// no test passed anything to; so a parameter is known to be a `char` when every one is, or when
/// every one was passed something. Otherwise its values stay numbers.
record Inputs(Map<String, List<Long>> values, Set<String> characters) {
  /// The descriptors of the parameters whose values are recorded.
  private static final Set<String> WHOLE_NUMBERS = Set.of("I", "J", "S", "B", "C");

  Inputs {
    values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    characters = Set.copyOf(characters);
  }

  /// The inputs the tests passed to the mutant's method.
  static Inputs of(Mutant mutant, Map<String, List<Long>> values) {
    if (values.isEmpty()) {
      return new Inputs(values, Set.of());
    }
    var types = wholeNumberTypes(mutant.descriptor());
    if (!types.contains("C")) {
      return new Inputs(values, Set.of());
    }
    if (types.stream().allMatch("C"::equals)) {
      return new Inputs(values, values.keySet());
    }
    if (types.size() != values.size()) {
      return new Inputs(values, Set.of());
    }
    var names = List.copyOf(values.keySet());
    var characters = new java.util.HashSet<String>();
    for (int index = 0; index < types.size(); index++) {
      if (types.get(index).equals("C")) {
        characters.add(names.get(index));
      }
    }
    return new Inputs(values, characters);
  }

  /// `c = 'a', 'z'`, `total = 50, 250`: one parameter's values as the source would write them.
  String written(String name) {
    var each = values.getOrDefault(name, List.of());
    return name
        + " = "
        + (characters.contains(name)
            ? characters(each)
            : each.stream().map(String::valueOf).collect(Collectors.joining(", ")));
  }

  /// `'a', 'z'`.
  static String characters(List<Long> values) {
    return values.stream().map(Inputs::character).collect(Collectors.joining(", "));
  }

  /// `'/'`, `'\\n'`, `'\\''`; a value a `char` can't hold stays a number.
  static String character(long value) {
    if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) {
      return String.valueOf(value);
    }
    return switch ((char) value) {
      case '\0' -> "'\\0'";
      case '\n' -> "'\\n'";
      case '\t' -> "'\\t'";
      case '\r' -> "'\\r'";
      case '\'' -> "'\\''";
      case '\\' -> "'\\\\'";
      default ->
          value >= ' ' && value <= '~' ? "'" + (char) value + "'" : "'\\u%04x'".formatted(value);
    };
  }

  private static List<String> wholeNumberTypes(String descriptor) {
    if (!descriptor.startsWith("(")) {
      return List.of(); // not a method's: a field's or a class's mutant
    }
    try {
      return MethodTypeDesc.ofDescriptor(descriptor).parameterList().stream()
          .map(type -> type.descriptorString())
          .filter(WHOLE_NUMBERS::contains)
          .toList();
    } catch (IllegalArgumentException e) {
      return List.of(); // a descriptor the JVM wouldn't take
    }
  }
}
