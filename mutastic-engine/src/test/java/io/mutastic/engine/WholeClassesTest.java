package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import io.mutastic.api.Policy;
import io.mutastic.api.Project;
import io.mutastic.api.Rule;
import io.mutastic.api.TestSelection;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// An operator that changes a class as a whole: its mutants' ids and lines, what a project's own
/// say does to them, and which tests they run against.
class WholeClassesTest {
  private static final String SOURCE =
      """
      package fixture;

      @Marked
      public class Order {
        @Marked
        private String name;

        @Marked(1)
        private String code = "c";

        @Marked public String label() {
          return name + code;
        }

        @Marked
        public String shown(
            String prefix) {
          return prefix + name;
        }

        public abstract static class Base {
          @Marked public abstract void run();
        }

        public static final class Inner {
          // @Marked isn't this class's: Inner has none of its own
        }
      }
      """;

  @TempDir Path work;

  /// One change per call of `change`, writing `bytes` back as it found them unless told otherwise.
  private static final class Stub implements ClassMutator {
    private final List<Change> changes;
    private final Reach reach;
    private Optional<RuntimeException> thrown = Optional.empty();
    private Optional<Project> prepared = Optional.empty();

    Stub(Reach reach, Change... changes) {
      this.reach = reach;
      this.changes = List.of(changes);
    }

    @Override
    public String id() {
      return "stub/whole";
    }

    @Override
    public String explanation() {
      return "stub";
    }

    @Override
    public Reach reach() {
      return reach;
    }

    @Override
    public void prepare(Project project) {
      prepared = Optional.of(project);
    }

    @Override
    public List<Change> changes(ClassModel owner) {
      return owner.thisClass().asInternalName().equals("fixture/Order") ? changes : List.of();
    }

    @Override
    public byte[] write(ClassModel owner, Member member, String label) {
      if (thrown.isPresent()) {
        throw thrown.get();
      }
      return ClassFile.of().transformClass(owner, java.lang.classfile.ClassTransform.ACCEPT_ALL);
    }

    @Override
    public Mutator.Words words(Mutant mutant) {
      return new Mutator.Words("changed", "headline", "help");
    }
  }

  private static ClassMutator.Change marked(ClassMutator.Member member) {
    return ClassMutator.Change.of(member, "remove", "@Marked", "@Marked");
  }

  private Project project(List<Path> dependencies) throws Exception {
    var sources = work.resolve("src");
    Files.createDirectories(sources.resolve("fixture"));
    Files.writeString(sources.resolve("fixture/Order.java"), SOURCE);
    return new Project(
        "fixture",
        Path.of("java"),
        List.of(),
        work,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(sources),
        List.of(),
        dependencies,
        List.of(),
        List.of(),
        TestSelection.ALL,
        Map.of());
  }

  private ClassModel model() throws Exception {
    var marked =
        new Javac.Source(
            "fixture.Marked",
            """
            package fixture;
            public @interface Marked { int value() default 0; }
            """);
    var compiled =
        Javac.compile(List.of(new Javac.Source("fixture.Order", SOURCE)), List.of(marked));
    return ClassFile.of().parse(compiled.get("fixture.Order"));
  }

  /// Through `Mutations`, for what it adds: the project's rules, and the check that a mutant loads.
  private List<Mutant> foundByMutations(ClassMutator mutator, Policy policy) throws Exception {
    var mutations =
        new Mutations(
            ClassFile.of(),
            policy.withOperators(List.of("stub"), List.of()).withClassMutators(List.of(mutator)));
    mutations.prepareFor(project(List.of()));
    return mutations.find(model());
  }

  private List<Mutant> found(ClassMutator mutator, List<Path> dependencies) throws Exception {
    var wholeClasses = new WholeClasses(List.of(mutator), (model, member) -> Optional.empty());
    wholeClasses.prepareFor(project(dependencies));
    return wholeClasses.find(model());
  }

  private List<Mutant> found(ClassMutator mutator) throws Exception {
    return found(mutator, List.of());
  }

  @Test
  void aMutantStandsOnItsMemberAndTheLineItsTextMarks() throws Exception {
    var stub =
        new Stub(
            ClassMutator.Reach.EVERY_TEST,
            marked(ClassMutator.Member.ofClass()),
            marked(ClassMutator.Member.ofField("name", "Ljava/lang/String;")),
            marked(ClassMutator.Member.ofField("code", "Ljava/lang/String;")),
            marked(new ClassMutator.Member("label", "()Ljava/lang/String;")),
            marked(new ClassMutator.Member("shown", "(Ljava/lang/String;)Ljava/lang/String;")));

    var mutants = found(stub);

    assertThat(mutants).extracting(Mutant::line).containsExactly(3, 5, 8, 11, 15);
    assertThat(mutants.getFirst().methodName()).isEqualTo("<class>");
    assertThat(mutants.get(1).methodName()).isEqualTo("<field name>");
    assertThat(mutants.get(1).descriptor()).isEqualTo("Ljava/lang/String;");
    assertThat(mutants).allMatch(mutant -> mutant.skipped().isEmpty());
    assertThat(stub.prepared).isPresent();
  }

  @Test
  void anAnnotationsTextOnAnAbstractMethodIsFoundByItsDeclaration() throws Exception {
    var missing =
        ClassMutator.Change.of(ClassMutator.Member.ofField("gone", "I"), "x", "x", "@Marked");
    var nothing = ClassMutator.Change.of(ClassMutator.Member.ofClass(), "y", "y", "@Nothing");
    var stub =
        new Stub(
            ClassMutator.Reach.EVERY_TEST,
            marked(new ClassMutator.Member("run", "()V")),
            missing,
            nothing);

    var mutants = found(stub);

    assertThat(mutants).extracting(Mutant::line).containsExactly(22, -1, -1);
  }

  @Test
  void noTextMeansTheMethodsFirstLineAndNoLineForAClass() throws Exception {
    var inMethod =
        ClassMutator.Change.of(
            new ClassMutator.Member("label", "()Ljava/lang/String;"), "m", "m", "");
    var onClass = ClassMutator.Change.of(ClassMutator.Member.ofClass(), "c", "c", "");

    var mutants = found(new Stub(ClassMutator.Reach.METHOD, inMethod, onClass));

    assertThat(mutants).extracting(Mutant::line).containsExactly(12, -1);
  }

  @Test
  void aMentionInALongerNameIsntOne() throws Exception {
    var change = ClassMutator.Change.of(ClassMutator.Member.ofClass(), "c", "c", "@Mark");

    assertThat(found(new Stub(ClassMutator.Reach.METHOD, change)))
        .singleElement()
        .satisfies(mutant -> assertThat(mutant.line()).isEqualTo(-1));
  }

  @Test
  void anIdHoldsTheMemberTheLabelAndTheOperator() throws Exception {
    var field = marked(ClassMutator.Member.ofField("name", "Ljava/lang/String;"));
    var other = marked(ClassMutator.Member.ofField("code", "Ljava/lang/String;"));

    var mutants = found(new Stub(ClassMutator.Reach.EVERY_TEST, field, other));

    assertThat(mutants.get(0).id()).isNotEqualTo(mutants.get(1).id());
  }

  @Test
  void aSkipTheOperatorGivesStands() throws Exception {
    var skipped =
        new ClassMutator.Change(
            ClassMutator.Member.ofClass(), "c", "c", "", Optional.of("equivalent: stub"));

    assertThat(found(new Stub(ClassMutator.Reach.METHOD, skipped)))
        .singleElement()
        .satisfies(mutant -> assertThat(mutant.skipped()).contains("equivalent: stub"));
  }

  @Test
  void aRuleSkipsAMemberOrTheClass() throws Exception {
    var policy = new Policy(List.of(new Rule(false, "fixture.Order", "generated")), List.of());

    var mutants =
        foundByMutations(
            new Stub(
                ClassMutator.Reach.EVERY_TEST,
                marked(ClassMutator.Member.ofClass()),
                marked(new ClassMutator.Member("label", "()Ljava/lang/String;"))),
            policy);

    assertThat(mutants)
        .allSatisfy(
            mutant -> assertThat(mutant.skipped().orElseThrow()).startsWith("user: generated"));
  }

  @Test
  void whereAFrameworkLoadsTheAppItsSkippedRatherThanLookingMissed() throws Exception {
    var mutants =
        found(
            new Stub(ClassMutator.Reach.EVERY_TEST, marked(ClassMutator.Member.ofClass())),
            List.of(Path.of("quarkus-junit5-3.0.jar")));

    assertThat(mutants)
        .singleElement()
        .satisfies(
            mutant ->
                assertThat(mutant.skipped().orElseThrow())
                    .startsWith("unviable: this project's tests run in a framework"));
  }

  @Test
  void whatTheOperatorCantWriteIsUnviable() throws Exception {
    var stub = new Stub(ClassMutator.Reach.EVERY_TEST, marked(ClassMutator.Member.ofClass()));
    stub.thrown = Optional.of(new IllegalArgumentException("no such label"));

    assertThat(foundByMutations(stub, Policy.NONE))
        .singleElement()
        .satisfies(mutant -> assertThat(mutant.skipped()).contains("unviable: no such label"));
  }

  @Test
  void itsOperatorIsChosenLikeAnyOther() {
    var stub = new Stub(ClassMutator.Reach.METHOD);
    var policy = Policy.NONE.withClassMutators(List.of(stub));

    assertThat(OperatorSet.active(policy)).doesNotContain(stub);
    assertThat(OperatorSet.active(policy.withOperators(List.of("extended"), List.of())))
        .contains(stub);
    assertThat(OperatorSet.active(policy.withOperators(List.of("stub"), List.of())))
        .containsExactly(stub);
  }

  /// Every test, those that reach the class first, each set fastest first; or for a change in a
  /// method, the tests that reach a line of it.
  @Test
  void whichTestsAMutantRunsAgainst() throws Exception {
    var coverage = new Coverage(ClassFile.of());
    coverage.instrument(model());
    var everyTest = whole(ClassMutator.Reach.EVERY_TEST, "<class>", "");
    var inMethod = whole(ClassMutator.Reach.METHOD, "label", "()Ljava/lang/String;");
    var lineOfLabel = lineOf(coverage, "label()Ljava/lang/String;", 12);
    var lineOfShown = lineOf(coverage, "shown(Ljava/lang/String;)Ljava/lang/String;", 18);
    time(coverage, "a-slow-reacher", 30, Set.of(lineOfLabel));
    time(coverage, "b-fast-reacher", 20, Set.of(lineOfShown));
    time(coverage, "c-slow-other", 10, Set.of());
    time(coverage, "d-fast-other", 5, Set.of());

    assertThat(coverage.coveringTests(everyTest))
        .containsExactly("b-fast-reacher", "a-slow-reacher", "d-fast-other", "c-slow-other");
    assertThat(coverage.coveringTests(inMethod)).containsExactly("a-slow-reacher");
    assertThat(coverage.timeOfEveryTest()).isEqualTo(Duration.ofMillis(65));
  }

  @Test
  void aMethodNoTestReachesIsntCovered() throws Exception {
    var coverage = new Coverage(ClassFile.of());
    coverage.instrument(model());
    time(coverage, "a-test", 5, Set.of());

    assertThat(
            coverage.coveringTests(
                whole(ClassMutator.Reach.METHOD, "label", "()Ljava/lang/String;")))
        .isEmpty();
  }

  /// `instrument` numbers lines in the order it meets them: found by a mutant on the line alone.
  private static int lineOf(Coverage coverage, String method, int line) {
    var name = method.substring(0, method.indexOf('('));
    var descriptor = method.substring(method.indexOf('('));
    var probe =
        new Mutant(
            "fixture/Order", name, descriptor, 0, line, BuiltIn.MATH, "x", "y", Optional.empty());
    var marker = "line-of-" + method;
    for (int id = 0; id < 100; id++) {
      coverage.recordLines(marker, Set.of(id));
      coverage.recordTest(marker, Duration.ZERO);
      if (coverage.coveringTests(probe).contains(marker)) {
        coverage.exclude(List.of(marker));
        return id;
      }
      coverage.exclude(List.of(marker));
    }
    throw new AssertionError("no line " + line + " in " + method);
  }

  private static void time(Coverage coverage, String test, int millis, Set<Integer> lines) {
    coverage.recordTest(test, Duration.ofMillis(millis));
    coverage.recordLines(test, lines);
  }

  private static Mutant whole(ClassMutator.Reach reach, String name, String descriptor) {
    return new Mutant(
        "fixture/Order", name, descriptor, 0, -1, new Stub(reach), "x", "y", Optional.empty());
  }
}
