package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The operators mutastic makes as plugins, each run on real output of what it's about: real
/// Hibernate Validator and Spring Security reading annotations, real kotlinc, real Reactor. A
/// card's claim that no test needs something is checked both ways: the mutant runs and every test
/// passes; and the test the card asks for, added, catches it.
class PluginOperatorsTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final Path JAR = Path.of(System.getProperty("mutastic.pluginsJar"));
  private static final Path RULES =
      Path.of(System.getProperty("mutastic.docs")).resolve("../../data/plugins").normalize();

  @TempDir Path work;

  /// What reads an annotation here keeps what it read: a validator in a static field of the
  /// project's, and a Spring context. So what catches `@NotNull` on `name`, and the test a
  /// `@PreAuthorize` refuses, which never reaches a line of the method, show the mutant reached
  /// them.
  @Test
  void annotationsAreCaughtByWhatReadsThemAndMissedWhenNoTestNeedsThem() throws Exception {
    var project = withPlugin("annotations", "- " + JAR);

    var run = events(project, "--operators=annotations");

    assertThat(status(run, "annotations/remove-validation", "<field name>", "Order.java"))
        .isEqualTo("Killed");
    assertThat(status(run, "annotations/remove-validation", "<field name>", "Customer.java"))
        .isEqualTo("Killed");
    assertThat(status(run, "annotations/remove-validation", "<class>", "Transfers.java"))
        .isEqualTo("Killed");
    assertThat(status(run, "annotations/remove-validation", "send(", "Transfers.java"))
        .isEqualTo("Killed");
    assertThat(status(run, "annotations/remove-security", "pay(", "Payroll.java"))
        .isEqualTo("Killed");
    assertThat(status(run, "annotations/remove-validation", "<field quantity>", "Order.java"))
        .isEqualTo("Ignored");
    assertThat(status(run, "annotations/remove-validation", "<field code>", "Order.java"))
        .isEqualTo("Survived");
    assertThat(status(run, "annotations/remove-validation", "<field email>", "Customer.java"))
        .isEqualTo("Survived");
    assertThat(status(run, "annotations/remove-security", "audit(", "Payroll.java"))
        .isEqualTo("Survived");
  }

  @Test
  void theTestsAnAnnotationsCardsAskForCatchThem() throws Exception {
    var project = withPlugin("annotations", "- " + JAR);

    var agent = agent(project, "--operators=annotations");

    assertThat(agent)
        .contains("no test needs `@Size` on `code`")
        .contains("no test needs `@Email` on `email`")
        .contains("no test needs `@PreAuthorize` on `audit()`")
        .contains("run against every test; all still pass with the change")
        .doesNotContain("covered by:");
    var size = idOf(agent, "no test needs `@Size` on `code`");
    var email = idOf(agent, "no test needs `@Email` on `email`");
    var audit = idOf(agent, "no test needs `@PreAuthorize` on `audit()`");
    addTest(
        project,
        "src/test/java/com/example/orders/OrderTest.java",
        """
        @Test
        void aCodeHasThreeLettersAtMost() {
          assertThat(Validators.SHARED.validate(new Order("a", "abcd", 1))).hasSize(1);
        }
        """);
    addTest(
        project,
        "src/test/java/com/example/orders/CustomerTest.java",
        """
        @Test
        void anEmailIsChecked() {
          assertThat(Validators.SHARED.validate(new Customer("a", "not an email"))).hasSize(1);
        }
        """);
    addTest(
        project,
        "src/test/java/com/example/orders/PayrollTest.java",
        """
        @Test
        @WithMockUser(roles = "USER")
        void onlyAnAdminAudits() {
          assertThatThrownBy(payroll::audit).isInstanceOf(AccessDeniedException.class);
        }
        """);

    assertVerified(project, size);
    assertVerified(project, email);
    assertVerified(project, audit);
  }

  /// A project whose tests run in a framework that loads the app itself can't be reached: its
  /// mutants are skipped with the reason, not reported missed.
  @Test
  void annotationsAreSkippedWhereAFrameworkLoadsTheApp() throws Exception {
    var project = withPlugin("annotations", "- " + JAR);
    var build = project.resolve("build.gradle.kts");
    Files.writeString(
        build,
        Files.readString(build)
            .replace(
                "dependencies {\n",
                "dependencies {\n    testRuntimeOnly(files(\"quarkus-junit5-stand-in.jar\"))\n"));
    Files.write(project.resolve("quarkus-junit5-stand-in.jar"), new byte[0]);

    var found =
        Samples.mutastic(
            "run",
            project.toString(),
            "--operators=annotations",
            "--format=jsonl",
            "--report=none");

    assertThat(found.out()).contains("can't reach what it has read");
  }

  /// `Intrinsics.checkNotNullParameter`, and a data class's `copy`, `componentN` and `equals`, are
  /// kotlinc's and are skipped. An empty range or sequence no test looks into is missed; one that's
  /// looked at is caught; one that's already what's returned is skipped.
  @Test
  void kotlinsEmptyReturnsAndItsOwnCodeOnRealKotlincOutput() throws Exception {
    var project = withPlugin("kotlin", "- " + JAR);

    var run = events(project, "--operators=defaults,kotlin");

    assertThat(lines(run))
        .filteredOn(line -> line.contains("\"status\":\"NoCoverage\""))
        .noneMatch(
            line -> line.contains("\"method\":\"copy") || line.contains("\"method\":\"equals"))
        .noneMatch(line -> line.contains("remove-void-call"));
    assertThat(run)
        .contains("generated: kotlinc's own check", "generated: kotlinc wrote it")
        .doesNotContain(".java\"")
        .contains("\"file\":\"src/main/kotlin/com/example/shelf/Shelf.kt\"");
    var kotlin = "kotlin/empty-return";
    assertThat(status(run, kotlin, "slots(", "Shelf.kt")).isEqualTo("Killed");
    assertThat(status(run, kotlin, "none(", "Shelf.kt")).isEqualTo("Ignored");
    assertThat(status(run, kotlin, "noLabels(", "Shelf.kt")).isEqualTo("Ignored");
    assertThat(status(run, kotlin, "letters(", "Shelf.kt")).isEqualTo("Survived");
    assertThat(status(run, kotlin, "positions(", "Shelf.kt")).isEqualTo("Survived");
    assertThat(status(run, kotlin, "labels(", "Shelf.kt")).isEqualTo("Survived");
  }

  @Test
  void theTestsKotlinsCardsAskForCatchThem() throws Exception {
    var project = withPlugin("kotlin", "- " + JAR);

    var agent = agent(project, "--operators=kotlin");

    assertThat(agent)
        .contains("no test looks at what's in the range `letters()` returns")
        .contains("no test looks at what's in the range `positions()` returns")
        .contains("no test looks at what's in the sequence `labels()` returns");
    var letters = idOf(agent, "the range `letters()` returns");
    var positions = idOf(agent, "the range `positions()` returns");
    var labels = idOf(agent, "the sequence `labels()` returns");
    addKotlinTest(
        project,
        """
        @Test
        fun whatTheRangesAndSequencesHold() {
            assertEquals(listOf('a', 'b'), shelf.letters("ab").toList())
            assertEquals(listOf(1L, 2L, 3L), shelf.positions(1).toList())
            assertEquals(listOf("a-0", "a-1", "a-2"), shelf.labels("a").toList())
        }
        """);

    assertVerified(project, letters);
    assertVerified(project, positions);
    assertVerified(project, labels);
  }

  @Test
  void rulesFilesForBigDecimalAndReactiveStreams() throws Exception {
    var project = withPlugin("money", "- bigdecimal.yaml", "- reactive.yaml");
    Files.copy(RULES.resolve("bigdecimal.yaml"), project.resolve("bigdecimal.yaml"));
    Files.copy(RULES.resolve("reactive.yaml"), project.resolve("reactive.yaml"));

    var agent = agent(project, "--operators=bigdecimal,reactive");

    var cards =
        List.of(
            "no test tells `subtract` from `add` in refund()",
            "no test tells `min` from `max` in cheapest()",
            "no test has a negative value for `.abs()` to change in owed()",
            "no test tells `multiply` from `divide` in scaled()",
            "no test needs `.take(…)` in firstTwo()",
            "no test makes orZero() fail, so `.onErrorReturn(…)` is never needed",
            "no test tells `any` from `all` in anyLarge()",
            "no test tells `flatMap` from `concatMap` in arrivals()");
    assertThat(agent)
        .contains(cards)
        .doesNotContain("in total()", "`.filter(…)`", "`.setScale(…)`");
    var ids = cards.stream().map(card -> idOf(agent, card)).toList();
    addTest(
        project,
        "src/test/java/com/example/money/InvoiceTest.java",
        """
        @Test
        void whatEachCardAsksFor() {
          assertEquals(money("8"), Invoice.refund(money("10"), money("2")));
          assertEquals(money("1"), Invoice.cheapest(money("1"), money("3")));
          assertEquals(money("3"), Invoice.owed(money("-3")));
          assertEquals(money("10"), Invoice.scaled(money("5"), money("2")));
        }
        """);
    addTest(
        project,
        "src/test/java/com/example/money/PaymentsTest.java",
        """
        @Test
        void whatEachCardAsksFor() {
          assertEquals(List.of(1, 2), Payments.firstTwo(Flux.just(1, 2, 3)));
          assertEquals(0, Payments.orZero(Mono.error(new IllegalStateException())));
          assertEquals(true, Payments.anyLarge(Flux.just(1, 100)));
          assertEquals(List.of(1, 200), Payments.arrivals(Flux.just(200, 1)));
        }
        """);

    ids.forEach(id -> assertVerified(project, id));
  }

  /// A stub of what a model would do, as files: the rewrites it might give, good and bad. Each
  /// reaches a mutant, or a skip that says why.
  @Test
  void proposedRewritesGoThroughTheCompileAndDuplicateGates() throws Exception {
    var project = withPlugin("pricing", "- " + JAR);
    var source =
        Files.readString(project.resolve("src/main/java/com/example/pricing/Pricing.java"));
    var proposals = project.resolve(".mutastic/proposed/com.example.pricing.Pricing");
    Files.createDirectories(proposals);
    propose(proposals, "a-boundary", source.replace("total >= 100", "total > 100"));
    propose(
        proposals,
        "b-boundary-reworded",
        source.replace("total >= 100", "total  >  100").replace("10% off", "A tenth off"));
    propose(proposals, "c-comment", source.replace("10% off", "A tenth off"));
    propose(proposals, "d-halved", source.replace("total / 10", "total / 20"));
    propose(proposals, "e-broken", source.replace("total >= 100", "total >= 100 +"));
    propose(
        proposals,
        "f-two-methods",
        source.replace("total / 10", "total / 20").replace("return 5;", "return 6;"));
    propose(
        proposals,
        "g-more-methods",
        source.replaceFirst("}\\s*$", "public int more() { return 1; } }"));
    propose(proposals, "h-another-class", source + "\nclass Helper {}\n");

    var run = events(project, "--operators=proposed");

    assertThat(replaced(run, "a-boundary")).contains("\"status\":\"Survived\"");
    assertThat(replaced(run, "b-boundary-reworded"))
        .contains("equivalent: compiles to the same code as `a-boundary`");
    assertThat(replaced(run, "c-comment")).contains("equivalent: it compiles to the same code");
    assertThat(replaced(run, "d-halved")).contains("\"status\":\"Killed\"");
    assertThat(replaced(run, "e-broken")).contains("unviable: it doesn't compile: line");
    assertThat(replaced(run, "f-two-methods"))
        .contains("unviable: it changes more than one method");
    assertThat(replaced(run, "g-more-methods")).contains("unviable: its fields or methods");
    assertThat(replaced(run, "h-another-class")).contains("unviable: it makes different classes");
    var agent = agent(project, "--operators=proposed");
    assertThat(agent)
        .contains(
            "no test tells `discount()` from the rewrite in"
                + " `.mutastic/proposed/com.example.pricing.Pricing/a-boundary.java`")
        .contains("if it changes what `discount()` does, add a test it fails; if it does the same");
    var id = idOf(agent, "a-boundary.java`");
    addTest(
        project,
        "src/test/java/com/example/pricing/PricingTest.java",
        """
        @Test
        void aHundredGetsItsDiscount() {
          assertEquals(10, new Pricing().discount(100));
        }
        """);
    assertVerified(project, id);
  }

  /// The example in the Plugins guide, as it's written there.
  @Test
  void aPluginJarAddsAnOperatorOnAClassAsAWhole() throws Exception {
    var project = withPlugin("operators", "- plugins/undeprecate.jar");
    PluginJars.write(
        project.resolve("plugins/undeprecate.jar"),
        io.mutastic.api.ClassMutator.class,
        "acme.Undeprecate",
        guideExample());
    var orders = project.resolve("src/main/java/com/example/orders/Orders.java");
    Files.writeString(
        orders,
        Files.readString(orders).replace("public class Orders", "@Deprecated public class Orders"));

    var agent = agent(project, "--operators=acme");

    assertThat(agent)
        .contains("no test needs com/example/orders/Orders to be deprecated")
        .contains("run against every test");
  }

  /// The guide's `Undeprecate`, from the first line of its code to its last.
  private static String guideExample() throws IOException {
    var guide =
        Files.readString(RULES.resolve("../../content/docs/guides/plugins.mdx").normalize());
    var start = guide.indexOf("package acme;", guide.indexOf("## Changing a class as a whole"));
    return guide.substring(start, guide.indexOf("```", start));
  }

  private Path withPlugin(String sample, String... plugins) throws IOException {
    var project = Samples.copy(SAMPLES.resolve(sample), work.resolve(sample));
    Files.writeString(
        project.resolve("mutastic.yaml"), "plugins:\n  " + String.join("\n  ", plugins) + "\n");
    return project;
  }

  private static String events(Path project, String operators) {
    var run =
        Samples.mutastic("run", project.toString(), operators, "--format=jsonl", "--report=none");
    assertThat(run.err()).doesNotContain("mutastic:");
    return run.out();
  }

  private static String agent(Path project, String operators) {
    return Samples.mutastic("run", project.toString(), operators, "--format=agent", "--report=none")
        .out();
  }

  private static List<String> lines(String events) {
    return events.lines().toList();
  }

  /// The status of the one mutant the operator made on that member, in that file.
  private static String status(String events, String operator, String member, String file) {
    var found =
        lines(events).stream()
            .filter(
                line ->
                    line.contains("\"operator\":\"" + operator + "\"")
                        && line.contains("\"method\":\"" + member)
                        && line.contains(file + "\""))
            .toList();
    assertThat(found).as(operator + " " + member + " " + file + "\n" + events).hasSize(1);
    var matched = Pattern.compile("\"status\":\"(?<status>\\w+)\"").matcher(found.getFirst());
    assertThat(matched.find()).isTrue();
    return matched.group("status");
  }

  private static String replaced(String events, String proposal) {
    return lines(events).stream()
        .filter(line -> line.contains("-> " + proposal + "\""))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no mutant for " + proposal + "\n" + events));
  }

  /// The id of the missed mutant whose card's first line says `words`.
  private static String idOf(String agent, String words) {
    return agent
        .lines()
        .filter(line -> line.matches("\\d+\\. missed m[0-9a-f]{8}: .*") && line.contains(words))
        .findFirst()
        .map(
            line -> line.substring(line.indexOf("missed ") + "missed ".length(), line.indexOf(':')))
        .orElseThrow(() -> new AssertionError("no card says " + words + "\n" + agent));
  }

  private static void addTest(Path project, String file, String test) throws IOException {
    var path = project.resolve(file);
    var source = Files.readString(path);
    Files.writeString(path, source.substring(0, source.lastIndexOf('}')) + test.indent(2) + "}\n");
  }

  private static void addKotlinTest(Path project, String test) throws IOException {
    addTest(project, "src/test/kotlin/com/example/shelf/ShelfTest.kt", test);
  }

  private static void propose(Path directory, String name, String source) throws IOException {
    Files.writeString(directory.resolve(name + ".java"), source);
  }

  private static void assertVerified(Path project, String id) {
    var verified = Samples.mutastic("verify", id, project.toString());
    assertThat(verified.exit())
        .as(id + "\n" + verified.out() + verified.err())
        .isEqualTo(Main.PASSED);
  }
}
