package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.api.Project;
import io.mutastic.api.TestSelection;
import io.mutastic.engine.fixture.Pricing;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IgnoredTest {
  private static final String LINE = "if (total >= 100) {";

  @Test
  void anIgnoreOnItsLineSkipsTheMutantWithTheReason() throws Exception {
    var mutant = boundary();
    var ignored = ignored(new Ignore(mutant.id(), LINE, "pricing is checked by hand"));

    var applied = ignored.apply(List.of(mutant), false);

    assertThat(applied.getFirst().skipped()).contains("user: pricing is checked by hand (ignored)");
    assertThat(ignored.stale()).isEmpty();
  }

  @Test
  void anIgnoreWhoseLineChangedNoLongerHoldsAndItsMutantRuns() throws Exception {
    var mutant = boundary();
    var ignore = new Ignore(mutant.id(), "if (total > 100) {", "old");
    var ignored = ignored(ignore);

    var applied = ignored.apply(List.of(mutant), false);

    assertThat(applied.getFirst().skipped()).isEmpty();
    assertThat(ignored.stale()).containsExactly(ignore);
  }

  @Test
  void anIgnoreMatchingNoMutantIsStaleOnlyWhenEveryMutantIsThere() throws Exception {
    var gone = new Ignore("m00000000", "gone();", "removed");

    var partly = ignored(gone);
    partly.apply(List.of(boundary()), false);
    var wholly = ignored(gone);
    var applied = wholly.apply(List.of(boundary()), true);

    assertThat(partly.stale()).isEmpty();
    assertThat(wholly.stale()).containsExactly(gone);
    assertThat(applied).containsExactly(boundary());
  }

  @Test
  void anIgnoreOverridesAridAndRulesButNotGeneratedOrEquivalent() throws Exception {
    var arid = boundary().skippedFor("arid: only writes output");
    var ruled = boundary().skippedFor("user: slow (rule `skip: a.B`)");
    var generated = boundary().skippedFor("generated: part of a switch on a String");

    for (Mutant mutant : List.of(arid, ruled)) {
      assertThat(ignored(new Ignore(mutant.id(), LINE, "mine")).apply(List.of(mutant), false))
          .singleElement()
          .satisfies(found -> assertThat(found.skipped()).contains("user: mine (ignored)"));
    }
    assertThat(ignored(new Ignore(generated.id(), LINE, "mine")).apply(List.of(generated), false))
        .containsExactly(generated);
  }

  @Test
  void aMutantWithNoSourceOrNoSuchLineCannotBeIgnored() throws Exception {
    var elsewhere =
        new Mutant(
            "com/example/Missing",
            "x",
            "()I",
            0,
            3,
            BuiltIn.RETURN_VALUE,
            "IRETURN",
            "return 0",
            Optional.empty());
    var pastTheEnd =
        new Mutant(
            "io/mutastic/engine/fixture/Pricing",
            "discount",
            "(I)I",
            0,
            100_000,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());

    for (Mutant mutant : List.of(elsewhere, pastTheEnd)) {
      var ignored = ignored(new Ignore(mutant.id(), LINE, "mine"));
      assertThat(ignored.apply(List.of(mutant), false)).containsExactly(mutant);
      assertThat(ignored.stale()).hasSize(1);
    }
  }

  private static Mutant boundary() throws Exception {
    return new Mutations(ClassFile.of())
        .find(MutationsTest.model(Pricing.class)).stream()
            .filter(mutant -> mutant.methodName().equals("discount"))
            .filter(mutant -> mutant.operator() == BuiltIn.BOUNDARY)
            .findFirst()
            .orElseThrow();
  }

  private static Ignored ignored(Ignore... ignores) throws Exception {
    ClassModel model = MutationsTest.model(Pricing.class);
    return new Ignored(
        List.of(ignores),
        Map.of("io.mutastic.engine.fixture.Pricing", model),
        project(List.of(Path.of("src/test/java"))));
  }

  static Project project(List<Path> sources) {
    return new Project(
        "pricing",
        Path.of("java"),
        List.of(),
        Path.of("."),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        sources,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        TestSelection.ALL,
        Map.of());
  }
}
