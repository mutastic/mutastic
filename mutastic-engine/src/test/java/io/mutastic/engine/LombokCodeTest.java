package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;

/// What Lombok writes into a method, told from what the person wrote by what's in the class file,
/// with a stand-in for its marker and javac's compiling of the code it writes: `samples/lombok` has
/// the real output, in the CLI's end-to-end test.
class LombokCodeTest {
  private static final Javac.Source MARKER =
      new Javac.Source(
          "lombok.Generated",
          """
          package lombok;
          import java.lang.annotation.*;
          @Retention(RetentionPolicy.CLASS) public @interface Generated {}
          """);

  /// Another annotation called `Generated`: its member is skipped, as any `@Generated` one is, but
  /// it isn't Lombok's, so a check in it isn't one `@NonNull` asked for.
  private static final Javac.Source NOT_LOMBOKS =
      new Javac.Source(
          "com.acme.Generated",
          """
          package com.acme;
          import java.lang.annotation.*;
          @Retention(RetentionPolicy.CLASS) public @interface Generated {}
          """);

  private static final Javac.Source ITEM =
      new Javac.Source(
          "fixture.Item",
          """
          package fixture;
          import java.io.IOException;
          import java.io.Writer;
          import java.util.Collections;
          public class Item {
            private String name;
            private int count;
            @lombok.Generated public int getCount() { return count; }
            @lombok.Generated public void setName(String name) {
              if (name == null) {
                throw new NullPointerException("name is marked non-null but is null");
              }
              this.name = name;
            }
            @lombok.Generated public boolean equals(Object o) {
              return o instanceof Item other && count == other.count;
            }
            @com.acme.Generated public void setOther(String name) {
              if (name == null) {
                throw new NullPointerException("name is marked non-null but is null");
              }
              this.name = name;
            }
            public int doubled() { return count * 2; }
            public void checked(String text) {
              if (text == null) {
                throw new NullPointerException("text is marked non-null but is null");
              }
              count += text.length();
            }
            public void cleanedUp(Writer out) throws IOException {
              try {
                out.write(count + 1);
              } finally {
                if (Collections.singletonList(out).get(0) != null) {
                  out.close();
                }
              }
            }
            @lombok.Generated public static class Builder {
              private int count;
              public Builder count(int count) { this.count = count + 1; return this; }
            }
          }
          """);

  /// The line of `out.write(count + 1)`, and of the `@Cleanup`'s close after it.
  private static final int BODY = 33;

  private static final List<Integer> CLOSE = List.of(35, 36);

  private static final Mutations STRONGER =
      new Mutations(
          ClassFile.of(),
          new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));

  private static List<Mutant> found() throws Exception {
    var classes = Javac.compile(List.of(ITEM), List.of(MARKER, NOT_LOMBOKS));
    return classes.entrySet().stream()
        .filter(each -> each.getKey().startsWith("fixture."))
        .map(Map.Entry::getValue)
        .flatMap(bytes -> STRONGER.find(ClassFile.of().parse(bytes)).stream())
        .toList();
  }

  private static List<Mutant> in(List<Mutant> found, String method) {
    var mutants = found.stream().filter(mutant -> mutant.methodName().equals(method)).toList();
    assertThat(mutants).as(method).isNotEmpty();
    return mutants;
  }

  private static ThrowingConsumer<String> generated() {
    return reason -> assertThat(reason).startsWith("generated:");
  }

  @Test
  void whatLombokMarkedIsGenerated() throws Exception {
    var found = found();

    for (var method : List.of("getCount", "equals", "count")) {
      assertThat(in(found, method))
          .as(method)
          .allSatisfy(mutant -> assertThat(mutant.skipped()).hasValueSatisfying(generated()));
    }
  }

  @Test
  void aNonNullCheckRunsWhereverItIs() throws Exception {
    var found = found();
    var setter = in(found, "setName");

    // The check the person asked for with `@NonNull` runs, in Lombok's setter and in their own
    // method; the rest of the setter is skipped, as Lombok's (or for what's there: a message).
    assertThat(setter)
        .filteredOn(mutant -> mutant.original().equals("IFNONNULL"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
    assertThat(setter)
        .filteredOn(mutant -> !mutant.original().equals("IFNONNULL"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isPresent())
        .anySatisfy(mutant -> assertThat(mutant.skipped()).hasValueSatisfying(generated()));
    assertThat(in(found, "checked"))
        .filteredOn(mutant -> mutant.original().equals("IFNONNULL"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  @Test
  void aCleanupsCloseIsGeneratedAndTheBodyIsNot() throws Exception {
    var found = in(found(), "cleanedUp");

    assertThat(found)
        .filteredOn(mutant -> CLOSE.contains(mutant.line()))
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.skipped()).contains("generated: closes a `@Cleanup` resource"));
    assertThat(found)
        .filteredOn(mutant -> mutant.line() == BODY)
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  @Test
  void codeWithoutLombokIsUnchanged() throws Exception {
    var found = found();

    assertThat(in(found, "doubled")).allSatisfy(mutant -> assertThat(mutant.skipped()).isEmpty());
    // Another tool's `@Generated` skips the whole member, its check too.
    assertThat(in(found, "setOther"))
        .filteredOn(mutant -> mutant.original().equals("IFNONNULL"))
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.skipped()).hasValueSatisfying(generated()));
  }
}
