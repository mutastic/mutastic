package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.engine.fixture.Visibility;
import java.lang.classfile.ClassModel;
import java.util.HashMap;
import java.util.Map;
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

  @Test
  void anInnerClassIsMadeInsideAnInstanceOfItsOuterClass() throws Exception {
    var outer = "Lio/mutastic/engine/fixture/Visibility;";
    assertThat(receiver(Visibility.Inner.class, "onInstance", ELSEWHERE))
        .contains("()V>(" + outer + ")V");
    assertThat(receiver(Visibility.Sized.class, "onInstance", ELSEWHERE))
        .contains("()V>(" + outer + "I)V");
    assertThat(receiver(Visibility.Inner.Deeper.class, "onInstance", ELSEWHERE))
        .contains("()V>(" + outer + ")V>(Lio/mutastic/engine/fixture/Visibility$Inner;)V");
    // The outer class needs a String, so the inner one can't be made either.
    assertThat(receiver(Visibility.Named.Part.class, "onInstance", ELSEWHERE)).isEmpty();
  }

  private static Optional<String> receiver(Class<?> type, String method, String testClass)
      throws Exception {
    return Access.receiver(models(type), mutant(type, method), testClass);
  }

  private static boolean callable(Class<?> type, String method, String testClass) throws Exception {
    return Access.callableFrom(models(type), mutant(type, method), testClass);
  }

  /// `type`'s class and the classes it's nested in, by binary name.
  private static Map<String, ClassModel> models(Class<?> type) throws Exception {
    var models = new HashMap<String, ClassModel>();
    for (Class<?> each = type; each != null; each = each.getEnclosingClass()) {
      models.put(each.getName(), MutationsTest.model(each));
    }
    return models;
  }

  private static Mutant mutant(Class<?> type, String method) throws Exception {
    var model = MutationsTest.model(type);
    var descriptor =
        model.methods().stream()
            .filter(found -> found.methodName().equalsString(method))
            .findFirst()
            .orElseThrow()
            .methodType()
            .stringValue();
    return new Mutant(
        model.thisClass().asInternalName(),
        method,
        descriptor,
        0,
        1,
        BuiltIn.MATH,
        "IADD",
        "ISUB",
        Optional.empty());
  }
}
