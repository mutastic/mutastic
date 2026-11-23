package io.mutastic.worker.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/// Shaped like a test `@Configuration`: found by a scan through [Configured], after an annotation
/// that takes some skipping, with fields and methods before the class's own annotations.
@Settings(
    port = 8080,
    timeout = 1L << 40,
    ratio = 0.5,
    name = "app",
    type = TestApp.class,
    kind = ElementType.TYPE,
    retention = @Retention(RetentionPolicy.RUNTIME),
    tags = {"fast", "local"})
@Configured
public class TestApp {
  static final long BIG = 1L << 40;
  static final double HALF = 0.5;

  @Deprecated int port = 8080;

  @Deprecated
  public long big() {
    return BIG + (long) HALF;
  }

  /// Comes with its class.
  public static final class Inner {}
}
