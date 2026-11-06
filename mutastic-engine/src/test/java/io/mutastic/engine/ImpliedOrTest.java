package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.engine.fixture.Implied;
import java.lang.classfile.ClassFile;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/// Which `||` tests are skipped as covered by the next one. That each skipped one really is
/// equivalent, CardTruthTest checks by running it.
class ImpliedOrTest {
  private static final String REASON =
      "equivalent: the next test of the `||` already covers its case";

  @Test
  void skipsOnlyATestTheNextOneCovers() throws Exception {
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    var bytes = Implied.class.getResourceAsStream("Implied.class").readAllBytes();
    var skipped =
        mutations.find(ClassFile.of().parse(bytes)).stream()
            .filter(mutant -> mutant.skipped().filter(REASON::equals).isPresent())
            .toList();

    assertThat(skipped)
        .extracting(Mutant::methodName, Mutant::operator, Mutant::replacement)
        .containsExactlyInAnyOrder(
            Tuple.tuple("byACall", BuiltIn.REMOVE_CONDITIONAL, "never jump"),
            Tuple.tuple("byAComparison", BuiltIn.REMOVE_CONDITIONAL, "never jump"),
            Tuple.tuple("byAComparison", BuiltIn.BOUNDARY, "IF_ICMPGE"),
            Tuple.tuple("byAComparison", BuiltIn.OTHER_COMPARISON, "IF_ICMPEQ"),
            Tuple.tuple("atTheSameEdge", BuiltIn.REMOVE_CONDITIONAL, "never jump"));
  }
}
