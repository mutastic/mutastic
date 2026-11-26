package io.proptastic.core;

/// A stand-in for proptastic's failure, by its name: what [io.mutastic.worker.CaughtInput] reads.
public final class PropertyFailure extends AssertionError {
  private static final long serialVersionUID = 1L;

  public PropertyFailure(String message, Throwable cause) {
    super(message, cause);
  }
}
