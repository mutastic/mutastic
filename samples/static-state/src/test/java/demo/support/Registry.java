package demo.support;

/// A registry the tests keep, in a package of the tests' own.
public final class Registry {
  private int registered;

  public int register() {
    return ++registered;
  }
}
