package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/// `@DoMutate` and a `mutate:` rule bring back every mutant mutastic would skip as arid in their
/// code: a whole method a filter judges, and each site in any method, a log call, a wait or a
/// capacity. What's generated, equivalent, unviable or the project's own skip stays skipped.
class KeptAridTest {
  /// Every arid reason, each in the shape javac writes it. Every operator runs on the fixtures,
  /// those that aren't the default's too.
  private static final String SHAPES = "kept/Arid.java";

  /// The fixtures the arid skips' own tests use, in more shapes.
  private static final List<String> FIXTURES =
      List.of(
          SHAPES,
          "arithmetic/ConstantValues.java",
          "exceptions/Throws.java",
          "discards/Discards.java",
          "text/Strings.java",
          "threads/Settings.java",
          "streams/Sizes.java");

  @Test
  void theShapesHaveEveryAridReason() throws Exception {
    var reasons =
        found(read(SHAPES), Policy.NONE).values().stream()
            .filter(why -> why.startsWith("arid:"))
            .distinct()
            .toList();

    assertThat(reasons)
        .containsExactlyInAnyOrder(
            "arid: only writes output",
            "arid: only feeds a log line",
            "arid: only an exception's message",
            "arid: only a failed check's message",
            "arid: only says an assert failed",
            "arid: only sizes a collection's first allocation",
            "arid: only sets when a hash table grows",
            "arid: only sets how long to wait",
            "arid: only waits",
            "arid: only checks the value isn't null",
            "arid: only sizes an array",
            "arid: only limits a split",
            "arid: trivial getter of name",
            "arid: trivial setter of name",
            "arid: `toString()` is a debug representation",
            "arid: `describe()` only serves `toString()`",
            "arid: `hashCode()` may return any value consistent with `equals()`",
            "arid: only sets how many run at once");
  }

  @Test
  void aMutateRuleBringsBackEveryAridSkip() throws Exception {
    for (var file : FIXTURES) {
      var source = read(file);
      var rule = new Rule(true, source.className(), "worth a test");
      var kept = found(source, new Policy(List.of(rule), List.of()));

      assertBroughtBack(file, found(source, Policy.NONE), kept);
    }
  }

  @Test
  void doMutateBringsBackEveryAridSkip() throws Exception {
    for (var file : FIXTURES) {
      var source = read(file);
      var simpleName = source.className().substring(source.className().indexOf('.') + 1);
      var annotated =
          new Javac.Source(
              source.className(),
              source
                      .text()
                      .replaceFirst(
                          "(?m)^((public |final )*class " + simpleName + "\\b)", "@DoMutate\n$1")
                  + "\n@interface DoMutate {}\n");

      assertBroughtBack(file, found(source, Policy.NONE), found(annotated, Policy.NONE));
    }
  }

  /// A `skip:` rule comes before a `mutate:` rule, as an annotation on the code comes before both.
  @Test
  void theProjectsOwnSkipStillSkips() throws Exception {
    var source = read(SHAPES);
    var policy =
        new Policy(
            List.of(
                new Rule(false, "kept.Arid#counted", "counted elsewhere"),
                new Rule(true, "kept.Arid", "worth a test")),
            List.of());
    var found = found(source, policy);

    assertThat(reasonsIn(found, "counted"))
        .contains("user: counted elsewhere (rule `skip: kept.Arid#counted`)")
        .noneMatch(why -> why.isEmpty() || why.startsWith("arid:"));
    assertThat(reasonsIn(found, "tested"))
        .isNotEmpty()
        .containsOnly("user: @DoNotMutate on `tested()`: tested elsewhere");
  }

  /// Every arid mutant runs, unless another reason skips it, and nothing else changes.
  private static void assertBroughtBack(
      String file, Map<String, String> plain, Map<String, String> kept) {
    assertThat(kept.keySet()).as(file).isEqualTo(plain.keySet());
    assertThat(plain.values()).as(file).anyMatch(why -> why.startsWith("arid:"));
    assertThat(kept.values()).as(file).noneMatch(why -> why.startsWith("arid:"));
    plain.forEach(
        (key, why) -> {
          if (!why.startsWith("arid:")) {
            assertThat(kept.get(key)).as("%s %s", file, key).isEqualTo(why);
          }
        });
  }

  private static List<String> reasonsIn(Map<String, String> found, String method) {
    return found.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(method + "("))
        .map(Map.Entry::getValue)
        .toList();
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
