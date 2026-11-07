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

  /// The start of each of the fixture's methods, which are written with two spaces before `static`
  /// and end at the first line that is two spaces and a `}`.
  private static final Pattern METHOD = Pattern.compile("  static .*?(?<name>\\w+)\\(.*\\{");

  /// What [#METHOD] finds on each line it's been given: the same few lines, once per claim.
  private static final Map<String, String> METHOD_ON = new HashMap<>();

  /// The predicates the conditions call: not conditions of their own.
  private static final Set<String> HELPERS = Set.of("even", "small", "atLeastOne", "isAbove");

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
      var shown = sources.mutatedLine(mutant, advice);
      if (shown.isPresent()) {
        claims.add(
            claimOf(
                mutant,
                advice,
                withLine(text, mutant.line(), shown.get()),
                "its changed line, `" + shown.get() + "`"));
      }
    }

    // The other class whose static a condition reads is there when they're compiled and run.
    var other = Javac.Source.read(OTHER, "shapes.Other");
    // The claims about one mutant are all checked against what it does, found once.
    var whatClaimsSay = whatClaimsSay(claims, other, original);
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
        .contains("its changed line, `if (b < 0 || a >= EDGE) {`");
    assertThat(failures).as("cards that say something their mutant doesn't do").isEmpty();
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
      firstDifference(
              outcomes(method, original.get(CLASS), original),
              outcomes(method, mutations.apply(model, mutant), original))
          .ifPresent(
              input -> failures.add(mutant + " (" + mutant.skipped().get() + ") at " + input));
    }

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
        .contains("clampLow", "clampZero", "clampLong", "raiseTo")
        .doesNotContain("notAClamp", "clampToSomethingElse");
    assertThat(equivalent)
        .as("comparisons of a size or length that differ only below zero")
        .filteredOn(mutant -> mutant.skipped().get().equals(Comparisons.NEVER_NEGATIVE))
        .extracting(Mutant::methodName)
        .contains("lengthNotZero", "sizeAboveZero");
    assertThat(failures).as("mutants skipped as equivalent that aren't").isEmpty();
  }

  /// No jump gets the same change twice: two mutants of one condition that behave the same on every
  /// input are one mutant counted twice. A size compared with zero is where it would happen
  /// (`size() > 0` to `size() >= 0` is the condition always true). Only those shapes are checked:
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
          || !Set.of("sizeAboveZero", "lengthNotZero").contains(mutant.methodName())) {
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

    assertThat(bySite).containsKeys("sizeAboveZero@0", "lengthNotZero@0");
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
      List<Claim> claims, Javac.Source other, Map<String, byte[]> original) throws Exception {
    var said = new HashMap<String, List<String>>();
    var unseen = new java.util.LinkedHashMap<String, Claim>();
    for (var claim : claims) {
      Kept.find(saidKey(claim, other))
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
                  List.of(other)));
      classes.put("shapes.Other", original.get("shapes.Other"));
      var loader = Javac.loader(classes);
      for (var claim : unseen.values()) {
        var outcomes =
            outcomes(claim.mutant().methodName(), loader.loadClass("shapes." + claim.variant()));
        Kept.keep(saidKey(claim, other), outcomes);
        said.put(claim.variant(), outcomes);
      }
    }
    return said;
  }

  private static List<String> saidKey(Claim claim, Javac.Source other) {
    return List.of("said", claim.source(), claim.mutant().methodName(), other.text());
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
        List.of());
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
    var line = text.lines().toList().get(mutant.line() - 1);
    int lineStart = text.indexOf(line + "\n");
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
  private static String withLine(String text, int number, String shown) {
    var lines = new ArrayList<>(text.lines().toList());
    var old = lines.get(number - 1);
    var boxed =
        LITERAL
            .matcher(shown)
            .replaceAll(found -> "Boolean." + found.group().toUpperCase(java.util.Locale.ROOT));
    lines.set(number - 1, old.substring(0, old.length() - old.stripLeading().length()) + boxed);
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
