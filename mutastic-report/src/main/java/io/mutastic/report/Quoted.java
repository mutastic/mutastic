package io.mutastic.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/// The project's values that a line of mutastic's own words names, each by a reference, `[1]`, so
/// the line can come before the fence and the values go inside it: `flaky: [1] failed, then passed
/// when run again; …` before it, and `[1] PricingTest#bigOrders` inside. A test's name, a failure's
/// message, an ignore's id or a report's path can say anything, and an agent reads the lines before
/// the fence as mutastic's. Each value has one number, the first it was given; a mutant's id,
/// checked as mutastic's own, is shown as it is.
final class Quoted {
  private final SequencedMap<String, Integer> numbers = new LinkedHashMap<>();

  /// `[1]` for `value`: its number, or the next one when it's new.
  String ref(String value) {
    return "[%d]".formatted(numbers.computeIfAbsent(value, unseen -> numbers.size() + 1));
  }

  /// Each of `values` [#ref]erred to, with commas between.
  String refs(List<String> values) {
    return values.stream().map(this::ref).collect(Collectors.joining(", "));
  }

  /// A mutant's id as it is, `m855c8e3c`; anything else said to be one, by its [#ref].
  String id(String id) {
    return Untrusted.isId(id) ? id : ref(id);
  }

  /// Each of `ids` as [#id] shows it, with commas between.
  String ids(List<String> ids) {
    return ids.stream().map(this::id).collect(Collectors.joining(", "));
  }

  boolean isEmpty() {
    return numbers.isEmpty();
  }

  /// `[1] PricingTest#bigOrders`, a line for each value in the order they were numbered, each
  /// [Untrusted#value]d: the start of the fence.
  String lines() {
    return numbers.entrySet().stream().map(Quoted::line).collect(Collectors.joining());
  }

  private static String line(Map.Entry<String, Integer> numbered) {
    return "[%d] %s\n".formatted(numbered.getValue(), Untrusted.value(numbered.getKey()));
  }
}
