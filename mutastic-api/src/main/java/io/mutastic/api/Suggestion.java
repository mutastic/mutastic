package io.mutastic.api;

import java.util.List;

/// A call that tells a missed mutant from the code today, found by running both: the test to write
/// is an assertion of today's value. Today's value is what the code does, not necessarily what it
/// should, so the person checks it before keeping the test.
///
/// @param call the call as Java source, `new Pricing().discount(100)`
/// @param parameters the method's parameter names, in order
/// @param inputs the arguments, one per parameter
/// @param receiverType the simple name of the class whose constructor makes the receiver, if it
///   takes arguments, `Window`
/// @param receiverArguments those constructor arguments, empty for a static method or a no-argument
///   constructor
/// @param today what the call returns today, as Java source: `10`, `true`, `"text"`, `throws
///   java.lang.IllegalStateException`
/// @param mutated what it returns with the mutant in, the same way, or `doesn't return`
public record Suggestion(
    String call,
    List<String> parameters,
    List<Long> inputs,
    String receiverType,
    List<Long> receiverArguments,
    String today,
    String mutated) {

  public Suggestion {
    parameters = List.copyOf(parameters);
    inputs = List.copyOf(inputs);
    receiverArguments = List.copyOf(receiverArguments);
  }

  /// A call with no receiver arguments: a static method, or a no-argument constructor.
  public Suggestion(
      String call, List<String> parameters, List<Long> inputs, String today, String mutated) {
    this(call, parameters, inputs, "", List.of(), today, mutated);
  }

  /// Whether today's result is a value (not an exception), which an assertion can pin.
  public boolean returnsValue() {
    return !today.startsWith("throws ");
  }
}
