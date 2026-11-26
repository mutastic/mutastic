package io.proptastic;

import java.util.List;
import java.util.SplittableRandom;

/// What produces values: drawn from the property's random stream, shrunk by smaller candidates.
public abstract class Domain<T> {
  Domain() {}

  abstract T draw(SplittableRandom random);

  /// Values smaller than `value`, the smallest first.
  abstract List<T> smaller(T value);

  /// `value` as a failure shows it.
  abstract String shown(T value);

  /// `value` as Java source, for the pin line.
  abstract String source(T value);
}
