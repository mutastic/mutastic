package io.mutastic.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Which test classes the production loader defines, not each run's test loader.
///
/// @param renamed top-level test classes in a package production code also uses, defined as a
///   renamed copy each run (see [Renamer])
/// @param shared classes the project names in `META-INF/services` for a service the JDK looks up
///   ([SystemLoader]): defined once, by the name the JDK asks for, so the instance the JDK makes
///   and the class a test reads are one and the same. Their statics last across runs.
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
