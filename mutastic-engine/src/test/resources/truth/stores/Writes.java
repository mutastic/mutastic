package stores;

/// One method for each shape javac compiles a write to a field to. Each write is on a line of its
/// own, so the card's claim, the write removed, can be written by taking the line out, or, where
/// its value is used, by the line in its `// without NAME:` comment. The static methods run them.
@SuppressWarnings("unused")
final class Writes {
  private static int shared;
  private static long sharedWide;

  private int count;
  private long wide;
  private double real;
  private String text = "start";
  private Writes other;

  private Writes() {}

  void setPlain(int a, int b) {
    count = a + b + 1;
  }

  void setWide(int a, int b) {
    wide = a * 3L + 1;
  }

  void setReal(int a, int b) {
    real = b / 2.0 + 1;
  }

  void setText(int a, int b) {
    text = "b" + b;
  }

  void compound(int a, int b) {
    count += b + 1;
  }

  void incremented(int a, int b) {
    count++;
  }

  void onABranch(int a, int b) {
    if (a > b) {
      count = a + 1;
    }
  }

  void toZero(int a, int b) {
    count = 0;
  }

  void onAnotherObject(int a, int b) {
    other = new Writes();
    other.count = a + 1;
  }

  int asAValue(int a, int b) {
    int y = (count = b + 1) + 1; // without count: int y = (b + 1) + 1;
    return y;
  }

  void inAFinally(int a, int b) {
    try {
      if (b < 0) {
        count = a / (b + 1); // without count: int unused = a / (b + 1);
      }
    } finally {
      text = "done" + b;
    }
  }

  static void setShared(int a, int b) {
    shared = a + 1;
  }

  static void setSharedWide(int a, int b) {
    sharedWide = b * 3L + 1;
  }

  void inALambda(int a, int b) {
    Runnable body =
        () -> {
          count = a + 1;
        };
    body.run();
  }

  /// A setter with nothing else in it: arid, as every plain setter is.
  void setCount(int count) {
    this.count = count;
  }

  private String state() {
    return count + "/" + wide + "/" + real + "/" + text + "/"
        + (other == null ? "-" : String.valueOf(other.count));
  }

  /// Runs an instance method on a new object, and gives what it left there and what it returned.
  private static String run(java.util.function.Function<Writes, Object> call) {
    var writes = new Writes();
    try {
      var returned = call.apply(writes);
      return returned + " " + writes.state();
    } catch (ArithmeticException e) {
      return "threw " + writes.state();
    }
  }

  static String plainRun(int a, int b) {
    return run(w -> { w.setPlain(a, b); return null; });
  }

  static String wideRun(int a, int b) {
    return run(w -> { w.setWide(a, b); return null; });
  }

  static String realRun(int a, int b) {
    return run(w -> { w.setReal(a, b); return null; });
  }

  static String textRun(int a, int b) {
    return run(w -> { w.setText(a, b); return null; });
  }

  static String compoundRun(int a, int b) {
    return run(w -> { w.compound(a, b); return null; });
  }

  static String incrementedRun(int a, int b) {
    return run(w -> { w.incremented(a, b); return null; });
  }

  static String branchRun(int a, int b) {
    return run(w -> { w.onABranch(a, b); return null; });
  }

  static String zeroRun(int a, int b) {
    return run(w -> { w.setPlain(a, b); w.toZero(a, b); return null; });
  }

  static String otherRun(int a, int b) {
    return run(w -> { w.onAnotherObject(a, b); return null; });
  }

  static String valueRun(int a, int b) {
    return run(w -> w.asAValue(a, b));
  }

  static String finallyRun(int a, int b) {
    return run(w -> { w.inAFinally(a, b); return null; });
  }

  static String lambdaRun(int a, int b) {
    return run(w -> { w.inALambda(a, b); return null; });
  }

  static String sharedRun(int a, int b) {
    setShared(a, b);
    return String.valueOf(shared);
  }

  static String sharedWideRun(int a, int b) {
    setSharedWide(a, b);
    return String.valueOf(sharedWide);
  }
}
