package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Mutant;
import io.mutastic.api.Operator.Tier;
import io.mutastic.api.Policy;
import io.mutastic.engine.fixture.Pipeline;
import java.lang.classfile.ClassFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Rules files: what they may say, what they may not, and the rewrites they describe, on a fluent
/// type of the kind a library has.
class CallRulesTest {
  private static final String RULES =
      """
      # A plugin's rules
      - id: acme/remove-limit
        explanation: The limit is lifted.
        tier: stronger
        owners: io.mutastic.engine.fixture.Pipeline
        methods: first
        rewrite: call first(J) with Long.MAX_VALUE
        source: first(Long.MAX_VALUE)
        changed: limit lifted
        headline: no test has more items than `.{name}(…)` in {method} lets through
        help: add a test with more items than the limit
      - id: acme/skip-map
        explanation: The mapping is skipped.
        owners: io.mutastic.engine.fixture.Pipeline
        methods: map
        rewrite: call map(Ljava/util/function/Function;) with java.util.function.Function.identity()
        headline: no test checks what `.{name}(…)` does in {method}
        headline.map: no test checks the mapped values in {method}
        help: assert the mapped values
      """;

  @Test
  void aCallRewriteCallsAnotherMethodWithTheRulesArguments() throws Exception {
    var rules = CallRules.parse(RULES, "acme.yaml");
    var mutations =
        new Mutations(
            ClassFile.of(), Policy.NONE.withOperators(List.of("stronger"), List.copyOf(rules)));
    var model = MutationsTest.model(Pipeline.class);
    var found = mutations.find(model);

    var lifted = only(found, "acme/remove-limit");
    var skipped = only(found, "acme/skip-map");

    assertThat(rules.getFirst().tier()).isEqualTo(Tier.STRONGER);
    assertThat(lifted.replacement()).isEqualTo("first(Long.MAX_VALUE)");
    assertThat(run(mutations, model, lifted)).isEqualTo(List.of(2, 4, 6)); // was [2, 4]
    assertThat(run(mutations, model, skipped)).isEqualTo(List.of(1, 2)); // was [2, 4]
    var words = rules.get(1).words(skipped);
    assertThat(words.headline()).isEqualTo("no test checks the mapped values in topTwoDoubled()");
    assertThat(words.changed()).isEqualTo("changed");
    assertThat(rules.getFirst().words(lifted).headline())
        .isEqualTo("no test has more items than `.first(…)` in topTwoDoubled() lets through");
  }

  @Test
  void aFileThatSaysSomethingElseIsAnErrorThatNamesTheLine() {
    assertThatThrownBy(() -> CallRules.parse("id: acme/x\n", "a.yaml"))
        .hasMessage("a.yaml:1: expected a rule, `- id: …`");
    assertThatThrownBy(() -> CallRules.parse("- id: acme/x\n  colour: red\n", "a.yaml"))
        .hasMessageStartingWith("a.yaml:2: expected one of");
    assertThatThrownBy(() -> CallRules.parse("- id: acme/x\n  id: acme/y\n", "a.yaml"))
        .hasMessage("a.yaml:2: id is given twice");
    assertThatThrownBy(() -> CallRules.parse("- id: acme/x\n", "a.yaml"))
        .hasMessage("a.yaml:1: the rule needs a explanation");
    assertThatThrownBy(() -> CallRules.parse(rule("Acme", "remove", "defaults"), "a.yaml"))
        .hasMessage("a.yaml:1: an id is `namespace/name`, lower case: Acme");
    assertThatThrownBy(() -> CallRules.parse(rule("acme/x", "delete", "defaults"), "a.yaml"))
        .hasMessageStartingWith("a.yaml:1: a rewrite is `remove`");
    assertThatThrownBy(() -> CallRules.parse(rule("acme/x", "remove", "loud"), "a.yaml"))
        .hasMessage("a.yaml:1: a tier is defaults or stronger, not loud");
    assertThat(CallRules.parse(rule("acme/x", "rename a->b, c->d", "defaults"), "a.yaml"))
        .singleElement()
        .satisfies(each -> assertThat(each.rewrite()).isEqualTo("rename a->b, c->d"));
  }

  @Test
  void quotedValuesAndCommentsAreRead() {
    var rules =
        CallRules.parse(
            """
            # rules

            - id: "acme/x"
              explanation: 'Quoted.'
              owners: a.B
              methods: m
              rewrite: remove
              headline: h
              help: h
            """,
            "a.yaml");

    assertThat(rules).singleElement().satisfies(each -> assertThat(each.id()).isEqualTo("acme/x"));
    assertThat(rules.getFirst().explanation()).isEqualTo("Quoted.");
  }

  private static String rule(String id, String rewrite, String tier) {
    return "- id: %s\n  explanation: e\n  tier: %s\n  owners: a.B\n  methods: m\n  rewrite: %s\n  headline: h\n  help: h\n"
        .formatted(id, tier, rewrite);
  }

  private static Mutant only(List<Mutant> found, String operator) {
    return found.stream()
        .filter(mutant -> mutant.operator().id().equals(operator))
        .filter(mutant -> mutant.methodName().equals("topTwoDoubled"))
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> run(
      Mutations mutations, java.lang.classfile.ClassModel model, Mutant mutant) throws Exception {
    assertThat(mutant.skipped()).as(mutant.toString()).isEmpty();
    var bytes = mutations.apply(model, mutant);
    var type =
        new MutationsTest.SingleClassLoader(Pipeline.class.getName(), bytes)
            .loadClass(Pipeline.class.getName());
    return (List<Integer>)
        type.getMethod("topTwoDoubled", List.class).invoke(null, List.of(1, 2, 3));
  }
}
