package io.mutastic.engine.fixture;

/// Fields and boxed values in comparisons: javac reads a field with `getfield` and unboxes with a
/// call to `intValue()`, neither of which is in the source.
public final class Window {
  private final Integer start;
  private final Boolean open;
  private final long end;

  public Window(Integer start, Boolean open, long end) {
    this.start = start;
    this.open = open;
    this.end = end;
  }

  public boolean after(int day) {
    return start <= day;
  }

  public boolean before(int day) {
    return day < end;
  }

  public String state() {
    return open ? "open" : "shut";
  }
}
