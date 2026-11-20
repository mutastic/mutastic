package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Filter;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import io.mutastic.api.Skip;
import io.mutastic.engine.fixture.Pricing;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// A plugin's filter can skip one mutant, not only a whole method: [Filter#skipMutant].
class MutantFiltersTest {
  private static final String RECORD = "io/mutastic/engine/fixture/Pricing.record";

  /// Only the mutants of the one call are skipped, with the filter's reason; the rest of the
  /// method's still run.
  @Test
  void aFilterSkipsOneMutantAndLeavesTheRestOfItsMethod() throws Exception {
    var found = find(new Policy(List.of(), List.of(new NoAuditCalls(Skip.Kind.USER, "audit"))));

    var checkout = found.stream().filter(mutant -> mutant.methodName().equals("checkout")).toList();
    assertThat(checkout)
        .filteredOn(mutant -> mutant.original().contains(RECORD))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).contains("user: audit"));
    assertThat(checkout)
        .filteredOn(mutant -> !mutant.original().contains(RECORD))
        .isNotEmpty()
        .allSatisfy(
            mutant -> assertThat(mutant.skipped()).isNotEqualTo(Optional.of("user: audit")));
    assertThat(found)
        .filteredOn(mutant -> !mutant.original().contains(RECORD))
        .noneMatch(mutant -> mutant.skipped().equals(Optional.of("user: audit")));
  }

  /// What mutastic's own checks skip keeps their reason: the filter is asked last, of what's left.
  @Test
  void aSkipBeforeTheFiltersComesFirst() throws Exception {
    var asked = new ArrayList<Mutant>();
    var everything =
        new Filter() {
          @Override
          public String id() {
            return "acme:everything";
          }

          @Override
          public Optional<Skip> skip(ClassModel owner, MethodModel method) {
            return Optional.empty();
          }

          @Override
          public Optional<Skip> skipMutant(Mutator.Site site, Mutant mutant) {
            asked.add(mutant);
            return Optional.of(new Skip(Skip.Kind.USER, "all of it"));
          }
        };

    var found = find(new Policy(List.of(), List.of(everything)));

    assertThat(asked).isNotEmpty().allMatch(mutant -> mutant.skipped().isEmpty());
    assertThat(found)
        .filteredOn(mutant -> mutant.original().contains("PrintStream"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).contains("arid: only writes output"));
  }

  /// A method a filter skips as a whole keeps that reason, and its mutants aren't asked about.
  @Test
  void aMethodSkippedAsAWholeIsNotAskedAboutAgain() throws Exception {
    var whole =
        new Filter() {
          @Override
          public String id() {
            return "acme:no-checkout";
          }

          @Override
          public Optional<Skip> skip(ClassModel owner, MethodModel method) {
            return method.methodName().equalsString("checkout")
                ? Optional.of(new Skip(Skip.Kind.USER, "checkout is covered elsewhere"))
                : Optional.empty();
          }
        };

    var found =
        find(new Policy(List.of(), List.of(whole, new NoAuditCalls(Skip.Kind.USER, "audit"))));

    assertThat(found)
        .filteredOn(
            mutant -> mutant.methodName().equals("checkout") && mutant.original().contains(RECORD))
        .isNotEmpty()
        .allSatisfy(
            mutant -> assertThat(mutant.skipped()).contains("user: checkout is covered elsewhere"));
  }

  /// An arid skip of one mutant is brought back by a `mutate:` rule, as mutastic's own are.
  @Test
  void aMutateRuleBringsBackAnAridSkipOfOneMutant() throws Exception {
    var arid = new NoAuditCalls(Skip.Kind.ARID, "only audits");

    assertThat(find(new Policy(List.of(), List.of(arid))))
        .anyMatch(mutant -> mutant.skipped().equals(Optional.of("arid: only audits")));
    var kept =
        new Policy(
            List.of(new Rule(true, "io.mutastic.engine.fixture.Pricing#checkout", "audits matter")),
            List.of(arid));
    assertThat(find(kept))
        .filteredOn(
            mutant -> mutant.methodName().equals("checkout") && mutant.original().contains(RECORD))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  /// Every skip says why: one with no reason stops the run, naming the filter.
  @Test
  void aSkipWithoutAReasonStopsTheRun() throws Exception {
    var policy = new Policy(List.of(), List.of(new NoAuditCalls(Skip.Kind.USER, " ")));

    assertThatThrownBy(() -> find(policy))
        .isInstanceOf(Engine.SkipWithoutReason.class)
        .hasMessageStartingWith("the filter acme:no-audit-calls skipped m")
        .hasMessageContaining("without a reason");
  }

  private static List<Mutant> find(Policy policy) throws Exception {
    var mutations = new Mutations(ClassFile.of(), policy);
    var model = MutationsTest.model(Pricing.class);
    mutations.prepare(List.of(model));
    return mutations.find(model);
  }

  /// Skips the mutants of a call to `Pricing.record`, as a team's plugin would skip its audit
  /// calls.
  private record NoAuditCalls(Skip.Kind kind, String reason) implements Filter {
    @Override
    public String id() {
      return "acme:no-audit-calls";
    }

    @Override
    public Optional<Skip> skip(ClassModel owner, MethodModel method) {
      return Optional.empty();
    }

    @Override
    public Optional<Skip> skipMutant(Mutator.Site site, Mutant mutant) {
      return site.instruction() instanceof InvokeInstruction call
              && call.owner().asInternalName().equals("io/mutastic/engine/fixture/Pricing")
              && call.name().equalsString("record")
          ? Optional.of(new Skip(kind, reason))
          : Optional.empty();
    }
  }
}
