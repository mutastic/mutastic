package io.mutastic.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/// Which test classes the production loader defines, not each run's test loader.
///
/// @param renamed top-level test classes in a package production code also uses, defined as a
///   renamed copy each run (see [Renamer])
/// @param shared classes defined once, by their own names, so the class something outside the tests
///   finds and the class a test reads are one and the same: those the project names in
///   `META-INF/services` for a service the JDK looks up ([SystemLoader]), and top-level test
///   classes in a package production code also uses that a framework finds by scanning
///   ([Scannable]), with the classes nested in them. Their statics last across runs.
record TestSplit(Set<String> renamed, Set<String> shared) {
  /// The services the JDK finds with the system class loader, whoever asks.
  static final List<String> JDK_SERVICES =
      List.of(
          "java.lang.System$LoggerFinder",
          "java.nio.file.spi.FileSystemProvider",
          "java.nio.charset.spi.CharsetProvider",
          "java.nio.channels.spi.SelectorProvider",
          "java.net.spi.InetAddressResolverProvider",
          "java.net.spi.URLStreamHandlerProvider");

  TestSplit {
    renamed = Set.copyOf(renamed);
    shared = Set.copyOf(shared);
  }

  /// From the top-level test classes in a package production code also uses (`split`): each is
  /// renamed per run but for the JDK's service `providers` and those a framework finds by scanning
  /// (`scanned`), which are shared.
  static TestSplit of(Set<String> split, Set<String> providers, Predicate<String> scanned) {
    var shared = new HashSet<>(providers);
    split.stream().filter(scanned).forEach(shared::add);
    var renamed = new HashSet<>(split);
    renamed.removeAll(shared);
    return new TestSplit(renamed, shared);
  }

  /// The system property the engine names, separated by commas, the test classes a renamed copy
  /// would break in, which run by their own names instead.
  static final String SHARED = "mutastic.sharedTests";

  /// The test classes the engine found a copy would break ([#SHARED]): none if it names none.
  static Set<String> sharedByTheEngine() {
    var named = System.getProperty(SHARED, "");
    return named.isEmpty() ? Set.of() : Set.of(named.split(",", -1));
  }

  /// The classes named for the JDK's services in the roots' `META-INF/services`.
  static Set<String> providersIn(List<Path> roots) throws IOException {
    var providers = new HashSet<String>();
    for (Path root : roots) {
      for (String service : JDK_SERVICES) {
        var file = root.resolve("META-INF/services/" + service);
        if (Files.isRegularFile(file)) {
          Files.readAllLines(file).stream()
              .map(line -> line.replaceFirst("#.*", "").strip())
              .filter(line -> !line.isEmpty())
              .forEach(providers::add);
        }
      }
    }
    return providers;
  }
}
