package io.mutastic.engine.fixture;

/// `@DoMutate` brings a class's trivial accessors back: a team's own annotation, by its simple
/// name.
@Kept.DoMutate
public class Kept {
  /// Any annotation called `DoMutate` works, wherever it's declared.
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
  @interface DoMutate {}

  private String name = "kept";

  public String getName() {
    return name;
  }
}
