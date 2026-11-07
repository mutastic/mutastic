package io.mutastic.api;

import java.util.List;

/// A call that tells a missed mutant from the code today, found by running both: the test to write
/// is an assertion of today's value. Today's value is what the code does, not necessarily what it
/// should, so the person checks it before keeping the test.
///
/// @param call the call in the language of the mutated class: `new Pricing().discount(100)`, or in
///   Kotlin `Pricing().discount(100)`
/// @param parameters the method's parameter names, in order
/// @param inputs the arguments, one per parameter
/// @param receiver the instances made to call the method on, outermost first: the receiver, after
///   the outer class's instance it's made in if it's an inner class; empty for a static method
/// @param today what the call returns today, as Java source: `10`, `true`, `"text"`, `throws
///   java.lang.IllegalStateException`
/// @param mutated what it returns with the mutant in, the same way, or `doesn't return`
public record Suggestion(
    String call,
    List<String> parameters,
    List<Long> inputs,
    List<Suggestion.Instance> receiver,
    String today,
    String mutated) {

  public Suggestion {
    parameters = List.copyOf(parameters);
    inputs = List.copyOf(inputs);
    receiver = List.copyOf(receiver);
  }

  /// A call with no receiver arguments: a static method, or a no-argument constructor.
  public Suggestion(
      String call, List<String> parameters, List<Long> inputs, String today, String mutated) {
    this(call, parameters, inputs, List.of(), today, mutated);
  }

  /// An instance a suggestion makes: its class's simple name and constructor arguments, `Window`
  /// and `[5, 10]`.
  public record Instance(String type, List<Long> arguments) {
    public Instance {
      arguments = List.copyOf(arguments);
    }
  }

  /// Whether making the receiver takes any arguments.
  public boolean receiverTakesArguments() {
    return receiver.stream().anyMatch(instance -> !instance.arguments().isEmpty());
  }

  /// Whether today's result is a value (not an exception), which an assertion can pin.
  public boolean returnsValue() {
    return !today.startsWith("throws ");
  }
}
