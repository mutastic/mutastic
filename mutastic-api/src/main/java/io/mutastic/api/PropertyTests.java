package io.mutastic.api;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/// The property-testing frameworks a module's tests use, and the seed a run draws their inputs
/// from.
///
/// A property draws its inputs at random. Drawn afresh in each JVM, a mutant only some inputs catch
/// is caught in one run and missed in the next, and a property that fails for a rare input looks
/// like a flaky test. So where a framework takes its seed from outside the test, every test JVM of
/// a run, the coverage pass's and each mutant's, gets the same one, and every run the same one
/// unless the person sets it: caught then means caught, every time. A framework that can't be
/// seeded from outside is named, never guessed at.
///
/// - QuickTheories reads the system property `QT_SEED` for each theory, unless the test calls
///   `withFixedSeed`.
/// - Kotest's property tests read `kotest.proptest.default.seed`, unless a test's own
///   `PropTestConfig` names a seed.
/// - junit-quickcheck seeds a parameter only from its `@When(seed=…)`, in the source.
/// - proptastic reads `proptastic.seed` as each property starts, else the environment's
///   `PROPTASTIC_SEED`, unless the chain says `.seed(n)`: `0x` and hex digits only, as its failures
///   print seeds. It's found by its entry point's class on the test classpath, whatever its jar is
///   called, and its random stream is keyed on the test method's signature, never its class, so a
///   renamed copy of a test class draws what the class does.
/// - jqwik is only detected, never seeded or read from: its tests run as ordinary tests.
///
/// @param frameworks the frameworks the tests use, by name, in [Framework]'s order
/// @param seed the seed every test JVM is given, decimal: the person's, else `0`; empty when no
///   framework the tests use takes one. proptastic is given it as `0x` and hex digits
/// @param seeded the frameworks that draw from `seed`
/// @param unseeded the frameworks whose seed can't be set from outside the test
@Internal
public record PropertyTests(
    List<String> frameworks, Optional<String> seed, List<String> seeded, List<String> unseeded) {

  /// The seed a run gives where the person hasn't set one: any fixed value, kept so a project's
  /// results stay as they were.
  public static final String SEED = "0";

  /// A Kotest setting that fails every property given a seed: a seed set for it would fail them
  /// all, so Kotest is left unseeded where it's on.
  static final String FAIL_IF_SEEDED = "kotest.proptest.seed.fail-if-set";

  /// Kotest's switch for saving a failing property's seed, to replay it in the next run: off, so a
  /// mutant's failure doesn't write to the person's home directory.
  static final String WRITE_FAILED = "kotest.proptest.seed.write-failed";

  /// What every test JVM gives proptastic besides its seed, so a run neither reads nor writes the
  /// person's files under the build directory: no saved failures replayed first or saved (a
  /// mutant's failure would decide the runs after it), no events (a mutant's would overwrite the
  /// person's), and nothing added to the corpus.
  static final List<String> PROPTASTIC_OFF =
      List.of(
          "-Dproptastic.replay=false",
          "-Dproptastic.events=false",
          "-Dproptastic.corpus.record=false");

  /// proptastic's environment variable for its seed, read where its system property isn't set.
  static final String PROPTASTIC_SEED = "PROPTASTIC_SEED";

  /// The class proptastic is found by: its entry point, `forAll`'s.
  static final String PROPTASTIC_ENTRY = "io/proptastic/Proptastic.class";

  public PropertyTests {
    frameworks = List.copyOf(frameworks);
    seeded = List.copyOf(seeded);
    unseeded = List.copyOf(unseeded);
  }

  /// A property-testing framework a test classpath can hold, in the order they're named: by its
  /// name, as `run-started` gives it, and the system property it takes its seed from, if it takes
  /// one from outside.
  enum Framework {
    JUNIT_QUICKCHECK("junit-quickcheck", null),
    QUICKTHEORIES("quicktheories", "QT_SEED"),
    KOTEST("kotest", "kotest.proptest.default.seed"),
    PROPTASTIC("proptastic", "proptastic.seed"),
    JQWIK("jqwik", null);

    private final String said;
    private final @Nullable String seedProperty;

    Framework(String said, @Nullable String seedProperty) {
      this.said = said;
      this.seedProperty = seedProperty;
    }

    /// Whether `jar`, a test classpath entry, is one of this framework's: by its file name, for
    /// jqwik by its group's directory in a Gradle or Maven cache too, and for proptastic by its
    /// entry point's class, in a jar or a directory of classes.
    boolean holds(Path jar) {
      var name = jar.getFileName() == null ? "" : jar.getFileName().toString();
      return switch (this) {
        case JUNIT_QUICKCHECK -> name.startsWith("junit-quickcheck-");
        case QUICKTHEORIES -> name.startsWith("quicktheories-");
        case KOTEST -> name.startsWith("kotest-property");
        case PROPTASTIC -> holdsClass(jar, PROPTASTIC_ENTRY);
        case JQWIK -> name.startsWith("jqwik-") || jqwikGroup(jar);
      };
    }

    /// Whether a classpath entry, a jar or a directory, holds the class file `entry`: looked at
    /// once a run for each entry.
    private static boolean holdsClass(Path jar, String entry) {
      return LOOKED.computeIfAbsent(jar.toAbsolutePath().normalize(), path -> lookIn(path, entry));
    }

    private static boolean lookIn(Path path, String entry) {
      if (Files.isDirectory(path)) {
        return Files.isRegularFile(path.resolve(entry));
      }
      if (!Files.isRegularFile(path)) {
        return false;
      }
      try (var zip = new ZipFile(path.toFile())) {
        return zip.getEntry(entry) != null;
      } catch (IOException e) {
        return false;
      }
    }

    private static boolean jqwikGroup(Path jar) {
      var path = jar.toString().replace('\\', '/');
      return path.contains("/net.jqwik/") || path.contains("/net/jqwik/");
    }
  }

  /// Whether each classpath entry looked in holds proptastic's entry point.
  private static final Map<Path, Boolean> LOOKED = new ConcurrentHashMap<>();

  /// The property tests of `project`, when its tests use a framework: none otherwise.
  public static Optional<PropertyTests> of(Project project) {
    return of(project.dependencies(), project.jvmArgs(), System.getenv());
  }

  static Optional<PropertyTests> of(List<Path> dependencies, List<String> jvmArgs) {
    return of(dependencies, jvmArgs, Map.of());
  }

  /// The property tests of a test classpath, `dependencies`, run with `jvmArgs` in `environment`:
  /// the person's seed is the first of the frameworks' seed settings they set, as a whole number,
  /// or for proptastic as `0x` and hex digits, in its system property or else its variable.
  static Optional<PropertyTests> of(
      List<Path> dependencies, List<String> jvmArgs, Map<String, String> environment) {
    var used =
        Arrays.stream(Framework.values())
            .filter(framework -> dependencies.stream().anyMatch(framework::holds))
            .toList();
    if (used.isEmpty()) {
      return Optional.empty();
    }
    var seeded = used.stream().filter(framework -> takesASeed(framework, jvmArgs)).toList();
    var seed =
        seeded.isEmpty()
            ? Optional.<String>empty()
            : Optional.of(seed(seeded, jvmArgs, environment));
    return Optional.of(
        new PropertyTests(
            names(used),
            seed,
            names(seeded),
            names(used.stream().filter(framework -> !seeded.contains(framework)).toList())));
  }

  /// Whether mutastic can give `framework` its seed: it takes one from outside, and for Kotest the
  /// person hasn't told it to fail a property given one.
  private static boolean takesASeed(Framework framework, List<String> jvmArgs) {
    if (framework.seedProperty == null) {
      return false;
    }
    return framework != Framework.KOTEST
        || !property(jvmArgs, FAIL_IF_SEEDED).orElse("").equals("true");
  }

  /// The person's seed, decimal, the first of `seeded`'s they set as that framework reads one; else
  /// [#SEED].
  private static String seed(
      List<Framework> seeded, List<String> jvmArgs, Map<String, String> environment) {
    return seeded.stream()
        .flatMap(framework -> personsSeed(framework, jvmArgs, environment).stream())
        .findFirst()
        .orElse(SEED);
  }

  /// The seed the person set for `framework`, decimal, if it's one the framework reads: a whole
  /// number, or for proptastic `0x` and hex digits, in its system property, else its variable.
  private static Optional<String> personsSeed(
      Framework framework, List<String> jvmArgs, Map<String, String> environment) {
    var set = property(jvmArgs, requireSeedProperty(framework));
    if (framework != Framework.PROPTASTIC) {
      return set.filter(WHOLE.asMatchPredicate());
    }
    return set.or(() -> Optional.ofNullable(environment.get(PROPTASTIC_SEED)))
        .map(String::strip)
        .filter(HEX.asMatchPredicate())
        .map(hex -> new BigInteger(hex.substring(2), 16).toString());
  }

  private static final Pattern WHOLE = Pattern.compile("[0-9]+");

  /// A seed as proptastic reads it.
  private static final Pattern HEX = Pattern.compile("0[xX][0-9a-fA-F]{1,16}");

  /// The low 64 bits, as proptastic's seed has.
  private static final BigInteger LONG_BITS = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

  private static String requireSeedProperty(Framework framework) {
    return java.util.Objects.requireNonNull(framework.seedProperty, framework.said);
  }

  /// The value `jvmArgs` give the system property `key`, the last one, as the JVM takes it.
  private static Optional<String> property(List<String> jvmArgs, String key) {
    var flag = "-D" + key + "=";
    return jvmArgs.stream()
        .filter(arg -> arg.startsWith(flag))
        .map(arg -> arg.substring(flag.length()))
        .reduce((earlier, later) -> later);
  }

  private static List<String> names(List<Framework> frameworks) {
    return frameworks.stream().map(framework -> framework.said).toList();
  }

  /// What every test JVM of the run is given, after the project's own arguments so it's the one
  /// seed: each seeded framework's seed property, Kotest's saved failures off, and proptastic's
  /// saved failures, events and corpus records.
  public List<String> jvmArgs() {
    var args = new ArrayList<String>();
    for (var framework : Framework.values()) {
      if (seeded.contains(framework.said) && seed.isPresent()) {
        args.add("-D" + requireSeedProperty(framework) + "=" + said(framework, seed.get()));
      }
    }
    if (frameworks.contains(Framework.KOTEST.said)) {
      args.add("-D" + WRITE_FAILED + "=false");
    }
    if (frameworks.contains(Framework.PROPTASTIC.said)) {
      args.addAll(PROPTASTIC_OFF);
    }
    return List.copyOf(args);
  }

  /// The seed as `framework` reads it: proptastic only as `0x` and hex digits, as its failures
  /// print seeds (it refuses a bare number), of the low 64 bits; the others decimal.
  private static String said(Framework framework, String seed) {
    return framework == Framework.PROPTASTIC
        ? "0x" + new BigInteger(seed).and(LONG_BITS).toString(16)
        : seed;
  }

  /// The note a run ends with when some of the tests' frameworks can't be seeded, on stderr: their
  /// results can change from run to run, and the run says so rather than hide it.
  public Optional<String> note() {
    if (unseeded.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        ("note: property tests in %s run with their own seeds, which mutastic can't set: a mutant"
                + " only some of their inputs catch can be caught in one run and missed in the next")
            .formatted(listed(unseeded)));
  }

  /// `a`, `a and b`, `a, b and c`.
  static String listed(List<String> names) {
    if (names.size() == 1) {
      return names.getFirst();
    }
    return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }
}
