package resources;

/// Each way javac closes a try-with-resources resource, which is its code and skipped, and closes
/// written by hand beside one, which are the person's. A hand-written close's card shows its line
/// changed, which is what its mutant does: each line is written into the method and compiled.
@SuppressWarnings({"unused", "try", "ConstantConditions", "EmptyTryBlock"})
final class Closes {
  private Closes() {}

  /// What it's asked to do, written to the log. A name that starts with "b" fails to read, and
  /// one that ends with it fails to close.
  static final class Res implements AutoCloseable {
    private final StringBuilder log;
    private final String name;

    Res(StringBuilder log, String name) {
      this.log = log;
      this.name = name;
    }

    String read() {
      log.append(name).append(" read;");
      if (name.startsWith("b")) {
        throw new IllegalStateException(name);
      }
      return name;
    }

    @Override
    public void close() {
      log.append(name).append(" closed;");
      if (name.endsWith("b")) {
        throw new IllegalArgumentException(name);
      }
    }
  }

  /// No resource for "1".
  private static Res open(StringBuilder log, String name) {
    return name.equals("1") ? null : new Res(log, name);
  }

  private static String failed(StringBuilder log, RuntimeException e) {
    var suppressed = e.getSuppressed().length;
    return log + " threw " + e.getClass().getSimpleName() + " suppressing " + suppressed;
  }

  static String single(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, a)) {
      log.append(r.read());
    }
    return log.toString();
  }

  static String fromNew(String a, String b) {
    var log = new StringBuilder();
    try (var r = new Res(log, a)) {
      log.append(r.read());
    }
    return log.toString();
  }

  static String two(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, a);
        var s = open(log, b)) {
      log.append(r.read());
      log.append(s.read());
    }
    return log.toString();
  }

  static String nested(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, a)) {
      try (var s = open(log, b)) {
        log.append(s.read());
      }
      log.append(r.read());
    }
    return log.toString();
  }

  static String caught(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, a)) {
      log.append(r.read());
    } catch (RuntimeException e) {
      return failed(log, e);
    }
    return log.toString();
  }

  static String withFinally(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, a)) {
      log.append(r.read());
    } finally {
      log.append("done");
    }
    return log.toString();
  }

  static String nullResource(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, "1")) {
      log.append(a);
    }
    return log.toString();
  }

  static String declaredBefore(String a, String b) {
    var log = new StringBuilder();
    var r = open(log, a);
    try (r) {
      log.append(r.read());
    }
    return log.toString();
  }

  static String exits(String a, String b) {
    var log = new StringBuilder();
    try (var r = open(log, a)) {
      log.append(r.read());
      if (a.equals(b)) {
        return log.toString();
      }
      log.append(b);
    }
    return log.toString();
  }

  /// A close of the person's around a try-with-resources.
  static String besideOne(String a, String b) {
    var log = new StringBuilder();
    var other = open(log, b);
    try {
      try (var r = open(log, a)) {
        log.append(r.read());
      }
    } finally {
      if (other != null) {
        other.close();
      }
    }
    return log.toString();
  }




  /// The person's resource, closed on each way out of an `if`, beside a try-with-resources.
  static String closedTwice(String a, String b) {
    var log = new StringBuilder();
    var other = new Res(log, b);
    try (var r = open(log, a)) {
      log.append(r.read());
    }
    if (a.length() > 1) {
      log.append("long;");
      other.close();
    } else {
      other.close();
    }
    return log.toString();
  }

  /// Before Java 7, by hand: what a try-with-resources stands for, written out as javac 7 wrote
  /// it, with the person's own `addSuppressed`.
  static String byHand(String a, String b) {
    var log = new StringBuilder();
    try {
      var r = open(log, a);
      Throwable primary = null;
      try {
        log.append(r.read());
      } catch (Throwable t) {
        primary = t;
        throw t;
      } finally {
        if (r != null) {
          if (primary != null) {
            try {
              r.close();
            } catch (Throwable x) {
              primary.addSuppressed(x);
            }
          } else {
            r.close();
          }
        }
      }
    } catch (RuntimeException e) {
      return failed(log, e);
    }
    return log.toString();
  }
}
