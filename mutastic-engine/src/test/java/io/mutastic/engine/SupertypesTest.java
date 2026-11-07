package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SupertypesTest {
  private static final String HERE = "io/mutastic/engine/SupertypesTest$";

  static class Failure extends IllegalStateException {
    @java.io.Serial private static final long serialVersionUID = 1L;
  }

  static class WorseFailure extends Failure {
    @java.io.Serial private static final long serialVersionUID = 1L;
  }

  /// Named as errors are, and not one.
  static class ConfigError {}

  private static final Supertypes SEEN = Supertypes.in(SupertypesTest.class.getClassLoader());

  @Test
  void walksUpToThrowable() {
    assertThat(SEEN.isThrowable("java/lang/Throwable")).isTrue();
    assertThat(SEEN.isThrowable("java/lang/StackOverflowError")).isTrue();
    assertThat(SEEN.isThrowable(HERE + "WorseFailure")).isTrue();
  }

  @Test
  void aNameIsNoEvidence() {
    assertThat(SEEN.isThrowable(HERE + "ConfigError")).isFalse();
    assertThat(SEEN.isThrowable("java/lang/Object")).isFalse();
  }

  @Test
  void whatCantBeReadIsNotAThrowable() throws IOException {
    assertThat(SEEN.isThrowable("com/example/NoSuchException")).isFalse();
    assertThat(new Supertypes(name -> new ByteArrayInputStream(new byte[] {1})).isThrowable("a/B"))
        .isFalse();
    // A class whose superclass is missing, part of the way up.
    var failure = fileOf(HERE + "WorseFailure");
    var cutShort =
        new Supertypes(
            name -> name.equals(HERE + "WorseFailure") ? new ByteArrayInputStream(failure) : null);
    assertThat(cutShort.isThrowable(HERE + "WorseFailure")).isFalse();
  }

  private static byte[] fileOf(String name) throws IOException {
    try (var file = SupertypesTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
      return Objects.requireNonNull(file).readAllBytes();
    }
  }
}
