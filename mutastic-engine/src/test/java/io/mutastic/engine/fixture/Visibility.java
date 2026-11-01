package io.mutastic.engine.fixture;

/// Methods and classes a test elsewhere can and can't call.
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
  }

  static Class<?> hiddenClass() {
    return Hidden.class;
  }
}
