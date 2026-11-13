package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.Advice;
import io.mutastic.report.SourceView;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Each card a condition's mutant gets says what the mutant does, checked against Java itself.
///
/// The fixture has a method for each way javac compiles a condition. For every mutant of one, the
/// card's claim is written into the source: "`x > 0` is always true" puts `Boolean.TRUE` where `x >
/// 0` was, "`>=` becomes `>`" changes the operator, "reversed" negates the condition. That source,
/// compiled, must behave as the mutant does on every input. The check doesn't share the report's
/// reading of the bytecode: a card that has a jump the wrong way round fails here, as the first
/// test of an `||` once did.
class CardTruthTest {
  private static final Path FIXTURE = Path.of("src/test/resources/truth");
  private static final Path SOURCE = FIXTURE.resolve("shapes/Shapes.java");
  private static final String CLASS = "shapes.Shapes";

  /// Another class, whose static a condition reads.
  private static final Path OTHER = FIXTURE.resolve("shapes/Other.java");

  /// The annotations the claimed shapes are said to be zero or above by, as stand-ins with the real
  /// ones' names, retention and targets.
  private static final List<String> ANNOTATIONS =
      List.of(
          "org.checkerframework.checker.index.qual.NonNegative",
          "androidx.annotation.IntRange",
          "jakarta.validation.constraints.PositiveOrZero");

  /// The start of each of the fixture's methods, which are written with two spaces before `static`
  /// and end at the first line that is two spaces and a `}`.
  private static final Pattern METHOD = Pattern.compile("  static .*?(?<name>\\w+)\\(.*\\{");

  /// What [#METHOD] finds on each line it's been given: the same few lines, once per claim.
  private static final Map<String, String> METHOD_ON = new HashMap<>();

  /// The predicates the conditions call: not conditions of their own.
  private static final Set<String> HELPERS =
      Set.of("even", "small", "atLeastOne", "isAbove", "counted");

  private static final Set<BuiltIn> CONDITIONS =
      Set.of(
          BuiltIn.BOUNDARY,
          BuiltIn.COMPARISON,
          BuiltIn.REMOVE_CONDITIONAL,
          BuiltIn.OTHER_COMPARISON,
          BuiltIn.LOGICAL_CONNECTOR);

  private static final Pattern ALWAYS =
      Pattern.compile("`(?<condition>[^`]+)` is always (?<value>true|false)");

  /// A connector's headline, naming its two sides.
  private static final Pattern SIDES =
      Pattern.compile("with one of `(?<left>[^`]+)` and `(?<right>[^`]+)` true");

  private static final Pattern CONSTANT =
      Pattern.compile("static final int (?<name>[A-Z_]+) = (?<value>-?\\d+);");

  private static final Pattern LONG_LITERAL = Pattern.compile("\\b(\\d+)[lL]\\b");

  private static final Pattern LITERAL = Pattern.compile("\\b(?:true|false)\\b");
  private static final Pattern REVERSED = Pattern.compile("with `(?<condition>[^`]+)` reversed");

  /// A comparison's headline: the inputs where its mutant differs, `with `a > b`` or `at `a == b``.
  private static final Pattern WHERE = Pattern.compile("(?:with|at) `(?<where>[^`]+)`$");

  /// A skip that rests on what the code says of a value, `because a is @NonNegative.` or, unnamed,
  /// `because it's @NonNegative.`, not on what mutastic proves.
  private static final Pattern CLAIMED =
      Pattern.compile("because (?:(?<value>.+) is|it's) @(?<annotation>\\w+).*\\.$");

  private static final int LOW = -4;
  private static final int HIGH = 5;

  /// One mutant's card, and the source that says what one of its claims says it does: what happens
  /// to the condition, or the changed line the card shows.
  private record Claim(Mutant mutant, Advice advice, String variant, String source, String what) {}

  @Test
  void everyConditionsCardSaysWhatItsMutantDoes() throws Exception {
    var original = original();
    var model = ClassFile.of().parse(original.get(CLASS));
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    var sources = new SourceView(FIXTURE, List.of(FIXTURE));
    var text = Files.readString(SOURCE);
    var withValues = withConstantsAsValues(text);

    var failures = new ArrayList<String>();
    var claims = new ArrayList<Claim>();
    var mutants =
        mutations.find(model).stream()
            .filter(mutant -> mutant.skipped().isEmpty())
            .filter(mutant -> CONDITIONS.contains(mutant.operator()))
            .filter(mutant -> !HELPERS.contains(mutant.methodName()))
            .toList();
    for (var mutant : mutants) {
      var advice = sources.advice(mutant);
      claim(text, withValues, mutant, advice)
          .ifPresentOrElse(
              source -> claims.add(claimOf(mutant, advice, source, "its words")),
              () -> {
                // A connector the card can't name (two in the statement) makes no claim to check.
                if (mutant.operator() != BuiltIn.LOGICAL_CONNECTOR || advice.before().isPresent()) {
                  failures.add(describe(mutant, advice) + "\n  its condition isn't in the source");
                }
              });
      headline(withValues, mutant, advice)
          .ifPresent(source -> claims.add(claimOf(mutant, advice, source, "its headline")));
      var shown = sources.diff(mutant, advice);
      if (shown.shown()) {
        claims.add(
            claimOf(
                mutant,
                advice,
                withLines(text, shown),
                "its changed lines, `" + String.join("\n", shown.shownAfter()) + "`"));
      }
    }

    // The other class whose static a condition reads, and the annotations the claimed shapes have,
    // are there when they're compiled; the class is there when they're run.
    var alongside = alongside();
    // The claims about one mutant are all checked against what it does, found once.
    var whatClaimsSay = whatClaimsSay(claims, alongside, original);
    var whatMutantsDo = new HashMap<Mutant, List<String>>();
    for (var claim : claims) {
      var method = claim.mutant().methodName();
      var said = whatClaimsSay.get(claim.variant());
      var did = whatMutantsDo.get(claim.mutant());
      if (did == null) {
        did = outcomes(method, mutations.apply(model, claim.mutant()), original);
        whatMutantsDo.put(claim.mutant(), did);
      }
      firstDifference(said, did)
          .ifPresent(
              input ->
                  failures.add(
                      describe(claim.mutant(), claim.advice())
                          + "\n  "
                          + claim.what()
                          + ": but at "
                          + input
                          + " the mutant does otherwise"));
    }

    assertThat(claims).as("the claims checked").hasSizeGreaterThan(60);
    assertThat(claims.stream().map(claim -> claim.mutant().methodName()).distinct())
        .as("the shapes with a claim checked")
        .hasSizeGreaterThan(20);
    // A constant the source names still gets its changed line, quoted as the source has it.
    assertThat(claims)
        .extracting(Claim::what)
        .contains("its changed lines, `if (b < 0 || a >= EDGE) {`");
    assertThat(failures).as("cards that say something their mutant doesn't do").isEmpty();
    // A char compared, or passed where a character goes, is claimed as the character it is.
    assertThat(claims.stream().map(claim -> claim.advice().headline()))
        .anySatisfy(headline -> assertThat(headline).contains("`c == 'b'`"))
        .anySatisfy(headline -> assertThat(headline).contains("indexOf('/')"));
    // A string javac wrote in, and a class literal, are claimed as the source writes them.
    assertThat(claims.stream().map(claim -> claim.mutant().methodName()))
        .contains(
            "namedText", "namedTextFirst", "finalLocalText", "classLiteral", "primitiveClass");
  }

  /// A mutant skipped as equivalent behaves as the code does on every input: a skip rule that's
  /// wrong hides a mutant a test could catch, which is worse than a card that's vague.
  @Test
  void everyEquivalentSkipIsEquivalent() throws Exception {
    var original = original();
    var model = ClassFile.of().parse(original.get(CLASS));
    var mutations =
        new Mutations(
            ClassFile.of(),
            new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()));
    var equivalent =
        mutations.find(model).stream()
            .filter(mutant -> mutant.skipped().orElse("").startsWith("equivalent:"))
            .filter(mutant -> !HELPERS.contains(mutant.methodName()))
            .toList();
    var failures = new ArrayList<String>();
    for (var mutant : equivalent) {
      var method = mutant.methodName();
      var code = outcomes(method, original.get(CLASS), original);
      var did = outcomes(method, mutations.apply(model, mutant), original);
      // A claimed skip takes the code at its word that `a` is zero or above: those are its inputs.
      firstDifference(
              claimed(mutant) ? fromZero(code) : code, claimed(mutant) ? fromZero(did) : did)
          .ifPresent(
              input -> failures.add(mutant + " (" + mutant.skipped().get() + ") at " + input));
    }

    assertThat(failures).as("mutants skipped as equivalent that aren't").isEmpty();
    assertThat(equivalent)
        .as("the `||` shapes whose first test the second covers")
        .filteredOn(mutant -> mutant.skipped().get().contains("`||`"))
        .extracting(Mutant::methodName, Mutant::operator, Mutant::replacement)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                "impliedByACall", BuiltIn.REMOVE_CONDITIONAL, "never jump"),
            org.assertj.core.groups.Tuple.tuple(
                "impliedComparison", BuiltIn.REMOVE_CONDITIONAL, "never jump"),
            org.assertj.core.groups.Tuple.tuple("impliedComparison", BuiltIn.BOUNDARY, "IF_ICMPGE"),
            org.assertj.core.groups.Tuple.tuple(
                "impliedComparison", BuiltIn.OTHER_COMPARISON, "IF_ICMPEQ"));
    assertThat(equivalent)
        .as("the edges where both sides give the same value")
        .filteredOn(mutant -> mutant.skipped().get().contains("same value at the edge"))
        .extracting(Mutant::methodName)
        .contains("clampLow", "clampZero", "clampLong", "clampBoxed", "raiseTo")
        .doesNotContain("notAClamp", "clampToSomethingElse");
    assertThat(equivalent)
        .as("comparisons of a size or length that differ only below zero")
        .filteredOn(mutant -> mutant.skipped().get().equals(Comparisons.NEVER_NEGATIVE))
        .extracting(Mutant::methodName)
        .contains("lengthNotZero", "arrayAboveZero", "bufferLeft", "groupsNotZero")
        .doesNotContain("userListSize", "userTextLength");
    assertThat(equivalent)
        .as("comparisons of an index with -1 that differ only below -1")
        .filteredOn(mutant -> mutant.skipped().get().equals(Comparisons.NEVER_BELOW_MINUS_ONE))
        .extracting(Mutant::methodName)
        .contains("indexMissing", "lastIndexFound", "notADigit")
        .doesNotContain("userIndex");
    assertThat(mutations.find(model))
        .as("an index of the project's own compared with -1: what differs only below -1 runs")
        .filteredOn(mutant -> mutant.methodName().equals("userIndex"))
        .filteredOn(mutant -> mutant.replacement().equals("IF_ICMPGT"))
        .allMatch(mutant -> mutant.skipped().isEmpty())
        .isNotEmpty();
    // A list or text of the project's own can be negative: what differs only there runs.
    for (var method : List.of("userListSize", "userTextLength")) {
      assertThat(differingOnlyBelowZero(mutations, model, method, original))
          .as("the mutants of %s that differ only for a negative size", method)
          .isNotEmpty();
    }
    assertThat(equivalent)
        .as("comparisons that differ only below zero, of a value the code says is zero or above")
        .filteredOn(CardTruthTest::claimed)
        .extracting(Mutant::methodName)
        .contains(
            "claimedParameter", "claimedField", "claimedLocal", "claimedReturn", "claimedRange")
        .doesNotContain("claimReassigned", "claimUnchecked");
    assertThat(mutations.find(model))
        .as("the near misses' changes that differ only below zero still run")
        .filteredOn(mutant -> mutant.operator() == BuiltIn.COMPARISON)
        .filteredOn(mutant -> mutant.replacement().equals("IFEQ"))
        .filteredOn(mutant -> mutant.skipped().isEmpty())
        .extracting(Mutant::methodName)
        .contains("claimReassigned", "claimUnchecked");
    assertThat(equivalent)
        .filteredOn(CardTruthTest::claimed)
        .extracting(Mutant::methodName, mutant -> mutant.skipped().get())
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                "claimedParameter",
                "equivalent: differs only when a is negative. That can't happen because a is"
                    + " @NonNegative."),
            org.assertj.core.groups.Tuple.tuple(
                "claimedReturn",
                "equivalent: differs only when counted(a) is negative. That can't happen because"
                    + " counted(a) is @NonNegative."),
            org.assertj.core.groups.Tuple.tuple(
                "claimedRange",
                "equivalent: differs only when a is negative. That can't happen because a is"
                    + " @IntRange(from = 0)."));
    assertThat(equivalent)
        .as("edges only reached where an `if` has ruled out their two sides being the same")
        .filteredOn(mutant -> mutant.skipped().get().startsWith("equivalent: differs only when "))
        .filteredOn(mutant -> mutant.skipped().get().contains(" the if "))
        .extracting(Mutant::methodName)
        .contains(
            "guardedLess",
            "guardedEarly",
            "guardedElse",
            "guardedTernary",
            "guardedAnd",
            "guardedLong",
            "guardedThenCompared")
        .doesNotContain(
            "guardStoredBetween",
            "guardIncrementedBetween",
            "guardOfAnotherLocal",
            "guardNotAround",
            "guardSkippedByOr",
            "guardOfFloats");
    assertThat(mutations.find(model))
        .as("the near misses' edges still run")
        .filteredOn(mutant -> mutant.operator() == BuiltIn.BOUNDARY)
        .filteredOn(mutant -> mutant.skipped().isEmpty())
        .extracting(Mutant::methodName)
        .contains(
            "guardStoredBetween",
            "guardIncrementedBetween",
            "guardOfAnotherLocal",
            "guardNotAround",
            "guardSkippedByOr",
            "guardOfFloats");
    assertThat(equivalent)
        .filteredOn(mutant -> mutant.methodName().equals("guardedLess"))
        .extracting(mutant -> mutant.skipped().get())
        .containsExactly(
            "equivalent: differs only when a is b. That can't happen because the if a != b"
                + " around it rules it out.");
    assertThat(equivalent)
        .filteredOn(mutant -> mutant.methodName().equals("guardedLong"))
        .extracting(mutant -> mutant.skipped().get())
        .containsExactly(
            "equivalent: differs only when x is y. That can't happen because the if x != y"
                + " around it rules it out.");
  }

  /// Whether the mutant's skip rests on what the code says of a value.
  private static boolean claimed(Mutant mutant) {
    return CLAIMED.matcher(mutant.skipped().orElse("")).find();
  }

  /// The outcomes for an `a` of zero and above, the rest left out: what a claim that `a` is never
  /// negative covers.
  private static List<String> fromZero(List<String> outcomes) {
    int fromZero = -LOW * (HIGH - LOW + 1);
    var kept = new ArrayList<String>(outcomes);
    for (int at = 0; at < fromZero; at++) {
      kept.set(at, "left out");
    }
    return kept;
  }

  /// The mutants of `method` that run and differ from the code only where `a` is negative: the ones
  /// a skip resting on a size's contract would hide.
  private static List<Mutant> differingOnlyBelowZero(
      Mutations mutations,
      java.lang.classfile.ClassModel model,
      String method,
      Map<String, byte[]> original)
      throws Exception {
    var code = outcomes(method, original.get(CLASS), original);
    int fromZero = -LOW * (HIGH - LOW + 1);
    var found = new ArrayList<Mutant>();
    for (var mutant : mutations.find(model)) {
      if (!mutant.methodName().equals(method) || mutant.skipped().isPresent()) {
        continue;
      }
      var did = outcomes(method, mutations.apply(model, mutant), original);
      if (!did.equals(code)
          && did.subList(fromZero, did.size()).equals(code.subList(fromZero, code.size()))) {
        found.add(mutant);
      }
    }
    return found;
  }

  /// No jump gets the same change twice: two mutants of one condition that behave the same on every
  /// input are one mutant counted twice. A length compared with zero is where it would happen
  /// (`length > 0` to `length >= 0` is the condition always true). Only those shapes are checked:
  /// elsewhere two different changes can happen to agree on the few inputs tried.
  @Test
  void noConditionGetsTheSameChangeTwice() throws Exception {
    var original = original();
    var model = ClassFile.of().parse(original.get(CLASS));
    var mutations =
        new Mutations(
            ClassFile.of(), new Policy(List.of(), List.of(), List.of(), List.of("all"), List.of()));
    var bySite = new java.util.LinkedHashMap<String, List<String>>();
    var twice = new ArrayList<String>();
    for (var mutant : mutations.find(model)) {
      if (mutant.skipped().isPresent()
          || !CONDITIONS.contains(mutant.operator())
          || !Set.of("arrayAboveZero", "lengthNotZero").contains(mutant.methodName())) {
        continue;
      }
      var mutated = new OneClass(CLASS, mutations.apply(model, mutant), original).loadClass(CLASS);
      var behaviour = behaviour(mutant.methodName(), mutated);
      var site = mutant.methodName() + "@" + mutant.ordinal();
      var seen = bySite.computeIfAbsent(site, key -> new ArrayList<>());
      if (seen.contains(behaviour)) {
        twice.add(site + " " + mutant.operator().id() + " " + mutant.replacement());
      }
      seen.add(behaviour);
    }

    assertThat(bySite.keySet())
        .anyMatch(site -> site.startsWith("arrayAboveZero@"))
        .anyMatch(site -> site.startsWith("lengthNotZero@"));
    assertThat(twice).as("changes made twice at one condition").isEmpty();
  }

  /// What `method` gives on every input, in order.
  private static String behaviour(String method, Class<?> type)
      throws ReflectiveOperationException {
    var called = method(type, method);
    var outcomes = new StringBuilder();
    for (int a = LOW; a <= HIGH; a++) {
      for (int b = LOW; b <= HIGH; b++) {
        outcomes.append(outcome(called, a, b)).append(' ');
      }
    }
    return outcomes.toString();
  }

  /// A connector mutant at every condition of two tests joined by `&&` or `||`, however javac lays
  /// it out (an `if`, a loop's test, a `do`'s at the bottom, a `return`, through a `!`), and none
  /// where a condition has three tests or a nested `if` compiles the same.
  @Test
  void aConnectorIsFoundInEveryConditionOfTwoTests() throws Exception {
    var original = original();
    var model = ClassFile.of().parse(original.get(CLASS));
    var found =
        new Mutations(
                ClassFile.of(),
                new Policy(List.of(), List.of(), List.of(), List.of("all"), List.of()))
            .find(model).stream()
                .filter(mutant -> mutant.operator() == BuiltIn.LOGICAL_CONNECTOR)
                .map(Mutant::methodName)
                .toList();

    assertThat(found)
        .contains(
            "and",
            "or",
            "orOnABoolean",
            "andOnBooleans",
            "notOr",
            "whileLoop",
            "forLoop",
            "doWhile",
            "nullOr",
            "notNullAnd",
            "staticField",
            "sameObject",
            "splitOverLines",
            "splitReturn",
            "namedConstant")
        .doesNotContain("mixed", "threeAnds", "nestedIfs", "plainIf", "ternary", "nestedTernary")
        .doesNotHaveDuplicates();
  }

  /// Only numbers are ordered: `==` on two `boolean`s gets only its opposite. A `float`'s
  /// comparison gets no change that `NaN` would make differ somewhere the card doesn't say.
  @Test
  void aComparisonChangesOnlyWhatItsOperandsAllow() throws Exception {
    var original = original();
    var model = ClassFile.of().parse(original.get(CLASS));
    var found =
        new Mutations(
                ClassFile.of(),
                new Policy(List.of(), List.of(), List.of(), List.of("stronger"), List.of()))
            .find(model);

    assertThat(changes(found, "booleansEqual", BuiltIn.COMPARISON)).isEmpty();
    assertThat(changes(found, "booleansEqual", BuiltIn.OTHER_COMPARISON))
        .containsExactly("IF_ICMPEQ");
    assertThat(changes(found, "equalLongs", BuiltIn.COMPARISON)).containsExactly("IFLT", "IFGT");
    assertThat(changes(found, "equalsAnExpression", BuiltIn.COMPARISON))
        .containsExactly("IF_ICMPLT", "IF_ICMPGT");
    // `x < b` on floats: `x <= b` stays, `x != b` would differ for NaN too.
    assertThat(changes(found, "belowAFloat", BuiltIn.BOUNDARY)).containsExactly("IFGT");
    assertThat(changes(found, "belowAFloat", BuiltIn.COMPARISON)).isEmpty();
    assertThat(changes(found, "equalFloats", BuiltIn.COMPARISON)).isEmpty();
    assertThat(changes(found, "equalFloats", BuiltIn.OTHER_COMPARISON)).containsExactly("IFEQ");
  }

  /// The jumps `operator` changes the comparison of `x` (or `a`) with `b` in `method` to.
  private static List<String> changes(List<Mutant> found, String method, BuiltIn operator) {
    return found.stream()
        .filter(mutant -> mutant.methodName().equals(method) && mutant.operator() == operator)
        .filter(mutant -> mutant.operands().stream().anyMatch(operand -> operand.contains("b")))
        .map(Mutant::replacement)
        .toList();
  }

  /// What each claim's source gives, by its variant: all a claim's text can do, so a text seen
  /// before isn't compiled or loaded again. The rest are compiled in one call. A claim that won't
  /// compile is never kept, and fails each time.
  private static Map<String, List<String>> whatClaimsSay(
      List<Claim> claims, List<Javac.Source> alongside, Map<String, byte[]> original)
      throws Exception {
    var said = new HashMap<String, List<String>>();
    var unseen = new java.util.LinkedHashMap<String, Claim>();
    for (var claim : claims) {
      Kept.find(saidKey(claim, alongside))
          .ifPresentOrElse(
              outcomes -> said.put(claim.variant(), outcomes),
              () -> unseen.put(claim.variant(), claim));
    }
    if (!unseen.isEmpty()) {
      var classes =
          new HashMap<>(
              Javac.compile(
                  unseen.values().stream()
                      .map(claim -> new Javac.Source("shapes." + claim.variant(), claim.source()))
                      .toList(),
                  alongside));
      original.forEach(
          (name, bytes) -> {
            if (name.startsWith("shapes.Other")) {
              classes.put(name, bytes);
            }
          });
      var loader = Javac.loader(classes);
      for (var claim : unseen.values()) {
        var outcomes =
            outcomes(claim.mutant().methodName(), loader.loadClass("shapes." + claim.variant()));
        Kept.keep(saidKey(claim, alongside), outcomes);
        said.put(claim.variant(), outcomes);
      }
    }
    return said;
  }

  private static List<String> saidKey(Claim claim, List<Javac.Source> alongside) {
    var key = new ArrayList<>(List.of("said", claim.source(), claim.mutant().methodName()));
    alongside.forEach(source -> key.add(source.text()));
    return key;
  }

  /// What a class of these bytes does, beside the fixture's other class: fixtures call nothing of
  /// mutastic's, so the bytes, which come from the code under test every time, are all it depends
  /// on.
  private static List<String> outcomes(String method, byte[] bytes, Map<String, byte[]> original)
      throws Exception {
    return Kept.outcomes(
        List.of("did", HexFormat.of().formatHex(bytes), digestOf(original), method),
        () -> outcomes(method, new OneClass(CLASS, bytes, original).loadClass(CLASS)));
  }

  /// The classes a mutant runs beside, in a key: a digest of them.
  private static String digestOf(Map<String, byte[]> original) {
    return Javac.digest(
        original.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + HexFormat.of().formatHex(entry.getValue()))
            .toList());
  }

  /// What a card says, as source: the fixture with the claim written in, and of its other methods
  /// only the predicates the conditions call. A claim is about one method, so the rest would only
  /// be more for javac to compile, a thousand times over. A method it needs and doesn't have stops
  /// it compiling, which fails the check rather than passing it.
  private static Claim claimOf(Mutant mutant, Advice advice, String source, String what) {
    var only = withOnlyMethod(source, mutant.methodName());
    var name = Javac.nameFor("Shapes", only);
    return new Claim(mutant, advice, name, renamed(only, name), what);
  }

  /// `text` with the methods other than `method` and the predicates left blank, on the same lines.
  private static String withOnlyMethod(String text, String method) {
    var kept = new ArrayList<String>();
    boolean blanking = false;
    for (var line : text.split("\n", -1)) {
      if (line.startsWith("  static ")) {
        var name = METHOD_ON.computeIfAbsent(line, CardTruthTest::methodOn);
        if (!name.isEmpty()) {
          blanking = !name.equals(method) && !HELPERS.contains(name);
        }
      }
      kept.add(blanking ? "" : line);
      if (line.equals("  }")) {
        blanking = false;
      }
    }
    return String.join("\n", kept);
  }

  /// The method a line declares, or nothing.
  private static String methodOn(String line) {
    var header = METHOD.matcher(line);
    return header.matches() ? header.group("name") : "";
  }

  /// The fixture and its other class, compiled.
  private static Map<String, byte[]> original() throws IOException {
    return Javac.compile(
        List.of(Javac.Source.read(SOURCE, CLASS), Javac.Source.read(OTHER, "shapes.Other")),
        annotations());
  }

  /// The fixture's other class and the annotations, for a claim's source to compile beside.
  private static List<Javac.Source> alongside() throws IOException {
    var alongside = new ArrayList<>(List.of(Javac.Source.read(OTHER, "shapes.Other")));
    alongside.addAll(annotations());
    return alongside;
  }

  private static List<Javac.Source> annotations() throws IOException {
    var sources = new ArrayList<Javac.Source>();
    for (var annotation : ANNOTATIONS) {
      sources.add(
          Javac.Source.read(FIXTURE.resolve(annotation.replace('.', '/') + ".java"), annotation));
    }
    return sources;
  }

  /// The source with the card's claim written into the mutant's method.
  private static Optional<String> claim(
      String text, String withValues, Mutant mutant, Advice advice) {
    Optional<String[]> edit =
        switch ((BuiltIn) mutant.operator()) {
          case REMOVE_CONDITIONAL ->
              match(ALWAYS, advice.change())
                  .map(
                      found ->
                          new String[] {
                            found.group("condition"),
                            "Boolean." + found.group("value").toUpperCase(java.util.Locale.ROOT)
                          });
          case BOUNDARY, COMPARISON, OTHER_COMPARISON ->
              match(REVERSED, advice.headline())
                  .map(found -> new String[] {found.group("condition"), "!(%s)"})
                  .or(() -> boundary(mutant, advice));
          case LOGICAL_CONNECTOR -> connector(text, advice);
          default -> Optional.empty();
        };
    return edit.flatMap(found -> replaceIn(withValues, mutant, found[0], found[1]));
  }

  /// A comparison's headline, that the mutant differs only where `a > b`, written in: the condition
  /// becomes the mutant's there and stays as it is elsewhere, which must behave as the mutant does.
  /// A headline naming the wrong side fails, as the inputs on the right one then differ.
  private static Optional<String> headline(String withValues, Mutant mutant, Advice advice) {
    if (mutant.operator() == BuiltIn.REMOVE_CONDITIONAL) {
      return Optional.empty();
    }
    var where = match(WHERE, advice.headline());
    return where.flatMap(
        found ->
            boundary(mutant, advice)
                .flatMap(
                    edit ->
                        replaceIn(
                            withValues,
                            mutant,
                            edit[0],
                            "((%s) ? (%s) : (%%s))".formatted(found.group("where"), edit[1]))));
  }

  /// `a > 0 && b < 2` to `a > 0 || b < 2`, from the sides the headline names; or the connector
  /// alone, `&&` to `||`, found once in the method.
  private static Optional<String[]> connector(String text, Advice advice) {
    if (advice.before().isEmpty() || advice.after().isEmpty()) {
      return Optional.empty();
    }
    var before = advice.before().get();
    var after = advice.after().get();
    return match(SIDES, advice.headline())
        .map(
            found -> {
              // The card quotes the sides as written; the claim is found with constants as values.
              var left = withConstantsAsValues(text, found.group("left"));
              var right = withConstantsAsValues(text, found.group("right"));
              return new String[] {
                "%s %s %s".formatted(left, before, right), "%s %s %s".formatted(left, after, right)
              };
            })
        .or(() -> Optional.of(new String[] {before, after}));
  }

  /// `a >= b` to `a > b`: the comparison as the card says it's written, and as it says it becomes.
  private static Optional<String[]> boundary(Mutant mutant, Advice advice) {
    var operands = mutant.operands();
    if (operands.size() != 2 || advice.before().isEmpty() || advice.after().isEmpty()) {
      return Optional.empty();
    }
    var before = "%s %s %s".formatted(operands.get(0), advice.before().get(), operands.get(1));
    var after = "%s %s %s".formatted(operands.get(0), advice.after().get(), operands.get(1));
    return Optional.of(new String[] {before, after});
  }

  private static Optional<java.util.regex.Matcher> match(Pattern pattern, String text) {
    var found = pattern.matcher(text);
    return found.find() ? Optional.of(found) : Optional.empty();
  }

  /// `text`, with its constants as values, with `condition` replaced by `replacement`, in which
  /// `%s` stands for the condition itself: where it's found once, as whole words, on the mutant's
  /// line, or else in its method.
  private static Optional<String> replaceIn(
      String text, Mutant mutant, String condition, String replacement) {
    var written = condition;
    var words = Pattern.compile("(?<!\\w)" + Pattern.quote(written) + "(?!\\w)");
    var lines = text.lines().toList();
    var line = lines.get(mutant.line() - 1);
    // Where the mutant's own line starts: the same text can be on another method's line too.
    int lineStart =
        lines.subList(0, mutant.line() - 1).stream().mapToInt(each -> each.length() + 1).sum();
    var onLine = once(words, line);
    if (onLine.isPresent() && lineStart >= 0) {
      int at = lineStart + onLine.getAsInt();
      return Optional.of(
          text.substring(0, at)
              + replacement.replace("%s", written)
              + text.substring(at + written.length()));
    }
    return replaceInMethod(text, mutant, words, written, replacement);
  }

  private static Optional<String> replaceInMethod(
      String text, Mutant mutant, Pattern words, String written, String replacement) {
    var method = mutant.methodName();
    var start = text.indexOf(" " + method + "(int a, int b)");
    if (start < 0) {
      start = text.indexOf(" " + method + "(");
    }
    var end = text.indexOf("\n  static ", start + 1);
    end = end < 0 ? text.lastIndexOf('}') : end;
    var body = text.substring(start, end);
    var found = once(words, body);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    int at = found.getAsInt();
    var changed =
        body.substring(0, at)
            + replacement.replace("%s", written)
            + body.substring(at + written.length());
    return Optional.of(text.substring(0, start) + changed + text.substring(end));
  }

  /// The source with line `number` (from 1) replaced by `shown`, kept at its indentation. Its
  /// `true` and `false` are written `Boolean.TRUE` and `Boolean.FALSE`, the same values: javac
  /// won't take a constant as a `case` guard, and folds `while (true)` into a loop with no end.
  private static String withLines(String text, io.mutastic.report.Diff shown) {
    var lines =
        shown.appliedTo(
            text.lines().toList(),
            line ->
                LITERAL
                    .matcher(line)
                    .replaceAll(
                        found -> "Boolean." + found.group().toUpperCase(java.util.Locale.ROOT)));
    return String.join("\n", lines) + "\n";
  }

  /// `static final int EDGE = 3;` and each use of `EDGE` as `3`, as javac inlines it and a card
  /// quotes it, and `1L` as `1`: the same code, so a claim about `a > 3` can be found and written
  /// in. Line numbers stay as they were.
  private static String withConstantsAsValues(String text) {
    return withConstantsAsValues(text, text);
  }

  /// `fragment` with the constants `text` declares as their values.
  private static String withConstantsAsValues(String text, String fragment) {
    var constants = CONSTANT.matcher(text);
    var resolved = fragment;
    while (constants.find()) {
      var use = Pattern.compile("(?<![\\w.])" + constants.group("name") + "(?![\\w(])(?! = )");
      resolved = use.matcher(resolved).replaceAll(constants.group("value"));
    }
    // `1L` is a long `1` in the bytecode, as a widened int `1` is: a card can't tell which the
    // source wrote, and quotes `1`. The same code either way.
    return LONG_LITERAL.matcher(resolved).replaceAll("$1");
  }

  /// Where `words` is in `text`, if it's there exactly once.
  private static java.util.OptionalInt once(Pattern words, String text) {
    var found = words.matcher(text);
    if (!found.find()) {
      return java.util.OptionalInt.empty();
    }
    int at = found.start();
    return found.find() ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(at);
  }

  private static String renamed(String text, String variant) {
    return text.replace("class Shapes ", "class " + variant + " ")
        .replace("private Shapes()", "private " + variant + "()");
  }

  /// The first input where what the card's source gave and what the mutant did aren't the same.
  private static Optional<String> firstDifference(List<String> said, List<String> did) {
    int inputs = HIGH - LOW + 1;
    for (int at = 0; at < said.size(); at++) {
      if (!said.get(at).equals(did.get(at))) {
        return Optional.of(
            "a = %d, b = %d (the card's source gives %s, the mutant %s)"
                .formatted(LOW + at / inputs, LOW + at % inputs, said.get(at), did.get(at)));
      }
    }
    return Optional.empty();
  }

  /// What `method` gives on every input, `a` then `b`, each from `LOW` to `HIGH`.
  private static List<String> outcomes(String method, Class<?> type)
      throws ReflectiveOperationException {
    var called = method(type, method);
    var outcomes = new ArrayList<String>();
    for (int a = LOW; a <= HIGH; a++) {
      for (int b = LOW; b <= HIGH; b++) {
        outcomes.add(outcome(called, a, b));
      }
    }
    return outcomes;
  }

  private static Method method(Class<?> owner, String name) throws NoSuchMethodException {
    var method = owner.getDeclaredMethod(name, int.class, int.class);
    method.setAccessible(true);
    return method;
  }

  private static String outcome(Method method, int a, int b) throws IllegalAccessException {
    try {
      return Objects.toString(method.invoke(null, a, b));
    } catch (InvocationTargetException thrown) {
      return "threw " + thrown.getCause().getClass().getSimpleName();
    }
  }

  private static String describe(Mutant mutant, Advice advice) {
    return "%s %s %s -> %s %s: %s; %s"
        .formatted(
            mutant.methodName(),
            mutant.operator().id(),
            mutant.original(),
            mutant.replacement(),
            mutant.operands(),
            advice.change(),
            advice.headline());
  }

  /// The mutated class from its bytes, and the fixture's other classes from where they were
  /// compiled, all in one loader: they share a package, and its package-private members.
  private static final class OneClass extends ClassLoader {
    private final String name;
    private final byte[] bytes;
    private final Map<String, byte[]> classes;

    OneClass(String name, byte[] bytes, Map<String, byte[]> classes) {
      super(CardTruthTest.class.getClassLoader());
      this.name = name;
      this.bytes = bytes.clone();
      this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
      if (!className.startsWith("shapes.")) {
        return super.loadClass(className, resolve);
      }
      synchronized (getClassLoadingLock(className)) {
        var loaded = findLoadedClass(className);
        if (loaded != null) {
          return loaded;
        }
        var defined = className.equals(name) ? bytes : compiled(className);
        return defineClass(className, defined, 0, defined.length);
      }
    }

    private byte[] compiled(String className) throws ClassNotFoundException {
      var compiled = classes.get(className);
      if (compiled == null) {
        throw new ClassNotFoundException(className);
      }
      return compiled;
    }
  }
}
