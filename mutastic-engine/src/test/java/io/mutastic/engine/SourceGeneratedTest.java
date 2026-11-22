package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.engine.fixture.MentionsGenerated;
import io.mutastic.engine.fixture.Pricing;
import io.mutastic.engine.fixture.ProcessorOutput;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// `@Generated` kept for the source alone, as `javax.annotation.processing.Generated` is: read from
/// the type's source file.
class SourceGeneratedTest {
  private static final Path SOURCES = Path.of("src/test/java");

  /// Every mutant of the type, of its lambdas and of the types nested in it is generated.
  @Test
  void aTypeTheSourceMarksIsGenerated() throws Exception {
    // A lambda's body is a method of the class it's in: `lambda$adder$0`.
    var skips = skipsIn(ProcessorOutput.class, ProcessorOutput.Builder.class);

    assertThat(skips)
        .isNotEmpty()
        .allMatch(
            skip ->
                skip.equals(
                    Optional.of("generated: @Generated on `ProcessorOutput` in the source")));
  }

  /// No source roots, no source to read: the class file alone decides, as before.
  @Test
  void withoutItsSourceTheClassFileAloneDecides() throws Exception {
    var mutations = new Mutations(ClassFile.of());
    var model = MutationsTest.model(ProcessorOutput.class);
    mutations.prepare(List.of(model));

    assertThat(mutations.find(model)).isNotEmpty().allMatch(mutant -> mutant.skipped().isEmpty());
  }

  /// A comment or a string that says `@Generated` isn't an annotation.
  @Test
  void textThatOnlyMentionsItDoesNotCount() throws Exception {
    assertThat(skipsIn(MentionsGenerated.class, Pricing.class))
        .isNotEmpty()
        .noneMatch(skip -> skip.filter(why -> why.contains("in the source")).isPresent());
  }

  @Test
  void readsOnlyATopLevelTypesOwnAnnotations() {
    var source =
        """
        package acme;

        import javax.annotation.processing.Generated;

        @Generated("one")
        final class One {
          @Generated("a member") void member() {}
        }

        /* @Generated */
        class Two {
          String text = "@Generated";
        }

        @javax.annotation.Generated(value = {"x", "y"}, date = "2026")
        public record Three(int value) {}

        @jakarta.annotation.Generated("z") @Deprecated
        enum Four { A, B; }

        @GeneratedValue
        interface Five {}

        class Six {
          @Generated
          class Inner {}
        }

        @Generated
        @interface Seven {}
        """;

    assertThat(SourceGenerated.generatedTypes(source))
        .containsExactlyInAnyOrder("One", "Three", "Four", "Seven");
  }

  @Test
  void theOutermostTypeIsTheOneBeforeTheFirstDollar() {
    assertThat(SourceGenerated.outermost("com/acme/Order$Builder$1")).isEqualTo("Order");
    assertThat(SourceGenerated.outermost("Order")).isEqualTo("Order");
  }

  /// Each mutant's skip, of each of the classes.
  private static List<Optional<String>> skipsIn(Class<?>... types) throws Exception {
    var mutations = new Mutations(ClassFile.of());
    var models = new java.util.ArrayList<ClassModel>();
    for (Class<?> type : types) {
      models.add(MutationsTest.model(type));
    }
    mutations.prepare(models);
    mutations.prepareFor(IgnoredTest.project(List.of(SOURCES)));
    return models.stream()
        .flatMap(model -> mutations.find(model).stream())
        .map(Mutant::skipped)
        .toList();
  }
}
