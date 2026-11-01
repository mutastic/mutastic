package io.mutastic.engine.fixture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/// A team's own annotations, matched by simple name: skips with the team's reason, generated code,
/// and `@DoMutate` to keep a debug representation that is a contract.
public class Annotated {
  @Retention(RetentionPolicy.CLASS)
  @interface DoNotMutate {
    String value() default "";
  }

  @Retention(RetentionPolicy.CLASS)
  @interface Generated {}

  @Retention(RetentionPolicy.CLASS)
  @interface DoMutate {}

  private int total = 1;

  @DoNotMutate("measured in the load test, not here")
  public int retries() {
    return total > 3 ? 3 : total;
  }

  @Generated
  public int generated() {
    return total + 1;
  }

  /// Shown to customers: a contract, so mutated.
  @DoMutate
  @Override
  public String toString() {
    return total > 0 ? "paid" : "due";
  }
}
