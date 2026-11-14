package io.mutastic.cli;

import static io.mutastic.cli.EditionContract.strings;
import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.cli.EditionContract.Found;
import io.mutastic.report.Schemas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// The Java edition keeps the editions' contract (docs/public/contract/v1.json): its command line,
/// its help's layout, the error code and exit code of each error case, the human summary's rows,
/// the agent format's lines, the events' key paths and order, and mutation.json's key paths. Each
/// is found by running mutastic as a person does, on a copy of the pricing sample, and compared
/// with the contract: a difference the contract doesn't record fails, naming the item and what to
/// do, and so does a recorded one that has gone.
///
/// The Rust edition runs the same checks on its own binary and fixture (mutastic-rs's
/// tests/contract.rs), so neither shares the other's code. CONTRIBUTING.md says how it works.
class ContractTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  /// The pricing sample's missed mutant.
  private static final String ID = "m855c8e3c";

  private static final EditionContract CONTRACT = EditionContract.read();

  @TempDir static Path work;

  private static Path project;

  @BeforeAll
  static void aProjectInGit() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("fixture"));
    git(project, "init", "-q", "-b", "main");
    git(project, "add", "-A");
    git(project, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "start");
    Files.createDirectories(work.resolve("empty"));
  }

  @Test
  void theContractIsWellFormed() throws IOException {
    var text = Files.readString(EditionContract.FILE, StandardCharsets.UTF_8);

    assertThat(Schemas.yamlProblems("contract/v1.json", text)).isEmpty();
    var ids = strings(ids());
    assertThat(ids).doesNotHaveDuplicates();
    assertThat(strings(CONTRACT.get("editions"))).contains(EditionContract.EDITION);
  }

  private static JsonNode ids() {
    var ids = JsonMapper.shared().createArrayNode();
    CONTRACT.get("differences").values().forEach(difference -> ids.add(difference.get("id")));
    return ids;
  }

  @Test
  void theSchemasAreTheContracts() throws Exception {
    var found = new ArrayList<Found>();
    var schemas = CONTRACT.get("schemas");
    schema(
        found,
        schemas,
        "events",
        Files.newInputStream(Path.of(System.getProperty("mutastic.schemas"), "events/v1.json")));
    schema(
        found,
        schemas,
        "report",
        Schemas.class.getResourceAsStream("schema/mutation-testing-report-schema.json"));
    schema(found, schemas, "sarif", Schemas.class.getResourceAsStream("schema/sarif-2.1.0.json"));

    assertThat(CONTRACT.unrecorded(found, List.of("schema"))).isEmpty();
  }

  private static void schema(List<Found> found, JsonNode schemas, String name, InputStream copy)
      throws Exception {
    try (copy) {
      var hash =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(copy.readAllBytes()));
      if (!hash.equals(schemas.get(name).get("sha256").asString())) {
        found.add(new Found("schema:" + name, "this edition's copy has sha256 " + hash));
      }
    }
  }

  @Test
  void theCommandLineIsTheContracts() {
    var model =
        JsonMapper.shared()
            .readTree(
                Path.of(System.getProperty("mutastic.docs"))
                    .resolve("../../data/commands.json")
                    .toFile());
    var found = new ArrayList<Found>();
    commands(found, byName(model.get("commands")));
    options(found, byName(model.get("options")));
    named(found, "env", strings(CONTRACT.get("environment")), environment(model));
    named(found, "exit", names(CONTRACT.get("exitCodes")), names(model.get("exitCodes")));

    assertThat(
            CONTRACT.unrecorded(
                found, List.of("command", "synopsis", "takes", "order", "option", "env", "exit")))
        .isEmpty();
  }

  private static void commands(List<Found> found, Map<String, JsonNode> model) {
    var contract = byName(CONTRACT.get("commands"));
    for (var command : contract.values()) {
      var name = command.get("name").asString();
      var mine = model.get(name);
      if (mine == null) {
        found.add(new Found("command:" + name, "the contract's command isn't in commands.json"));
      } else {
        command(found, command, mine);
      }
    }
    model.keySet().stream()
        .filter(name -> !contract.containsKey(name))
        .forEach(
            name -> found.add(new Found("command:" + name, "a command the contract doesn't have")));
  }

  private static void command(List<Found> found, JsonNode command, JsonNode mine) {
    var name = command.get("name").asString();
    var synopsis = mine.get("synopsis").asString();
    for (var slot : CONTRACT.get("engineSlots").values()) {
      synopsis = synopsis.replace(" " + slot.get("slot").asString(), "");
    }
    var shared = command.get("synopsis").asString();
    if (!synopsis.equals(shared)) {
      found.add(
          new Found("synopsis:" + name, "`%s`, the contract's `%s`".formatted(synopsis, shared)));
    }
    var takes = strings(command.get("options"));
    var mineTakes = strings(mine.get("options"));
    takes.stream()
        .filter(option -> !mineTakes.contains(option))
        .forEach(
            option ->
                found.add(
                    new Found(
                        "takes:%s:%s".formatted(name, option), name + " doesn't take " + option)));
    mineTakes.stream()
        .filter(option -> !takes.contains(option))
        .forEach(
            option ->
                found.add(
                    new Found(
                        "takes:%s:%s".formatted(name, option),
                        name + " takes " + option + ", which the contract doesn't give it")));
    var inMine = mineTakes.stream().filter(takes::contains).toList();
    var inContract = takes.stream().filter(mineTakes::contains).toList();
    if (!inMine.equals(inContract)) {
      found.add(
          new Found(
              "order:" + name,
              "options in the order %s, the contract's %s".formatted(inMine, inContract)));
    }
  }

  private static void options(List<Found> found, Map<String, JsonNode> model) {
    var contract = byName(CONTRACT.get("options"));
    for (var option : contract.values()) {
      var name = option.get("name").asString();
      var mine = model.get(name);
      if (mine == null) {
        found.add(new Found("option:" + name, "the contract's option isn't in commands.json"));
      } else {
        option(found, option, mine);
      }
    }
    model.keySet().stream()
        .filter(name -> !contract.containsKey(name))
        .forEach(
            name -> found.add(new Found("option:" + name, "an option the contract doesn't have")));
  }

  private static void option(List<Found> found, JsonNode option, JsonNode mine) {
    var name = "option:" + option.get("name").asString();
    same(found, name + ":kind", text(option, "kind"), text(mine, "kind"));
    same(found, name + ":value", text(option, "value"), text(mine, "value"));
    var choices = option.has("choices") ? strings(option.get("choices")) : List.<String>of();
    var mineChoices = strings(mine.get("choices"));
    named(found, name + ":choice", choices, mineChoices);
    var environment =
        option.has("environment") ? strings(option.get("environment")) : List.<String>of();
    var mineEnvironment =
        COMMAS
            .splitAsStream(text(mine, "environment"))
            .map(String::strip)
            .filter(each -> !each.isEmpty())
            .toList();
    if (!environment.equals(mineEnvironment)) {
      found.add(
          new Found(
              name + ":environment",
              "%s, the contract's %s".formatted(mineEnvironment, environment)));
    }
    if (option.has("default")) {
      var said = text(option, "default") + " unless set";
      if (!text(mine, "help").replace(", unless set", " unless set").contains(said)) {
        found.add(new Found(name + ":default", "its help doesn't say `" + said + "`"));
      }
    }
  }

  private static final Pattern COMMAS = Pattern.compile(",");

  private static void same(List<Found> found, String item, String contract, String mine) {
    if (!contract.equals(mine)) {
      found.add(new Found(item, "`%s`, the contract's `%s`".formatted(mine, contract)));
    }
  }

  /// Each of `contract`'s names this edition hasn't, and each of its own the contract hasn't.
  private static void named(
      List<Found> found, String surface, List<String> contract, List<String> mine) {
    contract.stream()
        .filter(name -> !mine.contains(name))
        .forEach(
            name ->
                found.add(
                    new Found(
                        surface + ":" + name, "the contract has it, and this edition doesn't")));
    mine.stream()
        .filter(name -> !contract.contains(name))
        .forEach(
            name ->
                found.add(
                    new Found(
                        surface + ":" + name, "this edition has it, and the contract doesn't")));
  }

  private static List<String> environment(JsonNode model) {
    return StreamSupport.stream(model.get("environment").values().spliterator(), false)
        .map(variable -> EQUALS.split(variable.get("name").asString(), 2)[0])
        .toList();
  }

  private static final Pattern EQUALS = Pattern.compile("=");

  /// An object's field names, or an array's `name`s.
  private static List<String> names(JsonNode node) {
    return node.isArray()
        ? StreamSupport.stream(node.values().spliterator(), false)
            .map(each -> each.get("name").asString())
            .toList()
        : List.copyOf(node.propertyNames());
  }

  private static Map<String, JsonNode> byName(JsonNode array) {
    var byName = new LinkedHashMap<String, JsonNode>();
    array.values().forEach(each -> byName.put(each.get("name").asString(), each));
    return byName;
  }

  private static String text(JsonNode node, String field) {
    return node.has(field) && !node.get(field).isNull() ? node.get(field).asString() : "";
  }

  @Test
  void theHelpIsLaidOutAsTheContractSays() {
    var help = CONTRACT.get("help");
    var found = new ArrayList<Found>();
    topHelp(found, help.get("top"));
    var none = Samples.mutastic();
    if (none.exit() != help.get("none").get("exit").asInt()
        || !none.out().isEmpty()
        || none.err().isEmpty()) {
      found.add(
          new Found(
              "help:none",
              "with no arguments it exits %d, %d characters on stdout and %d on stderr"
                  .formatted(none.exit(), none.out().length(), none.err().length())));
    }
    var model =
        byName(
            JsonMapper.shared()
                .readTree(
                    Path.of(System.getProperty("mutastic.docs"))
                        .resolve("../../data/commands.json")
                        .toFile())
                .get("commands"));
    for (var command : CONTRACT.get("commands").values()) {
      var name = command.get("name").asString();
      var options =
          model.containsKey(name) ? strings(model.get(name).get("options")) : List.<String>of();
      commandHelp(found, help.get("command"), name, options);
    }

    assertThat(CONTRACT.unrecorded(found, List.of("help"))).isEmpty();
  }

  private static void topHelp(List<Found> found, JsonNode top) {
    var run = Samples.mutastic("--help");
    var lines = run.out().lines().toList();
    if (run.exit() != 0 || lines.isEmpty()) {
      found.add(
          new Found(
              "help:top:exit",
              "--help exits %d with %d lines on stdout".formatted(run.exit(), lines.size())));
      return;
    }
    if (!find(top, "first", lines.getFirst())) {
      found.add(new Found("help:top:tagline", "its first line is `" + lines.getFirst() + "`"));
    }
    for (var section : strings(top.get("sections"))) {
      if (!lines.contains(section)) {
        found.add(new Found("help:top:section:" + section, "no `" + section + "` section"));
      }
    }
    if (!find(top, "last", lines.getLast())) {
      found.add(new Found("help:top:docs", "its last line is `" + lines.getLast() + "`"));
    }
  }

  private static void commandHelp(
      List<Found> found, JsonNode shape, String command, List<String> options) {
    var run = Samples.mutastic(arguments(command, "--help"));
    var lines = run.out().lines().toList();
    var item = "help:" + command;
    if (run.exit() != 0 || lines.isEmpty()) {
      found.add(
          new Found(
              item + ":exit",
              "it exits %d with %d lines on stdout".formatted(run.exit(), lines.size())));
      return;
    }
    if (!find(shape, "first", lines.getFirst())) {
      found.add(new Found(item + ":usage", "its first line is `" + lines.getFirst() + "`"));
    }
    if (lines.stream().noneMatch(line -> find(shape, "exits", line))) {
      found.add(new Found(item + ":exits", "no `Exits` paragraph"));
    }
    for (var section : strings(shape.get("sections"))) {
      if (!lines.contains(section)) {
        found.add(new Found(item + ":section:" + section, "no `" + section + "` section"));
      }
    }
    if (lines.stream().noneMatch(line -> find(shape, "docs", line))) {
      found.add(new Found(item + ":docs", "no `Docs:` link"));
    }
    var width = shape.get("width").asInt();
    var longest = lines.stream().mapToInt(String::length).max().orElse(0);
    if (longest > width) {
      found.add(
          new Found(item + ":width", "a line of %d columns, over %d".formatted(longest, width)));
    }
    for (var option : options) {
      var listed = Pattern.compile("^\\s+" + Pattern.quote(option) + "\\b");
      if (lines.stream().noneMatch(line -> listed.matcher(line).find())) {
        found.add(
            new Found(
                item + ":option:" + option, option + " is in commands.json, not in the help"));
      }
    }
  }

  private static boolean find(JsonNode shape, String field, String line) {
    return Pattern.compile(shape.get(field).asString()).matcher(line).find();
  }

  @Test
  void eachErrorEndsAsTheContractSays() throws Exception {
    var found = new ArrayList<Found>();
    for (var errorCase : CONTRACT.get("errorCases").values()) {
      errorCase(found, errorCase);
    }

    assertThat(CONTRACT.unrecorded(found, List.of("error"))).isEmpty();
  }

  private static void errorCase(List<Found> found, JsonNode errorCase) throws Exception {
    var dir = errorCase.has("setup") ? redBaseline() : project;
    var args =
        strings(errorCase.get("args")).stream()
            .map(
                arg ->
                    arg.replace("{dir}", dir.toString())
                        .replace("{id}", ID)
                        .replace("{empty}", work.resolve("empty").toString()))
            .toArray(String[]::new);
    var env = new HashMap<String, String>();
    errorCase
        .get("env")
        .properties()
        .forEach(entry -> env.put(entry.getKey(), entry.getValue().asString()));
    var run = mutastic(env, args);
    var item = "error:" + errorCase.get("id").asString();
    var exit = errorCase.get("exit").asInt();
    if (run.exit() != exit) {
      found.add(
          new Found(item + ":exit", "it exits %d, the contract's %d".formatted(run.exit(), exit)));
    }
    if (errorCase.has("code")) {
      errorCode(found, item, errorCase.get("code").asString(), run);
    }
  }

  private static void errorCode(List<Found> found, String item, String code, Samples.Result run) {
    var failed =
        run.out()
            .lines()
            .filter(line -> line.startsWith("{"))
            .map(JsonMapper.shared()::readTree)
            .filter(event -> event.get("reason").asString().equals("run-failed"))
            .reduce((first, second) -> second);
    var said = failed.map(event -> event.get("code").asString()).orElse("no run-failed event");
    if (!said.equals(code)) {
      found.add(new Found(item + ":code", "%s, the contract's %s".formatted(said, code)));
    }
    if (run.err().lines().anyMatch(line -> line.startsWith("usage: "))) {
      found.add(
          new Found(
              item + ":usage-line",
              "a `usage:` line follows the error, not the one command to run"));
    }
    if (run.err().lines().noneMatch(line -> line.startsWith("mutastic: "))) {
      found.add(new Found(item + ":stderr", "no line on stderr starts `mutastic: `"));
    }
  }

  /// mutastic run as Samples runs it, with only the lines of stdout that are events checked against
  /// the schema: a command with no format of its own, verify, ignores MUTASTIC_FORMAT.
  private static Samples.Result mutastic(Map<String, String> env, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            new Terminal(false, env));
    var result =
        new Samples.Result(
            exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    Schemas.checkEvents(
        result
            .out()
            .lines()
            .filter(line -> line.startsWith("{"))
            .collect(Collectors.joining("\n")));
    return result;
  }

  /// A copy of the fixture with a test that fails with no mutant in: the pricing test expects 26.
  private static Path redBaseline() throws IOException {
    var red = work.resolve("red");
    if (!Files.exists(red)) {
      Samples.copy(SAMPLE, red);
      var test = red.resolve("src/test/java/com/example/pricing/PricingTest.java");
      Files.writeString(
          test, Files.readString(test).replace("assertEquals(25,", "assertEquals(26,"));
    }
    return red;
  }

  /// A run as a person at a terminal sees it, which the summary and report checks share.
  private static Samples.Result full() {
    if (full == null) {
      full = Samples.mutastic(Map.of("MUTASTIC_FORMAT", "full"), "run", project.toString());
    }
    return full;
  }

  private static Samples.@Nullable Result full;

  @Test
  void theSummaryHasTheContractsRows() {
    var run = full();
    var found = new ArrayList<Found>();
    summary(found, rows(run.out()), CONTRACT.get("output").get("summary").get("rows"));

    assertThat(CONTRACT.unrecorded(found, List.of("summary"))).isEmpty();
  }

  /// The summary's rows: a label right-aligned in the first ten columns, two spaces, its value.
  private static List<Map.Entry<String, String>> rows(String output) {
    return output
        .lines()
        .filter(line -> line.length() > 12 && line.startsWith("  ", 10) && line.charAt(12) != ' ')
        .filter(line -> LABEL.matcher(line.substring(0, 10)).matches())
        .map(line -> Map.entry(line.substring(0, 10).strip(), line.substring(12)))
        .toList();
  }

  private static final Pattern LABEL = Pattern.compile("^ *[A-Z][a-z]+(?: [a-z]+)?$");

  private static void summary(
      List<Found> found, List<Map.Entry<String, String>> rows, JsonNode spec) {
    var specs = StreamSupport.stream(spec.values().spliterator(), false).toList();
    var labels = specs.stream().map(row -> row.get("label").asString()).toList();
    int at = 0;
    for (var row : rows) {
      int match = at;
      while (match < specs.size() && !fits(specs.get(match), row.getKey(), labels)) {
        match++;
      }
      if (match == specs.size()) {
        found.add(
            labels.contains(row.getKey())
                ? new Found("summary:order", row.getKey() + " is out of the contract's order")
                : new Found("summary:label:" + row.getKey(), "a row the contract doesn't have"));
        continue;
      }
      required(found, specs.subList(at, match));
      var shape = specs.get(match);
      if (shape.has("shape")
          && !Pattern.compile(shape.get("shape").asString()).matcher(row.getValue()).find()) {
        found.add(new Found("summary:shape:" + row.getKey(), "`" + row.getValue() + "`"));
      }
      at = match + 1;
    }
    required(found, specs.subList(at, specs.size()));
  }

  private static boolean fits(JsonNode row, String label, List<String> labels) {
    return row.get("label").asString().equals(label)
        || (row.has("pattern")
            && !labels.contains(label)
            && Pattern.compile(row.get("pattern").asString()).matcher(label).find());
  }

  private static void required(List<Found> found, List<JsonNode> skipped) {
    skipped.stream()
        .filter(row -> !row.has("optional") || !row.get("optional").asBoolean())
        .forEach(
            row ->
                found.add(
                    new Found("summary:label:" + row.get("label").asString(), "no such row")));
  }

  @Test
  void theAgentFormatHasTheContractsLines() {
    var run = Samples.mutastic("run", project.toString(), "--format=agent");
    var lines = masked(run.out()).lines().toList();
    var agent = CONTRACT.get("output").get("agent");
    var found = new ArrayList<Found>();
    var read = new Lines(lines, found);
    if (read.sequence(agent.get("head"), true)) {
      var entry = true;
      while (entry && read.more()) {
        entry = read.sequence(agent.get("entry"), false);
      }
      // The fence's close, after the entries: what quotes the project ends there.
      read.sequence(agent.get("tail"), true);
      if (read.more()) {
        found.add(
            new Found(
                "agent:extra",
                "line %d `%s` fits no shape".formatted(read.at + 1, lines.get(read.at))));
      }
    }

    assertThat(CONTRACT.unrecorded(found, List.of("agent"))).isEmpty();
  }

  /// The ids as `<id>`, and the fixture's directory, as given or relative to here, as `<dir>`.
  private static String masked(String output) {
    var relative = Path.of("").toAbsolutePath().relativize(project).toString();
    return IDS.matcher(output.replace(project.toString(), "<dir>"))
        .replaceAll("<id>")
        .lines()
        .map(
            line ->
                line.replace(" " + relative + " ", " <dir> ")
                    .replace(" " + relative + "`", " <dir>`"))
        .map(
            line ->
                line.endsWith(" " + relative)
                    ? line.substring(0, line.length() - relative.length()) + "<dir>"
                    : line)
        .collect(Collectors.joining("\n"));
  }

  private static final Pattern IDS = Pattern.compile("\\bm[0-9a-f]{8}\\b");

  /// The agent format's lines, read shape by shape.
  private static final class Lines {
    private final List<String> lines;
    private final List<Found> found;
    int at;

    Lines(List<String> lines, List<Found> found) {
      this.lines = lines;
      this.found = found;
    }

    boolean more() {
      return at < lines.size();
    }

    /// The shapes in order, each as often as its `repeat` says; false, and what failed, when a
    /// shape that must come doesn't. `optional`: a sequence that doesn't start isn't a failure.
    boolean sequence(JsonNode shapes, boolean required) {
      int start = at;
      for (var shape : shapes.values()) {
        var repeat = shape.has("repeat") ? shape.get("repeat").asString() : "1";
        var pattern = Pattern.compile(shape.get("pattern").asString());
        int count = 0;
        while (more()
            && pattern.matcher(lines.get(at)).find()
            && (count == 0 || "*+".contains(repeat))) {
          at++;
          count++;
        }
        if (count == 0 && "1+".contains(repeat)) {
          if (!required && at == start) {
            return false;
          }
          found.add(
              new Found(
                  "agent:" + shape.get("name").asString(),
                  "line %d `%s`".formatted(at + 1, more() ? lines.get(at) : "<end>")));
          return false;
        }
      }
      return true;
    }
  }

  @Test
  void theEventsHaveTheContractsKeyPaths() {
    full();
    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var events = run.out().lines().map(JsonMapper.shared()::readTree).toList();
    var found = new ArrayList<Found>();
    var byType = new LinkedHashMap<String, SequencedSet<String>>();
    events.forEach(
        event ->
            byType
                .computeIfAbsent(event.get("reason").asString(), reason -> new LinkedHashSet<>())
                .addAll(paths(event, "")));
    var shared = CONTRACT.get("events").get("keyPaths");
    for (var type : shared.properties()) {
      var mine = byType.getOrDefault(type.getKey(), new LinkedHashSet<>());
      named(found, "event:" + type.getKey(), strings(type.getValue()), List.copyOf(mine));
    }
    byType.entrySet().stream()
        .filter(type -> !shared.has(type.getKey()))
        .forEach(
            type ->
                type.getValue()
                    .forEach(
                        path ->
                            found.add(
                                new Found(
                                    "event:%s:%s".formatted(type.getKey(), path),
                                    "a key path the contract doesn't have"))));
    order(found, events);

    assertThat(CONTRACT.unrecorded(found, List.of("event", "event-order"))).isEmpty();
  }

  /// Decision 7: run-started before the first mutant-finished, each mutant-finished before the
  /// mutants phase ends, and run-finished or run-failed last.
  private static void order(List<Found> found, List<JsonNode> events) {
    var reasons =
        events.stream()
            .map(
                event ->
                    event.get("reason").asString()
                        + (event.has("phase") ? "/" + event.get("phase").asString() : ""))
            .toList();
    var firstMutant = reasons.indexOf("mutant-finished");
    if (firstMutant >= 0 && reasons.indexOf("run-started") > firstMutant) {
      found.add(new Found("event-order:run-started", "a mutant-finished comes before run-started"));
    }
    var mutantsEnd = reasons.indexOf("phase-finished/mutants");
    if (mutantsEnd >= 0 && reasons.lastIndexOf("mutant-finished") > mutantsEnd) {
      found.add(
          new Found("event-order:live", "a mutant-finished comes after the mutants phase ends"));
    }
    if (!reasons.isEmpty() && !List.of("run-finished", "run-failed").contains(reasons.getLast())) {
      found.add(new Found("event-order:last", "the last event is " + reasons.getLast()));
    }
  }

  /// Every key path in `node`, `a.b` for an object's, `a[].b` within an array's items, and a file's
  /// name as `*` under mutation.json's `files` and `testFiles`.
  static SequencedSet<String> paths(JsonNode node, String at) {
    var paths = new LinkedHashSet<String>();
    if (node.isObject()) {
      for (var field : node.properties()) {
        var key = at.equals("files") || at.equals("testFiles") ? "*" : field.getKey();
        var path = at.isEmpty() ? key : at + "." + key;
        paths.add(path);
        paths.addAll(paths(field.getValue(), path));
      }
    } else if (node.isArray()) {
      node.values().forEach(item -> paths.addAll(paths(item, at + "[]")));
    }
    return paths;
  }

  @Test
  void theReportHasTheContractsKeyPaths() throws IOException {
    var run = full();
    var reports = REPORTS.matcher(run.out());
    assertThat(reports.find()).as(run.out()).isTrue();
    var json = project.resolve(reports.group("json"));
    var report = JsonMapper.shared().readTree(json.toFile());
    var found = new ArrayList<Found>();
    named(
        found,
        "report",
        strings(CONTRACT.get("report").get("keyPaths")),
        List.copyOf(paths(report, "")));

    assertThat(Schemas.reportProblems(Files.readString(json))).isEmpty();
    assertThat(CONTRACT.unrecorded(found, List.of("report"))).isEmpty();
  }

  private static final Pattern REPORTS = Pattern.compile("Reports  (?<json>\\S+\\.json)");

  private static String[] arguments(String command, String last) {
    var words = new ArrayList<>(List.of(SPACE.split(command)));
    words.add(last);
    return words.toArray(String[]::new);
  }

  private static final Pattern SPACE = Pattern.compile(" ");

  private static void git(Path directory, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assertThat(process.waitFor()).isZero();
  }
}
