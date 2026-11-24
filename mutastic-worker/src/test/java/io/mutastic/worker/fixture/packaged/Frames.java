package io.mutastic.worker.fixture.packaged;

/// What a test that reports its caller sees of its own stack.
public final class Frames {
  private Frames() {}

  /// The frame this call is in, as `StackTraceElement` writes it.
  public static String here() {
    return new Throwable().getStackTrace()[0].toString();
  }
}
