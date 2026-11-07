package io.mutastic.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.ClassMutator.Member;
import io.mutastic.api.Mutant;
import io.mutastic.plugins.AnnotationRemoval.Security;
import io.mutastic.plugins.AnnotationRemoval.Validation;
import java.lang.annotation.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Which annotations are found, and that after a removal reflection finds none of the copies javac
/// made and every other annotation it did. By name in a package of its own, as the real ones are:
/// these tests need no library.
class AnnotationRemovalTest {
  private static final String NOT_NULL = "Ljakarta/validation/constraints/NotNull;";
  private static final String SIZE = "Ljakarta/validation/constraints/Size;";
  private static final String ANNOTATIONS =
      """
      package jakarta.validation.constraints;
      import java.lang.annotation.*;
      @Retention(RetentionPolicy.RUNTIME)
      @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE,
          ElementType.TYPE})
      public @interface %s {}
      """;
  private static final String FIXTURE =
      """
      package fixture;
      import jakarta.validation.constraints.*;
      import java.util.List;
      @Size
      @Deprecated
      public class Order implements Comparable<Order> {
        @NotNull @Deprecated public String name;
        @NotNull public int count;
        @com.acme.NotNull public String theirs;
        public List<@NotNull String> items;
        @NotNull public String[] tags;
        @NotNull public String label() { return name; }
        public void save(@NotNull String to, @Size String memo) {}
        @Override @Size public int compareTo(Order other) { return 0; }
        public abstract static class Base { @NotNull public abstract Object made(); }
        public static class Made extends Base {
          @Override @NotNull public String made() { return ""; }
        }
        public record Customer(@NotNull @Size String name, String email) {}
      }
      """;

  private static Compiled compiled;
  private final Validation validation = new Validation();

  @BeforeAll
  static void compile() throws Exception {
    compiled =
        Compiled.of(
            Map.of(
                "jakarta/validation/constraints/NotNull.java",
                ANNOTATIONS.formatted("NotNull"),
                "jakarta/validation/constraints/Size.java",
                ANNOTATIONS.formatted("Size"),
                "com/acme/NotNull.java",
                """
                package com.acme;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) public @interface NotNull {}
                """,
                "fixture/Order.java",
                FIXTURE));
  }

  @Test
  void findsEachPlaceAnAnnotationCanBeAndOnlyTheFamilys() {
    var changes = validation.changes(compiled.model("fixture.Order"));

    assertThat(changes)
        .extracting(change -> change.member().name() + " " + change.label())
        .containsExactlyInAnyOrder(
            "<class> remove " + SIZE,
            "<field name> remove " + NOT_NULL,
            "<field count> remove " + NOT_NULL,
            "<field tags> remove " + NOT_NULL,
            "label remove " + NOT_NULL,
            "save remove " + NOT_NULL + " from parameter 0",
            "save remove " + SIZE + " from parameter 1",
            "compareTo remove " + SIZE);
  }

  @Test
  void aNestedTypesAnnotationIsntARemovableOne() {
    assertThat(validation.changes(compiled.model("fixture.Order")))
        .noneMatch(change -> change.member().name().equals("<field items>"));
  }

  @Test
  void removesFromAFieldAndItsTypeButNotTheOtherAnnotations() throws Exception {
    var name = fieldOf("name", NOT_NULL);

    assertThat(annotationsOn(name)).containsExactly("Deprecated");
    assertThat(name.getAnnotatedType().getAnnotations()).isEmpty();
  }

  private java.lang.reflect.Field fieldOf(String field, String descriptor) throws Exception {
    var model = compiled.model("fixture.Order");
    var member =
        Member.ofField(
            field,
            model.fields().stream()
                .filter(each -> each.fieldName().equalsString(field))
                .findFirst()
                .orElseThrow()
                .fieldType()
                .stringValue());
    var bytes = validation.write(model, member, "remove " + descriptor);
    assertThat(ClassFile.of().verify(bytes)).isEmpty();
    return compiled.load("fixture.Order", bytes).getField(field);
  }

  @Test
  void anArraysAnnotationGoesFromItsElementsToo() throws Exception {
    var tags = fieldOf("tags", NOT_NULL);

    assertThat(annotationsOn(tags)).isEmpty();
    var type = (java.lang.reflect.AnnotatedArrayType) tags.getAnnotatedType();
    assertThat(type.getAnnotatedGenericComponentType().getAnnotations()).isEmpty();
  }

  @Test
  void removesFromAClass() throws Exception {
    var model = compiled.model("fixture.Order");

    var loaded =
        compiled.load("fixture.Order", validation.write(model, Member.ofClass(), "remove " + SIZE));

    assertThat(annotationsOn(loaded)).containsExactly("Deprecated");
  }

  @Test
  void removesFromAMethodAndItsReturnType() throws Exception {
    var model = compiled.model("fixture.Order");
    var label = new Member("label", "()Ljava/lang/String;");

    var method =
        compiled
            .load("fixture.Order", validation.write(model, label, "remove " + NOT_NULL))
            .getMethod("label");

    assertThat(annotationsOn(method)).isEmpty();
    assertThat(method.getAnnotatedReturnType().getAnnotations()).isEmpty();
  }

  @Test
  void removesFromOneParameterAndLeavesTheOther() throws Exception {
    var model = compiled.model("fixture.Order");
    var save = new Member("save", "(Ljava/lang/String;Ljava/lang/String;)V");

    var method =
        compiled
            .load(
                "fixture.Order",
                validation.write(model, save, "remove " + NOT_NULL + " from parameter 0"))
            .getMethod("save", String.class, String.class);

    assertThat(annotationsOn(method.getParameters()[0])).isEmpty();
    assertThat(method.getParameters()[0].getAnnotatedType().getAnnotations()).isEmpty();
    assertThat(annotationsOn(method.getParameters()[1])).containsExactly("Size");
  }

  /// Which of a method's bridges a caller gets depends on its static type: both must lose it.
  @Test
  void removesFromAMethodsBridgeToo() throws Exception {
    var made = compiled.model("fixture.Order$Made");
    assertThat(validation.changes(made)).hasSize(1);

    var loaded =
        compiled.load(
            "fixture.Order$Made",
            validation.write(
                made, new Member("made", "()Ljava/lang/String;"), "remove " + NOT_NULL));

    assertThat(loaded.getDeclaredMethods()).hasSize(2);
    assertThat(loaded.getDeclaredMethods())
        .allSatisfy(method -> assertThat(annotationsOn(method)).isEmpty());
  }

  /// Written once on a record's header, an annotation is copied to the field, the accessor and the
  /// constructor's parameter: one mutant, and it takes every copy.
  @Test
  void aRecordComponentsAnnotationIsOneChangeToEveryCopy() throws Exception {
    var customer = compiled.model("fixture.Order$Customer");

    assertThat(validation.changes(customer))
        .extracting(change -> change.member().name() + " " + change.label())
        .containsExactlyInAnyOrder(
            "<field name> remove " + NOT_NULL, "<field name> remove " + SIZE);

    var name = Member.ofField("name", "Ljava/lang/String;");
    var bytes = validation.write(customer, name, "remove " + NOT_NULL);
    assertThat(ClassFile.of().verify(bytes)).isEmpty();
    var loaded = compiled.load("fixture.Order$Customer", bytes);

    assertThat(annotationsOn(loaded.getDeclaredField("name"))).containsExactly("Size");
    assertThat(annotationsOn(loaded.getMethod("name"))).containsExactly("Size");
    var constructor = loaded.getDeclaredConstructor(String.class, String.class);
    assertThat(annotationsOn(constructor.getParameters()[0])).containsExactly("Size");
    assertThat(annotationsOn(loaded.getRecordComponents()[0].getAccessor()))
        .containsExactly("Size");
    assertThat(loaded.getRecordComponents()[0].getAnnotatedType().getAnnotations())
        .extracting(annotation -> annotation.annotationType().getSimpleName())
        .containsExactly("Size");
  }

  @Test
  void aPrimitiveCantBeNullSoItsNotNullIsEquivalent() {
    var changes = validation.changes(compiled.model("fixture.Order"));

    assertThat(changes)
        .filteredOn(change -> change.member().name().equals("<field count>"))
        .singleElement()
        .satisfies(
            change ->
                assertThat(change.skipped().orElseThrow()).startsWith("equivalent: a primitive"));
    assertThat(changes)
        .filteredOn(change -> change.member().name().equals("<field name>"))
        .singleElement()
        .satisfies(change -> assertThat(change.skipped()).isEmpty());
  }

  @Test
  void aChangeSaysWhatMarksItsLine() {
    assertThat(validation.changes(compiled.model("fixture.Order")))
        .filteredOn(change -> change.member().name().equals("<class>"))
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.sourceText()).isEqualTo("@Size");
              assertThat(change.original()).isEqualTo("@Size");
            });
  }

  @Test
  void aListContainerIsNamedAsTheSourceDoesAndMarkedByItsAnnotation() {
    assertThat(AnnotationFamily.simpleName("Ljakarta/validation/constraints/Size$List;"))
        .isEqualTo("Size.List");
  }

  @Test
  void aLabelThatIsntItsOwnIsRefused() {
    assertThatThrownBy(
            () -> validation.write(compiled.model("fixture.Order"), Member.ofClass(), "something"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCardNamesWhatTheAnnotationIsOn() {
    assertThat(words(validation, Member.ofField("name", "I"), "remove " + NOT_NULL).headline())
        .isEqualTo("no test needs `@NotNull` on `name`");
    assertThat(words(validation, Member.ofClass(), "remove " + NOT_NULL).headline())
        .isEqualTo("no test needs `@NotNull` on `Order.Line`");
    assertThat(words(validation, new Member("save", "()V"), "remove " + NOT_NULL).headline())
        .isEqualTo("no test needs `@NotNull` on `save()`");
    assertThat(
            words(validation, new Member("save", "()V"), "remove " + NOT_NULL + " from parameter 1")
                .headline())
        .isEqualTo("no test needs `@NotNull` on parameter 2 of `save()`");
    assertThat(words(new Security(), new Member("pay", "()V"), "remove " + NOT_NULL).help())
        .isEqualTo(
            "add a test that reaches `pay()` as a caller @NotNull must refuse, and assert it's refused");
    assertThat(words(validation, Member.ofField("name", "I"), "remove " + NOT_NULL).changed())
        .isEqualTo("@NotNull removed");
  }

  @Test
  void eachOperatorSaysItsNameAndWhatItDoes() {
    assertThat(List.of(validation.id(), new Security().id()))
        .containsExactly("annotations/remove-validation", "annotations/remove-security");
    assertThat(validation.explanation()).contains("Bean Validation");
    assertThat(new Security().explanation()).contains("security");
    assertThat(validation.reach()).isEqualTo(ClassMutator.Reach.EVERY_TEST);
    assertThat(validation.onCalls()).isFalse();
  }

  private static io.mutastic.api.Mutator.Words words(
      ClassMutator mutator, Member member, String label) {
    return mutator.words(
        new Mutant(
            "fixture/Order$Line",
            member.name(),
            member.descriptor(),
            0,
            1,
            mutator,
            "@NotNull",
            label,
            Optional.empty()));
  }

  private static List<String> annotationsOn(AnnotatedElement element) {
    return Arrays.stream(element.getAnnotations())
        .map(Annotation::annotationType)
        .map(Class::getSimpleName)
        .toList();
  }
}
