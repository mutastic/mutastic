package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.engine.fixture.Visibility;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccessTest {
  private static final String ELSEWHERE = "io.mutastic.engine.SomeTest";
  private static final String SAME_PACKAGE = "io.mutastic.engine.fixture.SomeTest";

  @Test
  void publicMethodsFromAnywhere() throws Exception {
    assertThat(callable(Visibility.class, "open", ELSEWHERE)).isTrue();
    assertThat(callable(Visibility.class, "onInstance", ELSEWHERE)).isTrue();
    assertThat(callable(Visibility.Nested.class, "open", ELSEWHERE)).isTrue();
  }

  @Test
  void packagePrivateOnlyFromTheSamePackage() throws Exception {
    assertThat(callable(Visibility.class, "packageOnly", SAME_PACKAGE)).isTrue();
    assertThat(callable(Visibility.class, "packageOnly", ELSEWHERE)).isFalse();
  }

  @Test
  void privateNeverAndNeitherIsWhatAPrivateThingHolds() throws Exception {
    assertThat(callable(Visibility.class, "hidden", SAME_PACKAGE)).isFalse();
    assertThat(
            callable(Class.forName(Visibility.class.getName() + "$Hidden"), "open", SAME_PACKAGE))
        .isFalse();
    assertThat(callable(Visibility.NoPublicConstructor.class, "onInstance", SAME_PACKAGE))
        .isFalse();
    assertThat(callable(Visibility.Inner.class, "onInstance", SAME_PACKAGE))
        .isFalse(); // needs an outer instance
  }

  @Test
  void picksHowToMakeTheReceiver() throws Exception {
    assertThat(receiver(Visibility.class, "open", ELSEWHERE)).contains("-");
    assertThat(receiver(Visibility.class, "onInstance", ELSEWHERE)).contains("()V");
    // A record of numbers: its canonical constructor, which a test can write with literals.
    assertThat(receiver(Visibility.Range.class, "contains", ELSEWHERE))
        .contains("(Ljava/lang/Integer;J)V");
    // A constructor that needs a String can't be filled with numbers.
    assertThat(receiver(Visibility.Named.class, "length", ELSEWHERE)).isEmpty();
  }

  private static Optional<String> receiver(Class<?> type, String method, String testClass)
      throws Exception {
    var model = MutationsTest.model(type);
    var descriptor =
        model.methods().stream()
            .filter(found -> found.methodName().equalsString(method))
            .findFirst()
            .orElseThrow()
            .methodType()
            .stringValue();
    var mutant =
        new Mutant(
            model.thisClass().asInternalName(),
            method,
            descriptor,
            0,
            1,
            Operator.MATH,
            "IADD",
            "ISUB",
            Optional.empty());
    return Access.receiver(model, mutant, testClass);
  }

  private static boolean callable(Class<?> type, String method, String testClass) throws Exception {
    var model = MutationsTest.model(type);
    var descriptor =
        model.methods().stream()
            .filter(found -> found.methodName().equalsString(method))
            .findFirst()
            .orElseThrow()
            .methodType()
            .stringValue();
    var mutant =
        new Mutant(
            model.thisClass().asInternalName(),
            method,
            descriptor,
            0,
            1,
            Operator.MATH,
            "IADD",
            "ISUB",
            Optional.empty());
    return Access.callableFrom(model, mutant, testClass);
  }
}
