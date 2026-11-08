package io.mutastic.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Project;
import io.mutastic.api.TestSelection;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The gates a proposal goes through before it's a mutant, and what comes out of them behaving as
/// the source says.
class ProposedRewritesTest {
  private static final String SOURCE =
      """
      package fixture;
      import java.util.function.IntUnaryOperator;
      public class Rates {
        private final int base = 2;
        public int doubled(int value) {
          return value * base;
        }
        public int tripled(int value) {
          IntUnaryOperator times = each -> each * 3;
          return times.applyAsInt(value);
        }
        public static class Nested {
          public int one() {
            return 1;
          }
        }
      }
      """;

  @TempDir Path work;
  private final ProposedRewrites rewrites = new ProposedRewrites();
  private Compiled original;

  @BeforeEach
  void prepare() throws Exception {
    Files.createDirectories(work.resolve("src/fixture"));
    Files.writeString(work.resolve("src/fixture/Rates.java"), SOURCE);
    original = Compiled.of(Map.of("fixture/Rates.java", SOURCE));
    rewrites.prepare(project(work));
  }

  private static Project project(Path work) {
    return new Project(
        "fixture",
        Path.of("java"),
        List.of(),
        work,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(work.resolve("src")),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        TestSelection.ALL,
        Map.of());
  }

  private void propose(String label, String source) throws Exception {
    var directory = work.resolve(".mutastic/proposed/fixture.Rates");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(label + ".java"), source);
  }

  private ClassMutator.Change only() {
    var changes = rewrites.changes(original.model("fixture.Rates"));
    assertThat(changes).hasSize(1);
    return changes.getFirst();
  }

  @Test
  void aClassWithNoProposalsHasNoChanges() {
    assertThat(rewrites.changes(original.model("fixture.Rates"))).isEmpty();
  }

  @Test
  void aRewriteOfOneMethodIsAMutantOfThatMethodThatDoesWhatTheSourceSays() throws Exception {
    propose("plus", SOURCE.replace("value * base", "value + base"));

    var change = only();

    assertThat(change.skipped()).isEmpty();
    assertThat(change.member().name()).isEqualTo("doubled");
    assertThat(change.label()).isEqualTo("plus");
    var bytes = rewrites.write(original.model("fixture.Rates"), change.member(), "plus");
    assertThat(ClassFile.of().verify(bytes)).isEmpty();
    var rewritten = original.load("fixture.Rates", bytes);
    var instance = rewritten.getConstructor().newInstance();
    assertThat(rewritten.getMethod("doubled", int.class).invoke(instance, 5)).isEqualTo(7);
    assertThat(rewritten.getMethod("tripled", int.class).invoke(instance, 5)).isEqualTo(15);
  }

  @Test
  void aRewriteInALambdaIsAChangeToTheMethodItsIn() throws Exception {
    propose("lambda", SOURCE.replace("each * 3", "each * 4"));

    assertThat(only().member().name()).isEqualTo("tripled");
  }

  @Test
  void aSecondProposalCompilingTheSameIsADuplicate() throws Exception {
    propose("a", SOURCE.replace("value * base", "value + base"));
    propose("b", SOURCE.replace("value * base", "value  +  base"));
    propose("c", SOURCE.replace("value * base", "value - base"));

    var changes = rewrites.changes(original.model("fixture.Rates"));

    assertThat(changes.get(0).skipped()).isEmpty();
    assertThat(changes.get(1).skipped()).contains("equivalent: compiles to the same code as `a`");
    assertThat(changes.get(2).skipped()).isEmpty();
  }

  @Test
  void whatCompilesToTheSameCodeIsEquivalent() throws Exception {
    propose("comment", SOURCE.replace("package fixture;", "// a comment\npackage fixture;"));

    assertThat(only().skipped()).contains("equivalent: it compiles to the same code as the class");
  }

  @Test
  void whatDoesntCompileIsUnviableWithJavacsMessage() throws Exception {
    propose("broken", SOURCE.replace("value * base", "value * nothing"));

    var skipped = only().skipped().orElseThrow();

    assertThat(skipped)
        .startsWith("unviable: it doesn't compile: line 6:")
        .contains("cannot find symbol");
  }

  @Test
  void aProposalOfMoreThanOneMethodIsUnviable() throws Exception {
    propose("two", SOURCE.replace("value * base", "value + base").replace("each * 3", "each * 4"));

    assertThat(only().skipped().orElseThrow())
        .startsWith("unviable: it changes more than one method")
        .contains("doubled(I)I", "tripled(I)I");
  }

  @Test
  void aNewMethodOrFieldIsUnviable() throws Exception {
    propose(
        "method",
        SOURCE.replace(
            "private final int base", "public int more() { return 1; } private final int base"));

    assertThat(only().skipped().orElseThrow()).contains("its fields or methods aren't the class's");
  }

  @Test
  void anotherClassInTheFileIsUnviable() throws Exception {
    propose("added", SOURCE + "class Helper {}");

    assertThat(only().skipped().orElseThrow()).contains("it makes different classes");
  }

  @Test
  void aChangeToANestedClassIsntAChangeToItsOuterClass() throws Exception {
    propose("nested", SOURCE.replace("return 1;", "return 2;"));

    assertThat(only().skipped().orElseThrow())
        .contains("it changes another class in the file too: fixture/Rates$Nested")
        .contains(".mutastic/proposed/fixture.Rates$Nested/");
  }

  @Test
  void aNestedClassCanHaveProposalsOfItsOwn() throws Exception {
    var directory = work.resolve(".mutastic/proposed/fixture.Rates$Nested");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("two.java"), SOURCE.replace("return 1;", "return 2;"));

    var changes = rewrites.changes(original.model("fixture.Rates$Nested"));

    assertThat(changes).singleElement().satisfies(change -> assertThat(change.skipped()).isEmpty());
  }

  /// Where `?:` joins two of the project's own types, javac's stack map names a type only the
  /// project's class path knows: comparing methods mustn't need it.
  @Test
  void aMethodJoiningTheProjectsOwnTypesCanBeCompared() throws Exception {
    var joining =
        SOURCE.replace(
            "public static class Nested {",
            """
            interface Fee {}
            static final class Flat implements Fee {}
            static Fee fee(Fee given) {
              return given != null ? given : new Flat();
            }
            public static class Nested {""");
    Files.writeString(work.resolve("src/fixture/Rates.java"), joining);
    var compiled = Compiled.of(Map.of("fixture/Rates.java", joining));
    propose("plus", joining.replace("value * base", "value + base"));

    var changes = rewrites.changes(compiled.model("fixture.Rates"));

    assertThat(changes).singleElement().satisfies(change -> assertThat(change.skipped()).isEmpty());
  }

  @Test
  void aProposalThatCantBeJudgedIsUnviableAndTheRestAreStillJudged() throws Exception {
    propose("plus", SOURCE.replace("value * base", "value + base"));
    Files.createDirectories(work.resolve(".mutastic/proposed/fixture.Rates/a-folder.java"));

    var changes = rewrites.changes(original.model("fixture.Rates"));

    assertThat(changes.get(0).skipped().orElseThrow())
        .startsWith("unviable: it couldn't be judged: ");
    assertThat(changes.get(1).skipped()).isEmpty();
  }

  @Test
  void aStaticInitialiserIsUnviable() throws Exception {
    var withStatic =
        SOURCE.replace(
            "private final int base = 2;", "private final int base = 2; static int shared = 1;");
    Files.writeString(work.resolve("src/fixture/Rates.java"), withStatic);
    var compiled = Compiled.of(Map.of("fixture/Rates.java", withStatic));
    propose("static", withStatic.replace("shared = 1", "shared = 2"));

    var changes = rewrites.changes(compiled.model("fixture.Rates"));

    assertThat(changes.getFirst().skipped().orElseThrow()).contains("a static initialiser");
  }

  @Test
  void aClassWhoseSourceIsntHereIsUnviable() throws Exception {
    propose("plus", SOURCE.replace("value * base", "value + base"));
    Files.delete(work.resolve("src/fixture/Rates.java"));

    assertThat(only().skipped().orElseThrow()).contains("source isn't here");
  }

  @Test
  void whenTheClassesOwnSourceNoLongerCompilesNothingCanBeCompared() throws Exception {
    propose("plus", SOURCE.replace("value * base", "value + base"));
    Files.writeString(work.resolve("src/fixture/Rates.java"), "package fixture; class Oops {");

    assertThat(only().skipped().orElseThrow()).contains("own source doesn't compile here");
  }

  @Test
  void anotherFileInTheDirectoryIsntAProposal() throws Exception {
    var directory = work.resolve(".mutastic/proposed/fixture.Rates");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("notes.txt"), "not java");

    assertThat(rewrites.changes(original.model("fixture.Rates"))).isEmpty();
  }

  @Test
  void writingAProposalThatDidntCompileIsRefused() {
    assertThatThrownBy(
            () ->
                rewrites.write(
                    original.model("fixture.Rates"), ClassMutator.Member.ofClass(), "unknown"))
        .isInstanceOf(IllegalStateException.class);
  }

  /// A card can't say the rewrite changes anything: no filter can rule out that it doesn't.
  @Test
  void aCardSaysToReadTheRewriteAndNeverThatItsDifferent() {
    var mutant =
        new Mutant(
            "fixture/Rates",
            "doubled",
            "(I)I",
            0,
            5,
            rewrites,
            "the method as written",
            "plus",
            Optional.empty());

    var words = rewrites.words(mutant);

    assertThat(words.headline())
        .isEqualTo(
            "no test tells `doubled()` from the rewrite in `.mutastic/proposed/fixture.Rates/plus.java`");
    assertThat(words.help()).contains("if it changes what `doubled()` does", "if it does the same");
    assertThat(words.changed()).contains("plus.java");
    assertThat(rewrites.id()).isEqualTo("proposed/rewrite");
    assertThat(rewrites.explanation()).contains("may do the same thing");
    assertThat(rewrites.reach()).isEqualTo(ClassMutator.Reach.METHOD);
  }
}
