package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Instruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What a card shows of a change: the whole statement, as written and as the mutant has it, the
/// change made where the source has it; and the change in words, never in the `+` line, where the
/// source can't show it. The shapes are from mutastic's own cards that didn't stand alone.
class DiffTest {
  @TempDir Path project;

  private SourceView sources;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/java/com/example/Text.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package com.example;

        class Text {
          static boolean quoted(String value) {
            return value.length() > 1
                && ((value.startsWith("\\"") && value.endsWith("\\""))
                    || (value.startsWith("'") && value.endsWith("'")));
          }

          static boolean isThrowable(java.util.List<String> next) {
            return (!next.isEmpty() && next.getFirst().equals("athrow"))
                || next.size() > 3;
          }

          static boolean notified(java.util.List<String> ofField) {
            if (ofField.stream().anyMatch(String::isEmpty)) {
              return true;
            }
            audit.record(format(ofField));
            return false;
          }
        }
        """);
    sources = new SourceView(project, List.of(project.resolve("src/main/java")));
  }

  /// A rename, as a rules file's swap makes: `{receiver}.endsWith({args})`.
  private record Rename(String to) implements Mutator {
    @Override
    public String id() {
      return "test/rename";
    }

    @Override
    public String explanation() {
      return "a call renamed";
    }

    @Override
    public boolean onCalls() {
      return true;
    }

    @Override
    public List<Change> changes(Site site) {
      return List.of();
    }

    @Override
    public void write(CodeBuilder builder, Instruction instruction, String label) {}

    @Override
    public Words words(Mutant mutant) {
      return new Words(
          "startsWith changed to " + to,
          "no test tells them apart",
          "add a test",
          Optional.of("{receiver}." + to + "({args})"));
    }
  }

  private static Mutant call(String method, int line, String called, Mutator operator) {
    return new Mutant(
        "com/example/Text",
        method,
        "(Ljava/lang/String;)Z",
        0,
        line,
        operator,
        "INVOKEVIRTUAL " + called,
        "renamed",
        Optional.empty());
  }

  private Diff diff(Mutant mutant) {
    return sources.diff(mutant, sources.advice(mutant));
  }

  @Test
  void aRenamedCallIsRenamedWhereItIsAndTheWholeStatementShows() {
    var swapped =
        diff(
            call(
                "quoted",
                6,
                "java/lang/String.startsWith(Ljava/lang/String;)Z",
                new Rename("endsWith")));

    // `startsWith` is there twice, once on javac's line for the call: that one changes, and the
    // whole statement shows.
    assertThat(swapped.shownBefore())
        .containsExactly(
            "return value.length() > 1",
            "    && ((value.startsWith(\"\\\"\") && value.endsWith(\"\\\"\"))",
            "        || (value.startsWith(\"'\") && value.endsWith(\"'\")));");
    assertThat(swapped.shownAfter())
        .containsExactly(
            "return value.length() > 1",
            "    && ((value.endsWith(\"\\\"\") && value.endsWith(\"\\\"\"))",
            "        || (value.startsWith(\"'\") && value.endsWith(\"'\")));");
  }

  @Test
  void aCallTwiceAfterJavacsLineAndNotOnItIsSaidInWords() {
    var swapped =
        diff(
            call(
                "quoted",
                5,
                "java/lang/String.startsWith(Ljava/lang/String;)Z",
                new Rename("endsWith")));

    assertThat(swapped.shown()).isFalse();
    assertThat(swapped.unshown())
        .isEqualTo("startsWith changed to endsWith (not shown in the source)");
  }

  @Test
  void aCallThereOnceIsRenamedInPlace() {
    var swapped =
        diff(
            call(
                "notified",
                16,
                "java/util/stream/Stream.anyMatch(Ljava/util/function/Predicate;)Z",
                new Rename("allMatch")));

    assertThat(swapped.shownAfter())
        .containsExactly("if (ofField.stream().allMatch(String::isEmpty)) {");
  }

  @Test
  void aForcedConditionReadsAsItsValueOverTheWholeStatement() {
    var forced =
        new Mutant(
            "com/example/Text",
            "isThrowable",
            "(Ljava/util/List;)Z",
            0,
            11,
            BuiltIn.REMOVE_CONDITIONAL,
            "IFNE",
            "always jump",
            Optional.empty(),
            List.of(),
            List.of("next.isEmpty()"));

    var shown = diff(forced);

    assertThat(sources.advice(forced).change()).isEqualTo("`next.isEmpty()` is always true");
    assertThat(shown.shownBefore())
        .containsExactly(
            "return (!next.isEmpty() && next.getFirst().equals(\"athrow\"))",
            "    || next.size() > 3;");
    assertThat(shown.shownAfter())
        .containsExactly(
            "return (false && next.getFirst().equals(\"athrow\"))", "    || next.size() > 3;");
  }

  @Test
  void aRemovedCallWhoseArgumentStillRunsLeavesIt() {
    var removed =
        new Mutant(
            "com/example/Text",
            "notified",
            "(Ljava/util/List;)Z",
            0,
            19,
            BuiltIn.REMOVE_VOID_CALL,
            "INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V",
            "remove call",
            Optional.empty());

    var shown = diff(removed);

    // `format(ofField)` still runs: the line taken out would say it doesn't.
    assertThat(shown.removed()).isFalse();
    assertThat(shown.shownAfter()).containsExactly("format(ofField);");
  }

  @Test
  void aLongStatementIsCutShortAroundItsChange() {
    var lines = List.of("a(", "  1,", "  2,", "  3,", "  4,", "  5,", "  6,", "  7);");
    var after = List.of("a(", "  1,", "  2,", "  3,", "  4,", "  5,", "  6,", "  8);");

    var cut = new Diff(1, lines, Optional.of(after), "7 becomes 8");

    assertThat(cut.shownAfter())
        .containsExactly("…", "  2,", "  3,", "  4,", "  5,", "  6,", "  8);");
    assertThat(cut.shownBefore()).hasSize(Diff.MOST_LINES + 1).startsWith("…");
  }
}
