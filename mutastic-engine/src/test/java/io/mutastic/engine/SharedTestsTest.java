package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Which test classes in `demo`, a package production code uses, run by their own names, from the
/// class files of a project's tests, compiled once.
class SharedTestsTest {
  private static final Set<String> PRODUCTION_PACKAGES = Set.of("demo");

  private static Map<String, ClassModel> tests;

  @BeforeAll
  static void compile() throws Exception {
    var classes =
        Javac.compile(
            List.of(
                source("demo.Plain", "package demo; class Plain { int x = 1; }"),
                source("demo.Point", "package demo; public class Point { public int x; }"),
                source(
                    "demo.args.Holder",
                    "package demo.args; public class Holder { public demo.Point point; }"),
                source(
                    "demo.Converter", "package demo; public class Converter { demo.Used used; }"),
                source("demo.Used", "package demo; public class Used {}"),
                source(
                    "demo.args.Marker",
                    "package demo.args; public @interface Marker { Class<?> value(); }"),
                source(
                    "demo.args.Annotated",
                    "package demo.args; @Marker(demo.Converter.class) public class Annotated {}"),
                source(
                    "demo.Named",
                    "package demo; class Named { String s = \"demo.Named$Inner\"; class Inner {} }"),
                source("demo.Other", "package demo; public class Other {}"),
                source(
                    "demo.Reads",
                    "package demo; class Reads { String n() { return Other.class.getName(); } }"),
                source(
                    "demo.Configured",
                    "package demo; @demo.args.Marker(Configured.Extra.class) class Configured {"
                        + " static class Extra {} }")),
            List.of(),
            "-g");
    tests = new HashMap<>();
    classes.values().stream()
        .map(bytes -> ClassFile.of().parse(bytes))
        .forEach(model -> tests.put(model.thisClass().asInternalName(), model));
  }

  private static Javac.Source source(String name, String text) {
    return new Javac.Source(name, text);
  }

  private static Set<String> shared() {
    return SharedTests.of(PRODUCTION_PACKAGES, tests);
  }

  @Test
  void aTestClassAnotherPackageRefersToRunsByItsOwnName() {
    assertThat(shared()).contains("demo.Point");
  }

  @Test
  void soDoesOneAnotherPackagesAnnotationNamesAndWhatItRefersTo() {
    assertThat(shared()).contains("demo.Converter", "demo.Used");
  }

  @Test
  void soDoesOneWhoseNameAStringHolds() {
    assertThat(shared()).contains("demo.Named");
  }

  @Test
  void soDoesAClassLiteralWhoseNameIsRead() {
    assertThat(shared()).contains("demo.Other");
  }

  @Test
  void everyOtherIsACopy() {
    assertThat(shared())
        .doesNotContain("demo.Plain", "demo.Reads", "demo.Configured")
        .noneMatch(name -> name.startsWith("demo.args."));
  }
}
