package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.AridRule;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/// A `mutate:` rule's `arid:` list, and `@DoMutate`'s `arid`, bring back only the skips of the arid
/// rules they name: each other skip, arid or not, stays.
class AridSelectorTest {
  private static final String SHAPES = "kept/Arid.java";

  @Test
  void aMutateRuleWithAnAridListBringsBackOnlyItsRules() throws Exception {
    var source = read(SHAPES);
    var plain = found(source, Policy.NONE);
    for (var rule : AridRule.values()) {
      var listed = new Rule(true, source.className(), "worth a test", List.of(rule.id()));

      assertOnly(rule.id(), Set.of(rule), plain, found(source, policy(listed)));
    }
  }

  @Test
  void doMutateWithAnAridListBringsBackOnlyItsRules() throws Exception {
    var source = read(SHAPES);
    var annotated =
        new Javac.Source(
            source.className(),
            source
                    .text()
                    .replaceFirst(
                        "(?m)^(class Arid\\b)",
                        "@DoMutate(arid = {\"mutastic:waits\", \"mutastic:debug-representations\"})"
                            + "\n$1")
                + "\n@interface DoMutate { String[] arid() default {}; }\n");

    assertOnly(
        "@DoMutate",
        Set.of(AridRule.WAITS, AridRule.DEBUG_REPRESENTATIONS),
        found(source, Policy.NONE),
        found(annotated, Policy.NONE));
  }

  /// Two rules over the same code bring back what either names; one with no list, everything.
  @Test
  void rulesOverTheSameCodeAddUp() throws Exception {
    var source = read(SHAPES);
    var plain = found(source, Policy.NONE);
    var waits = new Rule(true, source.className(), "a", List.of("mutastic:waits"));
    var output = new Rule(true, source.className() + "#*", "b", List.of("mutastic:output"));
    var all = new Rule(true, source.className() + "#*", "c");

    assertOnly(
        "two lists",
        Set.of(AridRule.WAITS, AridRule.OUTPUT),
        plain,
        found(source, policy(waits, output)));
    assertOnly(
        "a list and none", Set.of(AridRule.values()), plain, found(source, policy(waits, all)));
  }

  private static Policy policy(Rule... rules) {
    return new Policy(List.of(rules), List.of());
  }

  /// The mutants of `rules` run, unless another reason skips them; every other skip stays.
  private static void assertOnly(
      String what, Set<AridRule> rules, Map<String, String> plain, Map<String, String> kept) {
    assertThat(kept.keySet()).as(what).isEqualTo(plain.keySet());
    assertThat(plain.values())
        .as(what)
        .anyMatch(why -> AridRule.of(why).filter(rules::contains).isPresent());
    plain.forEach(
        (key, why) -> {
          var rule = AridRule.of(why);
          if (rule.filter(rules::contains).isPresent()) {
            assertThat(kept.get(key)).as("%s %s", what, key).doesNotStartWith("arid:");
          } else {
            assertThat(kept.get(key)).as("%s %s", what, key).isEqualTo(why);
          }
        });
  }

  private static Javac.Source read(String file) throws IOException {
    return Javac.Source.read(
        ClaimFixture.ROOT.resolve(file), file.replace(".java", "").replace('/', '.'));
  }

  /// Each mutant of the fixture's class, by method and id, with why it's skipped ("" if it runs).
  private static Map<String, String> found(Javac.Source source, Policy policy) throws IOException {
    var classes = Javac.compile(List.of(source), List.of(), "-g");
    var mutations =
        new Mutations(
            ClaimFixture.resolvingNested(classes),
            policy.withOperators(List.of("all"), List.of()),
            new Supertypes(name -> ClaimFixture.fixtureOrJdkClass(classes, name)));
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get(source.className())));
    mutations.prepare(List.of(model));
    var found = new TreeMap<String, String>();
    for (Mutant mutant : mutations.find(model)) {
      found.put(mutant.methodName() + "(" + mutant.id() + ")", mutant.skipped().orElse(""));
    }
    return found;
  }
}
