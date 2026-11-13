package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Choosing operators, on `samples/operators`: a stream filter no test needs runs by default; `+`
/// tested only as `2 + 2` is caught out by the stronger set; and a rules file, listed as a plugin,
/// adds an operator of its own.
class OperatorsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "operators");

  @TempDir Path work;

  @Test
  void theDefaultsFindTheStreamStageNoTestNeeds() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operators"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.out())
        .contains("no test needs `.filter(…)` in large()")
        .contains("no test needs `total >= 100` to be false in a lambda in `large()`")
        .doesNotContain("`+` becomes `*`");
  }

  /// A score is a share of what a set makes, so one under another set is no earlier score.
  @Test
  void onlyARunUnderTheSameOperatorsIsCompared() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operators"));
    String[] stronger = {
      "run", project.toString(), "--format=jsonl", "--report=none", "--operators=stronger"
    };

    String[] defaults = {"run", project.toString(), "--format=jsonl", "--report=none"};

    Samples.mutastic(defaults);
    var anotherSet = Samples.mutastic(stronger);
    var sameSet = Samples.mutastic(stronger);
    var backToTheFirst = Samples.mutastic(defaults);

    assertThat(anotherSet.out()).doesNotContain("previousScore", "changedSinceLastRun");
    assertThat(sameSet.out()).contains("previousScore", "changedSinceLastRun");
    // The run under the other set in between doesn't cost this one its comparison.
    assertThat(backToTheFirst.out()).contains("previousScore", "changedSinceLastRun");
  }

  @Test
  void strongerAddsArithmeticReplacementAndAPluginAddsItsRules() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operators"));
    Files.writeString(project.resolve("mutastic.yaml"), "plugins:\n  - rules/strings.yaml\n");

    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--operators=stronger");

    assertThat(run.out())
        .contains("tests pass even when `+` becomes `*` in `total()`")
        .contains("no test passes code() text with spaces for `.strip()` to remove");
  }

  @Test
  void operatorsInMutastic_yamlChooseAndTheCommandLineReplacesThem() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operators"));
    Files.writeString(project.resolve("mutastic.yaml"), "operators:\n  - -stream\n");

    var withoutStreams = Samples.mutastic("run", project.toString(), "--format=agent");
    var replaced =
        Samples.mutastic("run", project.toString(), "--format=agent", "--operators=defaults");

    assertThat(withoutStreams.out()).doesNotContain("`.filter(…)`");
    assertThat(replaced.out()).contains("no test needs `.filter(…)` in large()");
  }

  /// A Java operator from a plugin jar, on instructions rather than calls: a whole-number constant
  /// goes up by one. Its second change doesn't load, so it's skipped as unviable, never run.
  @Test
  void aPluginJarAddsAJavaOperator() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operators"));
    PluginJars.write(
        project.resolve("plugins/constants.jar"),
        io.mutastic.api.Mutator.class,
        "acme.ConstantPlusOne",
        """
        package acme;

        import io.mutastic.api.Mutant;
        import io.mutastic.api.Mutator;
        import java.lang.classfile.CodeBuilder;
        import java.lang.classfile.Instruction;
        import java.lang.classfile.instruction.ConstantInstruction;
        import java.util.List;

        public class ConstantPlusOne implements Mutator {
          public String id() {
            return "acme/constant-plus-one";
          }

          public String explanation() {
            return "A whole-number constant goes up by one.";
          }

          public boolean onCalls() {
            return false;
          }

          public List<Change> changes(Site site) {
            return site.instruction() instanceof ConstantInstruction constant
                    && constant.constantValue() instanceof Integer
                ? List.of(Change.of("plus one"), Change.of("dropped"))
                : List.of();
          }

          public void write(CodeBuilder builder, Instruction instruction, String label) {
            if (label.equals("plus one")) {
              var value = (Integer) ((ConstantInstruction) instruction).constantValue();
              builder.loadConstant(value + 1);
            }
          }

          public Words words(Mutant mutant) {
            return new Words(
                "constant plus one",
                "no test tells the constant in " + mutant.methodName() + "() from one more",
                "test at the constant itself, and assert the result");
          }
        }
        """);
    Files.writeString(project.resolve("mutastic.yaml"), "plugins:\n  - plugins/constants.jar\n");

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.err()).doesNotContain("mutastic:");
    assertThat(run.out())
        .contains("\"operator\":\"acme/constant-plus-one\"")
        .contains("no test tells the constant in lambda$large$0() from one more")
        .contains("unviable: ");
  }

  @Test
  void anOperatorNothingHasIsAUsageErrorThatSaysWhatThereIs() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("operators"));

    var run = Samples.mutastic("run", project.toString(), "--operators=everything");
    var close = Samples.mutastic("run", project.toString(), "--operators=defaults,-streams");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "--operators: no operator or set is called everything; there are defaults, stronger,"
                + " extended, all,")
        .contains("stream/remove-stage");
    assertThat(close.err())
        .contains(
            "--operators: no operator or set is called streams. There's one called stream.\n"
                + "  mutastic run "
                + project
                + " --operators=defaults,-stream\n");
  }
}
