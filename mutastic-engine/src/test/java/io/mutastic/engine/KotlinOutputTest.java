package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// What the engine makes of real kotlinc output, read from class files kept in
/// `src/test/resources/kotlin`: some of `samples/kotlin-shapes`' main code, compiled once by
/// kotlinc 2.2.21, the version the sample names, with `-jvm-target 17`, the stdlib and
/// kotlinx-coroutines-core 1.10.2. After changing that code, compile it again (kotlinc, or the
/// sample's `./gradlew compileKotlin`) and copy the classes named here. They're parsed, never
/// compiled, so these tests cost a few parses: what a run does with the code is checked end to end,
/// by `KotlinShapesTest` in `mutastic-cli`.
class KotlinOutputTest {
  private static final List<String> CLASSES =
      List.of(
          "CoroutinesKt",
          "CoroutinesKt$doubles$1",
          "CoroutinesKt$nextOf$1",
          "CoroutinesKt$sumTo$1",
          "ChecksKt",
          "Late",
          "Holder",
          "InlinesKt",
          "HelpersKt");

  /// Each test finds its own, for its own classes, not shared from a `@BeforeAll`: a mutant is only
  /// run against the tests that reach its line, and what a `@BeforeAll` reaches is no test's.
  private final Map<String, List<Mutant>> found = new java.util.HashMap<>();

  private @org.jspecify.annotations.Nullable Mutations mutations;

  private List<Mutant> mutantsOf(String className) {
    if (mutations == null) {
      var models = CLASSES.stream().map(KotlinOutputTest::model).toList();
      var policy = Policy.NONE.withOperators(List.of("all"), Policy.NONE.mutators());
      mutations = new Mutations(ClassFile.of(), policy);
      mutations.prepare(models);
    }
    var ready = mutations;
    return found.computeIfAbsent(className, name -> ready.find(model(name)));
  }

  private static ClassModel model(String name) {
    try (var in =
        Objects.requireNonNull(
            KotlinOutputTest.class.getResourceAsStream("/kotlin/" + name + ".class"), name)) {
      return ClassFile.of().parse(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Stream<Mutant> of(String className, String method) {
    return mutantsOf(className).stream().filter(mutant -> mutant.methodName().equals(method));
  }

  private List<Mutant> running(String className, String method) {
    return of(className, method).filter(mutant -> mutant.skipped().isEmpty()).toList();
  }

  private static boolean isState(Mutant mutant) {
    return mutant.original().contains("throwOnFailure")
        || mutant.original().startsWith("IF_ACMP")
        || mutant.original().equals("TABLESWITCH")
        || mutant.replacement().contains("label")
        || mutant.replacement().contains("$0");
  }

  @Test
  void aSuspendFunctionsStateIsSkippedAndItsOwnCodeIsNot() {
    for (var method : List.of("nextOf", "sumTo", "waits")) {
      assertThat(running("CoroutinesKt", method)).as(method).noneMatch(KotlinOutputTest::isState);
      assertThat(running("CoroutinesKt", method))
          .as(method)
          .allSatisfy(mutant -> assertThat(mutant.line()).isPositive());
    }
    assertThat(of("CoroutinesKt", "nextOf"))
        .anyMatch(mutant -> mutant.skipped().equals(Optional.of(Coroutines.REASON)));
    // Before its first line, kotlinc's way in has no line of its own: it's on the function's first.
    assertThat(of("CoroutinesKt", "nextOf").toList())
        .allSatisfy(mutant -> assertThat(mutant.line()).isPositive());
    assertThat(running("CoroutinesKt", "nextOf"))
        .anyMatch(mutant -> mutant.original().equals("IADD"))
        .anyMatch(mutant -> mutant.original().equals("ARETURN"));
    assertThat(running("CoroutinesKt", "sumTo"))
        .anyMatch(mutant -> mutant.original().startsWith("IF_ICMP"));
  }

  @Test
  void aSuspendingLambdasStateIsSkippedAndItsOwnCodeIsNot() {
    var lambda = running("CoroutinesKt$doubles$1", "invokeSuspend");

    assertThat(lambda).noneMatch(KotlinOutputTest::isState);
    assertThat(lambda).anyMatch(mutant -> mutant.original().equals("IMUL"));
    assertThat(of("CoroutinesKt$doubles$1", "invokeSuspend"))
        .anyMatch(
            mutant -> mutant.original().contains("throwOnFailure") && mutant.skipped().isPresent());
  }

  @Test
  void aNotNullAssertionIsThePersonsUnlessTheNextStepThrowsTheSame() {
    for (var method : List.of("keep", "asText")) {
      assertThat(running("ChecksKt", method))
          .as(method)
          .anyMatch(mutant -> mutant.original().contains("Intrinsics.checkNotNull("));
    }
    for (var method : List.of("lengthOf", "sizeOf", "countOf")) {
      assertThat(of("ChecksKt", method))
          .as(method)
          .filteredOn(mutant -> mutant.original().contains("Intrinsics.checkNotNull("))
          .isNotEmpty()
          .allSatisfy(
              mutant ->
                  assertThat(mutant.skipped())
                      .contains("equivalent: the next step throws the same NullPointerException"));
    }
    assertThat(of("ChecksKt", "keep"))
        .filteredOn(mutant -> mutant.original().contains("checkNotNullParameter"))
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isPresent());
  }

  @Test
  void aLateinitsGetterAndCheckAreKotlincsAndWhatReadsItIsNot() {
    assertThat(of("Late", "getName").toList())
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isPresent());
    assertThat(of("Late", "getName"))
        .anyMatch(
            mutant ->
                mutant
                    .skipped()
                    .equals(
                        Optional.of(
                            "generated: kotlinc wrote it: a `lateinit` property's getter")));
    assertThat(of("Late", "innerLength"))
        .filteredOn(mutant -> mutant.original().startsWith("IFNONNULL"))
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.skipped())
                    .contains(
                        "generated: kotlinc's check that a `lateinit` property has been set"));
    assertThat(running("Late", "innerLength"))
        .anyMatch(mutant -> mutant.original().equals("IRETURN"));
    assertThat(running("Late", "nameLength")).isNotEmpty();
  }

  @Test
  void codeCopiedInIsWhereItReallyIs() {
    assertThat(of("InlinesKt", "evens"))
        .filteredOn(
            mutant -> mutant.inlinedFrom().filter(file -> file.startsWith("kotlin/")).isPresent())
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.skipped().orElseThrow())
                    .startsWith("generated: kotlinc copied this from kotlin."));
    assertThat(running("InlinesKt", "usesBump"))
        .anyMatch(
            mutant ->
                mutant.inlinedFrom().equals(Optional.of("com/example/shapes/Helpers.kt"))
                    && mutant.line() == 4);
    assertThat(running("InlinesKt", "usesTwice")).anyMatch(mutant -> mutant.line() == 3);
    assertThat(of("InlinesKt", "twice").filter(mutant -> mutant.original().equals("IADD")))
        .allSatisfy(
            mutant ->
                assertThat(mutant.skipped().orElseThrow())
                    .endsWith("the mutants on its copies are the ones that count"));
  }
}
