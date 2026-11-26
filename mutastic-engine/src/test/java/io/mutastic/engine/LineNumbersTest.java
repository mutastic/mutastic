package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Mutant;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/// A class compiled without line numbers records no coverage, so its mutants would all read as
/// reached by no test: the run refuses, naming it, whatever the module's other classes have.
class LineNumbersTest {
  private static final String FEES =
      """
      package lines;
      public class Fees {
        public int lateFee(int days) { return days > 30 ? 25 : 0; }
      }
      """;
  private static final String RATES =
      """
      package lines;
      public class Rates {
        public int rate(int amount) { return amount > 100 ? 2 : 1; }
      }
      """;

  private static ClassModel compiled(String name, String source, String... options)
      throws Exception {
    var classes = Javac.compile(List.of(new Javac.Source(name, source)), List.of(), options);
    return ClassFile.of().parse(classes.get(name));
  }

  private static List<Mutant> mutantsOf(Map<String, ClassModel> models) {
    var mutants = new ArrayList<Mutant>();
    models.values().forEach(model -> mutants.addAll(new Mutations(ClassFile.of()).find(model)));
    return mutants;
  }

  private static Map<String, ClassModel> models(ClassModel... models) {
    var byName = new TreeMap<String, ClassModel>();
    for (var model : models) {
      byName.put(model.thisClass().asInternalName().replace('/', '.'), model);
    }
    return byName;
  }

  @Test
  void classesWithLineNumbersPass() throws Exception {
    var models = models(compiled("lines.Fees", FEES), compiled("lines.Rates", RATES));

    assertThat(LineNumbers.lacking(mutantsOf(models), models)).isEmpty();
  }

  @Test
  void onlyTheClassesWithoutThemAreNamed() throws Exception {
    // Partial debug info: one class from a compile with -g:none, one from a default compile.
    var models = models(compiled("lines.Fees", FEES, "-g:none"), compiled("lines.Rates", RATES));

    assertThat(LineNumbers.lacking(mutantsOf(models), models)).containsExactly("lines.Fees");
  }

  @Test
  void sourceAndVariablesWithoutLinesStillLackThem() throws Exception {
    var models = models(compiled("lines.Fees", FEES, "-g:source,vars"));

    assertThat(LineNumbers.lacking(mutantsOf(models), models)).containsExactly("lines.Fees");
  }

  @Test
  void aSkippedMutantDoesntCount() throws Exception {
    var models = models(compiled("lines.Fees", FEES, "-g:none"));
    var skipped =
        mutantsOf(models).stream()
            .map(
                mutant ->
                    new Mutant(
                        mutant.className(),
                        mutant.methodName(),
                        mutant.descriptor(),
                        mutant.ordinal(),
                        mutant.line(),
                        mutant.operator(),
                        mutant.original(),
                        mutant.replacement(),
                        java.util.Optional.of("generated"),
                        mutant.copies(),
                        mutant.operands()))
            .toList();

    assertThat(LineNumbers.lacking(skipped, models)).isEmpty();
  }

  @Test
  void theRefusalNamesTheClassesAndHowToCompileThem() throws Exception {
    var models = models(compiled("lines.Fees", FEES, "-g:none"), compiled("lines.Rates", RATES));

    assertThatThrownBy(() -> LineNumbers.require(mutantsOf(models), models))
        .isInstanceOf(Engine.NoLineNumbers.class)
        .hasMessageStartingWith(
            "lines.Fees was compiled without line numbers, so mutastic can't tell which tests"
                + " reach its mutants")
        .hasMessageContaining("take -g:none out of the compiler's arguments")
        .hasMessageNotContaining("lines.Rates");
  }

  @Test
  void manyClassesAreCounted() throws Exception {
    var models = new TreeMap<String, ClassModel>();
    for (var name : List.of("A", "B", "C", "D", "E", "F", "G")) {
      var source = FEES.replace("Fees", name);
      models.putAll(models(compiled("lines." + name, source, "-g:none")));
    }

    assertThatThrownBy(() -> LineNumbers.require(mutantsOf(models), models))
        .hasMessageStartingWith(
            "lines.A, lines.B, lines.C, lines.D, lines.E and 2 more were compiled without line"
                + " numbers, so mutastic can't tell which tests reach their mutants");
  }
}
