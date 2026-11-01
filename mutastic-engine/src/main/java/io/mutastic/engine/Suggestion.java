package io.mutastic.engine;

import java.util.List;

/// A call that tells a missed mutant from the code today, found by running both: the test to
/// write is an assertion of today's value. Today's value is what the code does, not
/// necessarily what it should, so the person checks it before keeping the test.
///
/// @param call the call as Java source, `new Pricing().discount(100)`
/// @param parameters the method's parameter names, in order
/// @param inputs the arguments, one per parameter
/// @param today what the call returns today, as Java source: `10`, `true`, `"text"`, `throws
///     java.lang.IllegalStateException`
/// @param mutated what it returns with the mutant in, the same way, or `doesn't return`
public record Suggestion(String call, List<String> parameters, List<Long> inputs, String today, String mutated) {

  public Suggestion {
    parameters = List.copyOf(parameters);
    inputs = List.copyOf(inputs);
  }

  /// Whether today's result is a value (not an exception), which an assertion can pin.
  public boolean returnsValue() {
    return !today.startsWith("throws ");
  }
}
