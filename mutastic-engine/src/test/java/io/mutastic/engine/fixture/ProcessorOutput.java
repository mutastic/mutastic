package io.mutastic.engine.fixture;

import java.util.function.IntUnaryOperator;
import javax.annotation.processing.Generated;

/// What an annotation processor writes: `@Generated` that only the source holds.
@Generated(
    value = {"acme.Processor"},
    comments = "{ braces in an annotation's array }")
public class ProcessorOutput {
  public int doubled(int value) {
    return value > 0 ? value * 2 : 0;
  }

  public IntUnaryOperator adder(int step) {
    return value -> value + step;
  }

  /// Nested in the generated type: generated too.
  public static class Builder {
    private int size;

    public Builder size(int size) {
      this.size = size > 0 ? size : 1;
      return this;
    }

    public int build() {
      return size * 2;
    }
  }
}
