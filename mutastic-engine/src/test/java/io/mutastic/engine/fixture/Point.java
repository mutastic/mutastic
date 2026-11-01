package io.mutastic.engine.fixture;

/// A record's accessors are trivial unless written with logic, as `y()` is here.
public record Point(int x, int y) {
  @Override
  public int y() {
    return y < 0 ? 0 : y;
  }
}
