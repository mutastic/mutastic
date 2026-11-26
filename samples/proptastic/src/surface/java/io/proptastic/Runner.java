package io.proptastic;

import io.proptastic.core.PropertyFailure;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Runs a property: its settings read now, its tries drawn from a stream keyed on the test's
/// signature, its first failure shrunk unless `proptastic.shrink=false`, then reported.
final class Runner {
  private Runner() {}

  private static final Pattern HEX = Pattern.compile("0[xX][0-9a-fA-F]{1,16}");

  private static final int SHRINK_BUDGET = 1_000;

  /// The test a property runs in: the nearest method marked `@Test` on the stack.
  private record Caller(String type, String name, String signature) {
    String id() {
      return type + "#" + signature;
    }
  }

  /// A failing input and what it threw.
  private record Failed<A>(A value, Throwable thrown) {}

  static <A> void check(Domain<A> domain, Property1.Check<A> check) {
    var caller = caller();
    describe(caller);
    long seed = seed();
    var random = new SplittableRandom(seed ^ key(caller.signature()));
    int tries = Integer.getInteger("proptastic.tries", 100);
    for (int tried = 1; tried <= tries; tried++) {
      var value = domain.draw(random);
      var thrown = thrown(check, value);
      if (thrown != null) {
        boolean shrink = !"false".equals(System.getProperty("proptastic.shrink"));
        var found = new Failed<>(value, thrown);
        var smallest = shrink ? shrunk(domain, check, found) : found;
        fail(caller, seed, tried, domain, smallest, shrink && smallest != found);
      }
    }
    event(caller, "{\"reason\":\"property-passed\",\"seed\":\"" + hex(seed) + "\"}");
  }

  private static <A> Throwable thrown(Property1.Check<A> check, A value) {
    try {
      check.check(value);
      return null;
    } catch (Throwable thrown) {
      return thrown;
    }
  }

  /// The smallest failing value the candidates lead to, within the budget.
  private static <A> Failed<A> shrunk(Domain<A> domain, Property1.Check<A> check, Failed<A> found) {
    var smallest = found;
    int runs = 0;
    boolean smaller = true;
    while (smaller && runs < SHRINK_BUDGET) {
      smaller = false;
      for (var candidate : domain.smaller(smallest.value())) {
        runs++;
        var thrown = thrown(check, candidate);
        if (thrown != null) {
          smallest = new Failed<>(candidate, thrown);
          smaller = true;
          break;
        }
      }
    }
    return smallest;
  }

  private static <A> void fail(
      Caller caller, long seed, int tried, Domain<A> domain, Failed<A> failed, boolean shrunk) {
    var input = "a = " + domain.shown(failed.value());
    var header =
        "%s failed after %d %s (seed %s)"
            .formatted(caller.name(), tried, tried == 1 ? "try" : "tries", hex(seed));
    var facts =
        List.of(
            List.of(shrunk ? "shrunk counterexample" : "counterexample", input),
            List.of("pin it", ".example(" + domain.source(failed.value()) + ")"));
    var report = Report.block(header, facts);
    if (!"false".equals(System.getProperty("proptastic.replay"))) {
      write(dir().resolve("failures").resolve(file(caller.id()) + ".txt"), input);
    }
    event(caller, "{\"reason\":\"property-failed\",\"seed\":\"" + hex(seed) + "\"}");
    var said = failed.thrown().getMessage();
    var message = (said == null ? failed.thrown().toString() : said) + "\n\n" + report;
    throw new PropertyFailure(message, failed.thrown());
  }

  /// The seed: `proptastic.seed`, else `PROPTASTIC_SEED`, as `0x` and hex digits; else a fresh one.
  private static long seed() {
    var said = System.getProperty("proptastic.seed");
    var where = "the system property proptastic.seed";
    if (said == null || said.isBlank()) {
      said = System.getenv("PROPTASTIC_SEED");
      where = "PROPTASTIC_SEED";
    }
    if (said == null || said.isBlank()) {
      return new SecureRandom().nextLong();
    }
    if (!HEX.matcher(said.strip()).matches()) {
      throw new IllegalArgumentException(
          "proptastic: %s is %s, which isn't a seed. Seeds are 0x and hex digits, as failures print them."
              .formatted(where, said.strip()));
    }
    return Long.parseUnsignedLong(said.strip().substring(2), 16);
  }

  static String hex(long seed) {
    return "0x" + Long.toHexString(seed);
  }

  /// The random stream's key: the test's signature alone, FNV-1a, so a renamed copy of its class
  /// draws the same inputs.
  private static long key(String signature) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : signature.getBytes(StandardCharsets.UTF_8)) {
      hash = (hash ^ (b & 0xff)) * 0x100000001b3L;
    }
    return hash;
  }

  private static Caller caller() {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(
            frames ->
                frames
                    .filter(frame -> !frame.getClassName().startsWith("io.proptastic."))
                    .filter(Runner::isTest)
                    .findFirst()
                    .map(
                        frame ->
                            new Caller(
                                frame.getClassName(),
                                frame.getMethodName(),
                                frame.getMethodName()
                                    + Arrays.stream(frame.getMethodType().parameterArray())
                                        .map(Class::getName)
                                        .collect(Collectors.joining(",", "(", ")"))))
                    .orElse(new Caller("property", "property", "property()")));
  }

  private static boolean isTest(StackWalker.StackFrame frame) {
    try {
      Method method =
          frame
              .getDeclaringClass()
              .getDeclaredMethod(frame.getMethodName(), frame.getMethodType().parameterArray());
      return Arrays.stream(method.getAnnotations())
          .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("Test"));
    } catch (NoSuchMethodException | SecurityException e) {
      return false;
    }
  }

  /// One event per property, its last, unless `proptastic.events=false`.
  private static void event(Caller caller, String event) {
    if (!"false".equals(System.getProperty("proptastic.events"))) {
      write(dir().resolve("events").resolve(file(caller.id()) + ".json"), event);
    }
  }

  /// The property's corpus folder and its description, as proptastic writes them on each run,
  /// unless `proptastic.corpus=off`. The fixture keeps no entries in it.
  private static void describe(Caller caller) {
    var corpus = System.getProperty("proptastic.corpus", ".proptastic/corpus");
    if (!corpus.equals("off")) {
      write(Path.of(corpus, file(caller.id()), "property.txt"), "key=" + caller.id());
    }
  }

  private static Path dir() {
    return Path.of(System.getProperty("proptastic.dir", "build/proptastic"));
  }

  private static String file(String id) {
    return id.replaceAll("[^A-Za-z0-9._#-]", "_");
  }

  private static void write(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text + "\n");
    } catch (IOException e) {
      // a file that can't be written never fails the test
    }
  }
}
