package io.proptastic.core;

import org.opentest4j.AssertionFailedError;

/// A property's failure, as proptastic throws it: the assertion's own message, a blank line, then
/// the report; the cause and the stack trace are the assertion's.
public final class PropertyFailure extends AssertionFailedError {
  private static final long serialVersionUID = 1L;

  public PropertyFailure(String message, Throwable original) {
    super(message, original);
    setStackTrace(original.getStackTrace());
  }
}
