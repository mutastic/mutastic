package io.mutastic.api;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/// mutastic's own arid rules: each a kind of code a test could check but shouldn't have to, by the
/// id that output, `mutastic.yaml` and the docs name it by. An id never changes once shipped: a
/// `mutate:` rule's `arid:` list names it, and docs.mutastic.io has a section for it at
/// [#anchor()]. Both editions share the ids (`docs/public/contract/v1.json`, `arid`).
///
/// A rule is known by the reasons it gives, as a skipped mutant shows them: `arid: only waits`.
@Internal
public enum AridRule {
  TRIVIAL_ACCESSORS(
      "mutastic:trivial-accessors",
      Scope.METHOD,
      "A getter or setter that only reads or writes one of its class's fields.",
      "String getName() { return name; }",
      "arid: trivial getter of <field>",
      "arid: trivial setter of <field>"),
  DEBUG_REPRESENTATIONS(
      "mutastic:debug-representations",
      Scope.METHOD,
      "`toString()` and `hashCode()`, the lambdas they define and the helpers only they call.",
      "public String toString() { return \"Order \" + id; }",
      "arid: `toString()` is a debug representation",
      "arid: `hashCode()` may return any value consistent with `equals()`",
      "arid: a lambda in `<method>()`",
      "arid: `<helper>()` only serves `<method>()`"),
  OUTPUT(
      "mutastic:output",
      Scope.SITE,
      "Code that only writes output: a log line, or text printed to the console.",
      "System.out.println(\"Saved \" + id);",
      "arid: only writes output"),
  MESSAGES(
      "mutastic:messages",
      Scope.SITE,
      "The text of an exception's message, or of a failed check's.",
      "throw new IllegalArgumentException(\"amount must be positive\");",
      "arid: only an exception's message",
      "arid: only a failed check's message"),
  FAILED_ASSERTS(
      "mutastic:failed-asserts",
      Scope.SITE,
      "The `throw` of an `assert` that fails.",
      "assert total >= 0 : \"negative total\";",
      "arid: only says an assert failed"),
  CAPACITY(
      "mutastic:capacity",
      Scope.SITE,
      "The size a collection or a buffer starts with.",
      "new ArrayList<>(orders.size() * 2)",
      "arid: only sizes a collection's first allocation"),
  WAIT_TIMES(
      "mutastic:wait-times",
      Scope.SITE,
      "How long a sleep, or a `join` with a time limit, waits.",
      "Thread.sleep(seconds * 1000L);",
      "arid: only sets how long to wait"),
  WAITS(
      "mutastic:waits",
      Scope.SITE,
      "A sleep, or a `join` with a time limit, removed.",
      "Thread.sleep(100);",
      "arid: only waits"),
  NULL_CHECKS(
      "mutastic:null-checks",
      Scope.SITE,
      "A call that only checks a value is there, its result unused, removed:"
          + " `Objects.requireNonNull(x)` or `x.getClass()` in Java, `x.unwrap()` or"
          + " `x.expect(…)` in Rust.",
      "Objects.requireNonNull(order);",
      "arid: only checks the value isn't null"),
  ARRAY_SIZES(
      "mutastic:array-sizes",
      Scope.SITE,
      "The size of a new array, written as a number.",
      "byte[] buffer = new byte[8192];",
      "arid: only sizes an array"),
  FIRST_VALUES(
      "mutastic:first-values",
      Scope.SITE,
      "A variable's first value of 0 or 1, where a counter or a total starts.",
      "int count = 0;",
      "arid: a variable's first value of 0 or 1"),
  SPLIT_LIMITS(
      "mutastic:split-limits",
      Scope.SITE,
      "The limit of a `String.split`.",
      "line.split(\",\", 2)",
      "arid: only limits a split"),
  THREAD_NAMES(
      "mutastic:thread-names",
      Scope.SITE,
      "A thread's or a pool's name, which only people and their tools read: a thread dump, a"
          + " profiler, a log pattern.",
      "thread.setName(\"background-task\");",
      "arid: only names a thread"),
  THREAD_PRIORITIES(
      "mutastic:thread-priorities",
      Scope.SITE,
      "A thread's priority from 1 to 10: a hint, which Linux ignores by default and a virtual"
          + " thread always does.",
      "thread.setPriority(Thread.MAX_PRIORITY);",
      "arid: only sets a priority hint"),
  KEEP_ALIVE_TIMES(
      "mutastic:keep-alive-times",
      Scope.SITE,
      "How long a pool's idle threads live before they stop, while it stays above zero.",
      "pool.setKeepAliveTime(30, TimeUnit.SECONDS);",
      "arid: only sets how long idle threads live"),
  STACK_SIZES(
      "mutastic:stack-sizes",
      Scope.SITE,
      "The size of a thread's stack, while it stays above zero.",
      "Thread.ofPlatform().stackSize(1 << 20).unstarted(task);",
      "arid: only sizes a thread's stack");

  /// What a rule judges: a whole method, every mutant in it, or one site, a call or a value.
  public enum Scope {
    METHOD,
    SITE;

    /// `method`, `site`: as the contract and the docs' data say it.
    public String written() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /// The path of the docs page with a section for each rule, under the docs' base.
  public static final String PAGE = "reference/arid/";

  private final String id;
  private final Scope scope;
  private final String description;
  private final String example;

  // Always a List.of, which can't change: the check goes by the declared type, which can.
  @SuppressWarnings("ImmutableEnumChecker")
  private final List<String> reasons;

  @SuppressWarnings("ImmutableEnumChecker")
  private final List<Pattern> patterns;

  AridRule(String id, Scope scope, String description, String example, String... reasons) {
    this.id = id;
    this.scope = scope;
    this.description = description;
    this.example = example;
    this.reasons = List.of(reasons);
    this.patterns = Arrays.stream(reasons).map(AridRule::pattern).toList();
  }

  /// `mutastic:waits`.
  public String id() {
    return id;
  }

  /// `waits`: the id's name, after its namespace, which is the id of its section on the docs page,
  /// as an operator's section is named.
  public String anchor() {
    return id.substring(id.indexOf(':') + 1);
  }

  /// `https://docs.mutastic.io/reference/arid/#waits`, under the docs of the mutastic running.
  public String url(String docs) {
    return docs + PAGE + "#" + anchor();
  }

  /// Whether it judges a whole method or one site.
  public Scope scope() {
    return scope;
  }

  /// What it skips, in a sentence.
  public String description() {
    return description;
  }

  /// A line of Java it skips a mutant of.
  public String example() {
    return example;
  }

  /// The reasons it gives, as a skipped mutant shows them, a name in the code as `<field>`.
  public List<String> reasons() {
    return reasons;
  }

  /// The rule that gives `reason` (`arid: only waits`), if one of mutastic's own does.
  public static Optional<AridRule> of(String reason) {
    return Arrays.stream(values())
        .filter(rule -> rule.patterns.stream().anyMatch(each -> each.matcher(reason).matches()))
        .findFirst();
  }

  /// The rule with id `id`, `mutastic:waits`.
  public static Optional<AridRule> named(String id) {
    return Arrays.stream(values()).filter(rule -> rule.id.equals(id)).findFirst();
  }

  /// Every rule's id, in order.
  public static List<String> ids() {
    return Arrays.stream(values()).map(AridRule::id).toList();
  }

  /// A reason as a pattern: `<field>` is any name.
  private static Pattern pattern(String reason) {
    return Pattern.compile(
        Arrays.stream(reason.split("<[a-z]+>", -1))
            .map(Pattern::quote)
            .reduce((before, after) -> before + "[^`\\s]+" + after)
            .orElseThrow());
  }
}
