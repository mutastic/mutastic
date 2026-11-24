package io.mutastic.engine.tests;

import java.util.Optional;

/// Why a worker's tests couldn't start, as the worker said it: the exception creating the JUnit
/// Platform's launcher or discovering the tests threw, and its first cause.
///
/// @param exception its class, `org.junit.platform.commons.JUnitException`
/// @param message its message's first line
/// @param cause its first cause's class, when it has one
/// @param causeMessage that cause's message's first line
public record StartFailure(
    String exception, String message, Optional<String> cause, Optional<String> causeMessage) {

  /// From the worker's `UNSTARTED` fields: the exception, its message, its cause and the cause's
  /// message, `-` for no cause.
  public static StartFailure of(String exception, String message, String cause, String said) {
    return new StartFailure(exception, message, given(cause), given(said));
  }

  private static Optional<String> given(String field) {
    return field.equals("-") ? Optional.empty() : Optional.of(field);
  }

  /// `TestEngine with ID 'testng' failed to discover tests: NoSuchMethodError: 'void …'`: the
  /// message, then the cause by its simple name and what it said.
  public String said() {
    return message
        + cause
            .map(name -> ": " + simple(name) + causeMessage.map(": "::concat).orElse(""))
            .orElse("");
  }

  /// Whether the exception or its cause is of the class named `name`,
  /// `java.lang.NoSuchMethodError`.
  boolean is(String name) {
    return exception.equals(name) || cause.filter(name::equals).isPresent();
  }

  /// Whether the message or its cause's says `text`.
  boolean says(String text) {
    return message.contains(text) || causeMessage.filter(said -> said.contains(text)).isPresent();
  }

  private static String simple(String name) {
    return name.substring(name.lastIndexOf('.') + 1);
  }
}
