package io.mutastic.engine.fixture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/// A team's own `@DoNotMutate`, declared in its own code with `reason()`: it works the same as
/// mutastic's, so nothing has to depend on mutastic.
public class OwnAnnotations {
  @Retention(RetentionPolicy.CLASS)
  @interface DoNotMutate {
    String reason() default "";
  }

  private int count = 1;

  @DoNotMutate(reason = "tested elsewhere")
  public int capped() {
    return count > 3 ? 3 : count;
  }
}
