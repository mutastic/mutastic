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
/// @param named the value the call is at the edge of, when the source names it: a constant, or a
///   field the constructor sets from an argument
@Internal
public record Suggestion(
    String call,
    List<String> parameters,
    List<Long> inputs,
    List<Suggestion.Instance> receiver,
    String today,
    String mutated,
    java.util.Optional<Named> named) {

  public Suggestion {
    parameters = List.copyOf(parameters);
    inputs = List.copyOf(inputs);
    receiver = List.copyOf(receiver);
  }

  /// A call at no value the source names.
  public Suggestion(
      String call,
      List<String> parameters,
      List<Long> inputs,
      List<Instance> receiver,
      String today,
      String mutated) {
    this(call, parameters, inputs, receiver, today, mutated, java.util.Optional.empty());
  }

  /// A call with no receiver arguments: a static method, or a no-argument constructor.
  public Suggestion(
      String call, List<String> parameters, List<Long> inputs, String today, String mutated) {
    this(call, parameters, inputs, List.of(), today, mutated);
  }

  /// An instance a suggestion makes: its class's simple name, its constructor arguments and the
  /// constructor's names for them, `Window`, `[5, 10]` and `[start, end]`.
  public record Instance(String type, List<Long> arguments, List<String> parameters) {
    public Instance {
      arguments = List.copyOf(arguments);
      parameters = List.copyOf(parameters);
    }

    /// An instance whose constructor's names for its arguments aren't known.
    public Instance(String type, List<Long> arguments) {
      this(type, arguments, List.of());
    }
  }

  /// The value a suggested call is at the edge of, as the source names it, which the test is named
  /// after: a constant (`FREE_FROM`), or a field the constructor sets (`freeFrom`).
  ///
  /// @param name the constant's or field's name, as the source has it
  /// @param note what the test's comment says of it: `FREE_FROM = 100`, or `100 is Pricing's
  ///   FREE_FROM` for a constant the test can't use; empty if it needs nothing
  /// @param local the line a test declares the value in, when it's passed twice: `int freeFrom =
  ///   20;`
  public record Named(String name, String note, java.util.Optional<String> local) {}

  /// Whether making the receiver takes any arguments.
  public boolean receiverTakesArguments() {
    return receiver.stream().anyMatch(instance -> !instance.arguments().isEmpty());
  }

  /// Whether today's result is a value (not an exception), which an assertion can pin.
  public boolean returnsValue() {
    return !today.startsWith("throws ");
  }
}
