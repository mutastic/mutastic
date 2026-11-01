package io.mutastic.engine.fixture;

import io.mutastic.annotations.DoMutate;
import io.mutastic.annotations.DoNotMutate;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/// mutastic's annotations, as a project uses them, beside a team's own `@Generated`: all matched by
/// simple name.
public class Annotated {
  @Retention(RetentionPolicy.CLASS)
  @interface Generated {}

  private int count = 1;

  @DoNotMutate("tested elsewhere")
  public int capped() {
    return count > 3 ? 3 : count;
  }

  @Generated
  public int generated() {
    return count + 1;
  }

  @DoMutate("a contract: shown to people")
  @Override
  public String toString() {
    return count > 0 ? "some" : "none";
  }
}
