package io.proptastic;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/// `int`s between two inclusive bounds, shrunk toward the one nearest zero.
public final class IntegerDomain extends Domain<Integer> {
  private final int least;
  private final int most;

  IntegerDomain(int least, int most) {
    this.least = least;
    this.most = most;
  }

  /// `n` or more.
  public IntegerDomain atLeast(int n) {
    return new IntegerDomain(n, most);
  }

  /// `n` or less.
  public IntegerDomain atMost(int n) {
    return new IntegerDomain(least, n);
  }

  @Override
  Integer draw(SplittableRandom random) {
    return (int) random.nextLong(least, (long) most + 1);
  }

  @Override
  List<Integer> smaller(Integer value) {
    int target = Math.max(least, Math.min(most, 0));
    var out = new ArrayList<Integer>();
    if (value != target) {
      out.add(target);
      int half = (int) (value - ((long) value - target) / 2);
      if (half != value && half != target) {
        out.add(half);
      }
      int step = value > target ? value - 1 : value + 1;
      if (step != target && step != half) {
        out.add(step);
      }
    }
    return out;
  }

  @Override
  String shown(Integer value) {
    return value.toString();
  }

  @Override
  String source(Integer value) {
    return value.toString();
  }
}
