package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// Which edges are skipped as only reached where an `if` has ruled out their two sides being the
/// same: the ways into the code after the `if` that this reads from the bytecode. That each skipped
/// one really is equivalent, CardTruthTest checks by running it.
class GuardedEdgeTest {
  private static final String SOURCE =
      """
      package g;
      class Guards {
        static int thrown(int a, int b) {
          if (a == b) {
            throw new IllegalArgumentException();
          }
          return a < b ? 1 : 0;
        }

        static int jumpedPast(int a, int b) {
          int r;
          if (a == b) {
            r = 1;
          } else {
            r = a < b ? 1 : 2;
          }
          return r;
        }

        static int runsOn(int a, int b) {
          int r = 0;
          if (a == b) {
            r = 1;
          }
          return a < b ? r : 5;
        }

        static int caught(int a, int b) {
          int r = 0;
          if (a != b) {
            try {
              r = Integer.parseInt("1");
            } catch (NumberFormatException e) {
              r = 2;
            }
            return a < b ? r : 0;
          }
          return 3;
        }
      }
      """;

  @Test
  void skipsOnlyWhereEveryWayInPassesTheIf() throws Exception {
    var skipped = skipped();

    assertThat(skipped)
        .containsEntry(
            "thrown",
            "equivalent: differs only when a is b. That can't happen because the if a == b before"
                + " it goes elsewhere.")
        .containsKey("jumpedPast")
        .doesNotContainKeys("runsOn", "caught");
  }

  @Test
  void saysSoWithoutNamesWhenTheCodeHasNone() throws Exception {
    assertThat(skippedIn(SOURCE, "-g:none"))
        .containsEntry(
            "thrown",
            "equivalent: differs only when both sides are the same. That can't happen because the"
                + " if before it goes elsewhere when they're the same.");
    var around =
        """
        package g;
        class Guards {
          static int around(int a, int b) {
            if (a != b) {
              return a < b ? 1 : 0;
            }
            return 2;
          }
        }
        """;
    assertThat(skippedIn(around, "-g:none"))
        .containsEntry(
            "around",
            "equivalent: differs only when both sides are the same. That can't happen because the"
                + " if around it already checked they differ.");
  }

  private static Map<String, String> skipped() throws Exception {
    return skippedIn(SOURCE);
  }

  /// Each method's boundary mutant skipped by this rule, with its reason.
  private static Map<String, String> skippedIn(String source, String... options) throws Exception {
    var bytes =
        Javac.compile(List.of(new Javac.Source("g.Guards", source)), List.of(), options)
            .get("g.Guards");
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    return mutations.find(ClassFile.of().parse(bytes)).stream()
        .filter(mutant -> mutant.operator() == BuiltIn.BOUNDARY)
        .filter(mutant -> mutant.skipped().orElse("").contains(" the if "))
        .collect(
            Collectors.toMap(
                Mutant::methodName, mutant -> mutant.skipped().orElseThrow(), (one, other) -> one));
  }
}
