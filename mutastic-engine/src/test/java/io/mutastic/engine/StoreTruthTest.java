package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.SourceView;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Each card a removed assignment or field write gets says what its mutant does, checked against
/// Java itself: "the assignment to `x` is removed" takes the assignment out of the source, and that
/// source, compiled, must behave as the mutant does on every input. And what's skipped as
/// equivalent must behave as the original does.
class StoreTruthTest {
  private static final Path FIXTURE = Path.of("src/test/resources/truth");
  private static final int LOW = -4;
  private static final int HIGH = 5;

  /// A fixture class, the operator it's for, and how that operator's card says what it did.
  private record Fixture(String name, BuiltIn operator, String change) {
    Path source() {
      return FIXTURE.resolve("stores/" + name + ".java");
    }

    String className() {
      return "stores." + name;
    }

    Mutations mutations() {
      return new Mutations(
          ClassFile.of(),
          new Policy(List.of(), List.of(), List.of(), List.of(operator.id()), List.of()));
    }
  }

  private static final Fixture ASSIGNMENTS =
      new Fixture(
          "Assignments",
          BuiltIn.REMOVE_ASSIGNMENT,
          "the assignment to `%s` is removed, so it keeps its earlier value");

  private static final Fixture WRITES =
      new Fixture(
          "Writes",
          BuiltIn.REMOVE_FIELD_WRITE,
          "the write to `%s` is removed, so it keeps its earlier value");

  @Test
  void anAssignmentIsChangedOnlyWhereTheVariableAlreadyHasAValue() throws Exception {
    var sites = sites(ASSIGNMENTS);

    assertThat(sites)
        .containsEntry("anInt", List.of("x"))
        .containsEntry("aLong", List.of("x"))
        .containsEntry("aFloat", List.of("x"))
        .containsEntry("aDouble", List.of("x"))
        .containsEntry("aReference", List.of("x"))
        .containsEntry("fromNull", List.of("x"))
        .containsEntry("narrowedByItsValue", List.of("x"))
        .containsEntry("aParameter", List.of("a"))
        .containsEntry("compound", List.of("x"))
        .containsEntry("onABranch", List.of("x"))
        .containsEntry("inALoop", List.of("total"))
        .containsEntry("inASwitch", List.of("x"))
        .containsEntry("inATry", List.of("x"))
        .containsEntry("readByTheHandler", List.of("x", "x"))
        .containsEntry("inAFinally", List.of("x"))
        .containsEntry("lambda$inALambda$0", List.of("x"))
        .containsEntry("inAnExpression", List.of("x"))
        .containsEntry("chained", List.of("y", "x"))
        .doesNotContainKeys(
            "declared",
            "declaredThenAssigned",
            "slotUsedAgain",
            "forEach",
            "caught",
            "pattern",
            "stringSwitch",
            "lambda$stringSwitchInALambda$0");
  }

  @Test
  void anAssignmentNothingReadsIsSkippedAsEquivalent() throws Exception {
    var sites = sites(ASSIGNMENTS);
    var unread = "x (equivalent: nothing reads the value it's given)";

    assertThat(sites)
        .containsEntry("overwritten", List.of(unread, "x"))
        .containsEntry("neverRead", List.of(unread))
        .containsEntry("overwrittenInBothArms", List.of(unread, "x", "x"));
  }

  @Test
  void aFieldWriteIsChangedWhereverItIs() throws Exception {
    var sites = sites(WRITES);

    assertThat(sites)
        .containsEntry("setPlain", List.of("count"))
        .containsEntry("setWide", List.of("wide"))
        .containsEntry("setReal", List.of("real"))
        .containsEntry("setText", List.of("text"))
        .containsEntry("compound", List.of("count"))
        .containsEntry("incremented", List.of("count"))
        .containsEntry("onABranch", List.of("count"))
        .containsEntry("toZero", List.of("count"))
        .containsEntry("onAnotherObject", List.of("other", "count"))
        .containsEntry("asAValue", List.of("count"))
        .containsEntry("inAFinally", List.of("count", "text"))
        .containsEntry("setShared", List.of("shared"))
        .containsEntry("setSharedWide", List.of("sharedWide"))
        .containsEntry("lambda$inALambda$0", List.of("count"))
        .doesNotContainKey("<init>");
    assertThat(sites.get("setCount")).singleElement().asString().contains("(arid:");
  }

  @Test
  void codeWithoutItsTableOfVariablesHasNoAssignmentsToChange() throws Exception {
    var model = model(ASSIGNMENTS, "-g:lines,source");

    assertThat(ASSIGNMENTS.mutations().find(model)).isEmpty();
  }

  @Test
  void everyRemovedAssignmentsCardSaysWhatItsMutantDoes() throws Exception {
    assertThat(cardsThatLie(ASSIGNMENTS)).isEmpty();
  }

  @Test
  void everyRemovedFieldWritesCardSaysWhatItsMutantDoes() throws Exception {
    assertThat(cardsThatLie(WRITES)).isEmpty();
  }

  @Test
  void whatIsSkippedAsEquivalentBehavesAsTheOriginal() throws Exception {
    var model = model(ASSIGNMENTS);
    var skipped =
        ASSIGNMENTS.mutations().find(model).stream()
            .filter(
                mutant -> mutant.skipped().filter(why -> why.startsWith("equivalent")).isPresent())
            .toList();

    assertThat(skipped).as("the assignments skipped").hasSize(3);
    var expected = outcomes(ASSIGNMENTS, unchanged(model));
    for (var mutant : skipped) {
      var mutated = ASSIGNMENTS.mutations().apply(model, mutant);
      assertThat(outcomes(ASSIGNMENTS, mutated)).as(describe(mutant)).isEqualTo(expected);
    }
  }

  /// What the operator changes in each method: the name, and why it's skipped if it is.
  private Map<String, List<String>> sites(Fixture fixture) throws IOException {
    var model = model(fixture);
    var sites = new LinkedHashMap<String, List<String>>();
    for (Mutant mutant : fixture.mutations().find(model)) {
      sites
          .computeIfAbsent(mutant.methodName(), name -> new ArrayList<>())
          .add(named(mutant) + mutant.skipped().map(why -> " (" + why + ")").orElse(""));
    }
    return sites;
  }

  /// Every card whose words its mutant doesn't bear out: the name isn't what the line assigns, the
  /// mutant isn't the source with the assignment taken out, or it changes nothing at all.
  private List<String> cardsThatLie(Fixture fixture) throws Exception {
    var model = model(fixture);
    var mutations = fixture.mutations();
    var mutants =
        mutations.find(model).stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
    var lines = Files.readAllLines(fixture.source());
    var failures = new ArrayList<String>(wordsThatLie(fixture, mutants, lines));
    var variants = variants(fixture, mutants, lines);

    var original = outcomes(fixture, unchanged(model));
    for (int at = 0; at < mutants.size(); at++) {
      var mutant = mutants.get(at);
      var mutated = outcomes(fixture, mutations.apply(model, mutant));
      if (!said(variants.get(at)).equals(mutated)) {
        failures.add(describe(mutant) + ": the mutant isn't the assignment taken out");
      }
      if (mutated.equals(original)) {
        failures.add(describe(mutant) + ": changes nothing");
      }
    }
    assertThat(mutants).as("the cards checked").hasSizeGreaterThan(10);
    return failures;
  }

  /// What the source of a variant gives: all its text can do, so a text seen before isn't compiled
  /// or loaded again. One that won't compile is never kept, and fails each time.
  private static List<String> said(Javac.Source variant) throws ReflectiveOperationException {
    return Kept.outcomes(
        List.of("said", variant.className(), variant.text()),
        () -> {
          try {
            return outcomes(
                Javac.loader(Javac.compile(List.of(variant), List.of()))
                    .loadClass(variant.className()));
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  /// What a class of these bytes gives: they come from the code under test every time, and a
  /// fixture calls nothing of mutastic's, so they're all it depends on.
  private static List<String> outcomes(Fixture fixture, byte[] bytes)
      throws ReflectiveOperationException {
    return Kept.outcomes(
        List.of("did", fixture.className(), java.util.HexFormat.of().formatHex(bytes)),
        () -> outcomes(load(fixture, bytes)));
  }

  /// The cards that don't name what the mutant's line assigns, in the words the operator's use.
  private static List<String> wordsThatLie(
      Fixture fixture, List<Mutant> mutants, List<String> lines) {
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    var failures = new ArrayList<String>();
    for (var mutant : mutants) {
      var advice = sources.advice(mutant);
      var name = named(mutant);
      var assigns = Pattern.compile("\\b%s\\s*([-+*/]?=[^=]|\\+\\+)".formatted(name));
      if (!advice.change().equals(fixture.change().formatted(name))
          || !assigns.matcher(lines.get(mutant.line() - 1)).find()
          || !advice.headline().contains("`" + name + "`")
          || !advice.fix().contains("`" + name + "`")) {
        failures.add(
            "%s: %s; %s; %s"
                .formatted(describe(mutant), advice.change(), advice.headline(), advice.fix()));
      }
    }
    return failures;
  }

  /// The source once for each mutant, as a class of its own, with what the card claims done to it.
  private static List<Javac.Source> variants(
      Fixture fixture, List<Mutant> mutants, List<String> lines) {
    var variants = new ArrayList<Javac.Source>();
    for (var mutant : mutants) {
      var changed = new ArrayList<>(lines);
      int line = mutant.line() - 1;
      changed.set(line, without(lines.get(line), named(mutant)));
      var text = String.join("\n", changed);
      var name = Javac.nameFor(fixture.name(), text);
      variants.add(
          new Javac.Source(
              "stores." + name, text.replaceAll("\\b" + fixture.name() + "\\b", name)));
    }
    return variants;
  }

  /// The line with `name`'s assignment gone: what its `// without name:` comment says, or nothing.
  private static String without(String line, String name) {
    var said =
        Pattern.compile("// without %s: (?<code>.*?)(?= // without |$)".formatted(name))
            .matcher(line);
    return said.find() ? said.group("code") : "";
  }

  /// `x`, for a mutant whose change is `remove assignment to x`.
  private static String named(Mutant mutant) {
    var change = mutant.replacement();
    return change.substring(change.lastIndexOf(' ') + 1);
  }

  private static byte[] unchanged(ClassModel model) {
    return ClassFile.of().transformClass(model, ClassTransform.ACCEPT_ALL);
  }

  private static ClassModel model(Fixture fixture, String... options) throws IOException {
    var source = Javac.Source.read(fixture.source(), fixture.className());
    var compiled = Javac.compile(List.of(source), List.of(), options);
    return ClassFile.of().parse(compiled.get(fixture.className()));
  }

  private static Class<?> load(Fixture fixture, byte[] bytes) throws ClassNotFoundException {
    return new OneClass(fixture.className(), bytes).loadClass(fixture.className());
  }

  /// What each of the class's static methods gives for each pair of inputs, or what it threw.
  private static List<String> outcomes(Class<?> owner) throws ReflectiveOperationException {
    var outcomes = new ArrayList<String>();
    var methods =
        Arrays.stream(owner.getDeclaredMethods())
            .filter(StoreTruthTest::takesTwoInts)
            .sorted(Comparator.comparing(Method::getName))
            .toList();
    for (Method method : methods) {
      method.setAccessible(true);
      for (int a = LOW; a <= HIGH; a++) {
        for (int b = LOW; b <= HIGH; b++) {
          outcomes.add(method.getName() + ": " + outcome(method, a, b));
        }
      }
    }
    return outcomes;
  }

  private static boolean takesTwoInts(Method method) {
    return Modifier.isStatic(method.getModifiers())
        && !method.isSynthetic()
        && Arrays.equals(method.getParameterTypes(), new Class<?>[] {int.class, int.class});
  }

  private static String outcome(Method method, int a, int b) throws IllegalAccessException {
    try {
      return String.valueOf(method.invoke(null, a, b));
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  private static String describe(Mutant mutant) {
    return "%s line %d %s".formatted(mutant.methodName(), mutant.line(), mutant.replacement());
  }

  /// The fixture's class from the given bytes, in a loader of its own.
  private static final class OneClass extends ClassLoader {
    private final String name;
    private final byte[] bytes;

    OneClass(String name, byte[] bytes) {
      super(StoreTruthTest.class.getClassLoader());
      this.name = name;
      this.bytes = bytes.clone();
    }

    @Override
    protected Class<?> loadClass(String wanted, boolean resolve) throws ClassNotFoundException {
      return wanted.equals(name)
          ? defineClass(wanted, bytes, 0, bytes.length)
          : super.loadClass(wanted, resolve);
    }
  }
}
