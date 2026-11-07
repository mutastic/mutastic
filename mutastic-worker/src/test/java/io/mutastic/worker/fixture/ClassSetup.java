package io.mutastic.worker.fixture;

import io.mutastic.worker.Probes;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// Run through a launcher by `TestScopesTest`: each place code can run calls a line of its own.
@Tag("fixture")
public class ClassSetup {
  public static final int STATIC_INITIALISER = 1;
  public static final int BEFORE_ALL = 2;
  public static final int AFTER_ALL = 3;
  public static final int BEFORE_EACH = 4;
  public static final int FIRST = 5;
  public static final int SECOND = 6;
  public static final int FACTORY = 7;
  public static final int NESTED_BEFORE_EACH = 8;
  public static final int NESTED = 9;

  static {
    Probes.line(STATIC_INITIALISER);
  }

  @BeforeAll
  static void beforeAll() {
    Probes.line(BEFORE_ALL);
  }

  @AfterAll
  static void afterAll() {
    Probes.line(AFTER_ALL);
  }

  @BeforeEach
  void beforeEach() {
    Probes.line(BEFORE_EACH);
  }

  @Test
  void first() {
    Probes.line(FIRST);
  }

  @Test
  void second() {
    Probes.line(SECOND);
  }

  static IntStream factory() {
    Probes.line(FACTORY);
    return IntStream.of(1, 2);
  }

  @ParameterizedTest
  @MethodSource("factory")
  void parameterised(int value) {}

  @Nested
  public class Inner {
    @BeforeEach
    void beforeEach() {
      Probes.line(NESTED_BEFORE_EACH);
    }

    @Test
    void nested() {
      Probes.line(NESTED);
    }
  }
}
