package io.proptastic;

import java.io.Serializable;

/// A property over one domain, run by `check`.
public final class Property1<A> {
  private final Domain<A> domain;

  Property1(Domain<A> domain) {
    this.domain = domain;
  }

  /// Runs the property now: throws with the counterexample if `check` fails.
  public void check(Check<A> check) {
    Runner.check(domain, check);
  }

  /// The property's body.
  @FunctionalInterface
  public interface Check<A> extends Serializable {
    void check(A a) throws Throwable;
  }
}
