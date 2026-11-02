package io.mutastic.engine.fixture;

/// Methods and classes a test elsewhere can and can't call. Unused private methods, a non-static
/// inner class and needlessly public members are the point: they're the shapes `AccessTest` reads.
@SuppressWarnings({"UnusedMethod", "EffectivelyPrivate", "ClassCanBeStatic", "unused"})
public class Visibility {
  public static int open(int value) {
    return value + 1;
  }

  static int packageOnly(int value) {
    return value + 1;
  }

  private static int hidden(int value) {
    return value + 1;
  }

  public int onInstance(int value) {
    return value + 1;
  }

  public static final class Nested {
    public static int open(int value) {
      return value + 1;
    }
  }

  private static final class Hidden {
    public static int open(int value) {
      return value + 1;
    }
  }

  public static final class NoPublicConstructor {
    private NoPublicConstructor() {}

    public int onInstance(int value) {
      return value + 1;
    }
  }

  public final class Inner {
    public int onInstance(int value) {
      return value + 1;
    }

    public final class Deeper {
      public int onInstance(int value) {
        return value + 1;
      }
    }
  }

  public final class Sized {
    public Sized(int size) {}

    public int onInstance(int value) {
      return value + 1;
    }
  }

  public record Range(Integer low, long high) {
    public boolean contains(int value) {
      return low <= value && value <= high;
    }
  }

  public static final class Named {
    public Named(String name) {}

    public int length(int extra) {
      return extra + 1;
    }

    public final class Part {
      public int onInstance(int value) {
        return value + 1;
      }
    }
  }

  static Class<?> hiddenClass() {
    return Hidden.class;
  }
}
