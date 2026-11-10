package io.mutastic.report;

import io.mutastic.api.FlakyTest;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import io.mutastic.api.Suggestion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// One share of a run split with `--shard`, read back from the events it kept ([JsonEvents]): what
/// `run --from-shards` puts back together with the other shares' into the whole run.
///
/// A mutant itself is found again from the code, as the whole run would find it; its result, and
/// what the share's run found of its tests, come from here.
public final class ShareEvents {
  private final Map<String, Object> started;
  private final Optional<Map<String, Object>> finished;
  private final Map<String, Map<String, Object>> results;

  private ShareEvents(
      Map<String, Object> started,
      Optional<Map<String, Object>> finished,
      Map<String, Map<String, Object>> results) {
    this.started = started;
    this.finished = finished;
    this.results = results;
  }

  /// The share whose events are in `file`. A line that isn't an event is an
  /// [IllegalArgumentException] naming it.
  public static ShareEvents read(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, Object> started = Map.of();
    Optional<Map<String, Object>> finished = Optional.empty();
    var results = new LinkedHashMap<String, Map<String, Object>>();
    for (int at = 0; at < lines.size(); at++) {
      var event = event(lines.get(at), at + 1);
      switch (text(event, "reason").orElse("")) {
        case "run-started" -> started = event;
        case "run-finished" -> finished = Optional.of(event);
        // A revised result replaces the first, as it does for every reader of the events.
        case "mutant-finished", "mutant-revised" -> results.put(id(event, at + 1), event);
        default -> {}
      }
    }
    return new ShareEvents(started, finished, results);
  }

  private static Map<String, Object> event(String line, int number) {
    try {
      if (JsonReader.read(line) instanceof Map<?, ?> map) {
        return fields(map);
      }
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "line %d isn't JSON: %s".formatted(number, e.getMessage()));
    }
    throw new IllegalArgumentException("line %d isn't an event".formatted(number));
  }

  private static String id(Map<String, Object> event, int number) {
    return text(event, "id")
        .orElseThrow(() -> new IllegalArgumentException("line %d has no id".formatted(number)));
  }

  /// Which share it is, and what it ran on, if its `run-started` says: every share's does.
  public Optional<JsonEvents.Share> share() {
    return object(started, "share")
        .map(
            share ->
                new JsonEvents.Share(
                    (int) number(share, "number"),
                    (int) number(share, "of"),
                    text(share, "version").orElse(""),
                    text(share, "code").orElse("")));
  }

  /// The project the share ran, by name.
  public String project() {
    return text(started, "project").orElse("");
  }

  /// Whether the share's run finished: its last event is there.
  public boolean finished() {
    return finished.isPresent();
  }

  /// What the share ran that the whole run can't be put together from, if anything: a run of a
  /// change's lines, an audit of the arid filters, or ghosts, whose results the events only
  /// summarise.
  public Optional<String> partial() {
    var end = finished.orElse(Map.of());
    if (end.containsKey("scope")) {
      return Optional.of("ran only a change's lines (--since)");
    }
    if (end.containsKey("aridAudit")) {
      return Optional.of("audited the arid filters (MUTASTIC_ARID=audit)");
    }
    return end.containsKey("ghosts")
        ? Optional.of("ran ghosts (--experimental=ghosts)")
        : Optional.empty();
  }

  /// The ids of the mutants it ran, skipped ones too.
  public Set<String> ids() {
    return results.keySet();
  }

  /// The share's result for `mutant`, one of its [#ids].
  public MutantResult result(Mutant mutant) {
    var fields = java.util.Objects.requireNonNull(results.get(mutant.id()), mutant.id());
    return new MutantResult(
        mutant,
        status(fields),
        strings(fields, "coveredBy"),
        text(fields, "caughtBy"),
        text(fields, "caughtWith"),
        text(fields, "failureOrigin"),
        (int) number(fields, "testsRun"),
        text(fields, "unconfirmedCatchBy"),
        inputs(fields),
        object(fields, "suggestion").map(ShareEvents::suggestion),
        Duration.ofMillis(number(fields, "durationMs")),
        text(fields, "falseCatchBy"),
        strings(fields, "caughtByAll"));
  }

  /// The status its result had, by the name the events give it.
  public Status status(String id) {
    return status(java.util.Objects.requireNonNull(results.get(id), id));
  }

  private static Status status(Map<String, Object> fields) {
    var name = text(fields, "status").orElse("");
    return java.util.Arrays.stream(Status.values())
        .filter(status -> status.schemaName().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no status " + name));
  }

  /// The JDK feature release its tests ran on.
  public int jdk() {
    return (int) number(end(), "jdk");
  }

  /// How many tests its coverage pass ran: all the module's, as in every share.
  public int tests() {
    return (int) number(end(), "tests");
  }

  public int workers() {
    return (int) number(end(), "workers");
  }

  /// How many workers started: one for each worker, and one after each kill.
  public int workersStarted() {
    return (int) number(end(), "workersStarted");
  }

  public Duration coverage() {
    return Duration.ofMillis(number(end(), "coverageMs"));
  }

  public Duration took() {
    return Duration.ofMillis(number(end(), "durationMs"));
  }

  /// The tests it found flaky and left out.
  public List<String> flaky() {
    return strings(end(), "flaky");
  }

  /// What it found of why each flaky test is flaky.
  public List<FlakyTest> flakyEvidence() {
    return maps(end(), "flakyEvidence").stream()
        .map(
            fields ->
                new FlakyTest(
                    text(fields, "test").orElse(""),
                    strings(fields, "failure"),
                    strings(fields, "reaches"),
                    strings(fields, "reachesInTest"),
                    Boolean.TRUE.equals(fields.get("failsAlone"))))
        .toList();
  }

  private Map<String, Object> end() {
    return finished.orElseThrow(() -> new IllegalStateException("the share didn't finish"));
  }

  private static Suggestion suggestion(Map<String, Object> fields) {
    return new Suggestion(
        text(fields, "call").orElse(""),
        strings(fields, "parameters"),
        numbers(fields.get("arguments")),
        maps(fields, "receiver").stream()
            .map(
                instance ->
                    new Suggestion.Instance(
                        text(instance, "type").orElse(""),
                        numbers(instance.get("arguments")),
                        strings(instance, "parameters")))
            .toList(),
        text(fields, "today").orElse(""),
        text(fields, "withMutant").orElse(""),
        object(fields, "named")
            .map(
                named ->
                    new Suggestion.Named(
                        text(named, "name").orElse(""),
                        text(named, "note").orElse(""),
                        text(named, "local"),
                        text(named, "meaning").orElse(""))),
        text(fields, "builtBy"));
  }

  private static Map<String, List<Long>> inputs(Map<String, Object> fields) {
    var inputs = new LinkedHashMap<String, List<Long>>();
    object(fields, "inputs")
        .ifPresent(found -> found.forEach((name, values) -> inputs.put(name, numbers(values))));
    return inputs;
  }

  private static Optional<String> text(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof String text ? Optional.of(text) : Optional.empty();
  }

  private static long number(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof Number number ? number.longValue() : 0;
  }

  private static List<String> strings(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
  }

  private static List<Long> numbers(@org.jspecify.annotations.Nullable Object value) {
    return value instanceof List<?> list
        ? list.stream().map(item -> ((Number) item).longValue()).toList()
        : List.of();
  }

  private static Optional<Map<String, Object>> object(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof Map<?, ?> map ? Optional.of(fields(map)) : Optional.empty();
  }

  private static List<Map<String, Object>> maps(Map<String, Object> fields, String key) {
    return fields.get(key) instanceof List<?> list
        ? list.stream().filter(Map.class::isInstance).map(item -> fields((Map<?, ?>) item)).toList()
        : List.of();
  }

  /// A JSON object's fields, by name.
  private static Map<String, Object> fields(Map<?, ?> map) {
    var fields = new LinkedHashMap<String, Object>();
    map.forEach((key, value) -> fields.put(String.valueOf(key), value));
    return fields;
  }
}
