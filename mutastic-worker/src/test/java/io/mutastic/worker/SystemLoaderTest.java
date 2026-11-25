package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.worker.fixture.CounterTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The worker's system class loader, run here directly: the JVM makes it before any agent starts,
/// so the coverage agent never sees it run in a worker.
class SystemLoaderTest {
  private static final String COUNTER = "io.mutastic.worker.fixture.CounterTest";

  @TempDir Path temp;

  /// Before the worker has a production loader there's nothing behind the class path; after, the
  /// JDK's lookups reach the project's classes and resources, and only the project's own.
  @Test
  void findsTheProjectOnceTheWorkerHasLoadedIt() throws Exception {
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader());
    var resource = "io/mutastic/worker/fixture/CounterTest.class";
    SystemLoader.project(null);
    assertThatThrownBy(() -> system.loadClass(COUNTER)).isInstanceOf(ClassNotFoundException.class);
    assertThat(system.getResource(resource)).isNull();
    assertThat(Collections.list(system.getResources(resource))).isEmpty();

    var production =
        new ProductionLoader(
            List.of(),
            Map.of(),
            roots(),
            new TestSplit(Set.of(), Set.of(COUNTER)),
            ClassLoader.getPlatformClassLoader());
    SystemLoader.project(production);
    try {
      assertThat(system.loadClass(COUNTER).getClassLoader()).isSameAs(production);
      assertThat(system.getResource(resource)).isNotNull();
      assertThat(Collections.list(system.getResources(resource))).hasSize(1);
    } finally {
      SystemLoader.project(null);
    }
  }

  /// A `-javaagent` jar the JVM adds: its classes and resources are the system loader's own.
  @Test
  void anAgentsJarIsItsOwn() throws Exception {
    var jar = temp.resolve("agent.jar");
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("io/mutastic/worker/fixture/CounterTest.class"));
      out.write(Files.readAllBytes(roots().get(0).resolve(COUNTER.replace('.', '/') + ".class")));
      out.putNextEntry(new JarEntry("agent.properties"));
      out.write("premain=yes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader());
    system.appendToClassPathForInstrumentation(jar.toString());

    assertThat(system.loadClass(COUNTER).getClassLoader()).isSameAs(system);
    assertThat(system.getResource("agent.properties")).isNotNull();
    assertThat(Collections.list(system.getResources("agent.properties"))).hasSize(1);
    assertThat(system.getResource("nowhere.properties")).isNull();
  }

  /// A class the project names for a JDK service is the production loader's, by its own name, for
  /// every run: the instance the JDK makes and the class a test reads are the same.
  @Test
  void aServiceClassIsSharedByEveryRun() throws Exception {
    var production =
        new ProductionLoader(
            List.of(),
            Map.of(),
            roots(),
            new TestSplit(Set.of(), Set.of(COUNTER)),
            ClassLoader.getPlatformClassLoader());
    var first = new TestLoader(roots(), production, 1);
    var second = new TestLoader(roots(), production, 2);

    assertThat(first.copyOf(COUNTER)).isEqualTo(COUNTER);
    assertThat(first.loadClass(COUNTER)).isSameAs(second.loadClass(COUNTER));
    assertThat(first.loadClass(COUNTER).getClassLoader()).isSameAs(production);
  }

  /// A library's class is the system loader's own, with the jar it came from, so a library that
  /// looks a class up by its own loader finds the project's, as it does on a build's class path.
  @Test
  void aLibraryIsItsOwnAndFindsTheProject() throws Exception {
    var jar = jarOf(temp.resolve("library.jar"), PROVIDER, PROVIDER + "$Rot13");
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader(), jar.toString());
    var production =
        new ProductionLoader(
            List.of(), Map.of(), roots(), new TestSplit(Set.of(), Set.of(COUNTER)), system);
    SystemLoader.project(production);
    try {
      var library = system.loadClass(PROVIDER);

      assertThat(library.getClassLoader()).isSameAs(system);
      assertThat(library.getProtectionDomain().getCodeSource().getLocation())
          .isEqualTo(jar.toUri().toURL());
      assertThat(Class.forName(COUNTER, false, library.getClassLoader()).getClassLoader())
          .isSameAs(production);
    } finally {
      SystemLoader.project(null);
    }
  }

  /// A name neither the libraries nor the project have isn't found, from either side: the project
  /// asks back here for what it hasn't got, and this doesn't ask it again.
  @Test
  void aNameNoOneHasIsntFoundFromEitherSide() throws Exception {
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader(), "");
    var production =
        new ProductionLoader(
            List.of(), Map.of(), roots(), new TestSplit(Set.of(), Set.of()), system);
    SystemLoader.project(production);
    try {
      assertThatThrownBy(() -> system.loadClass("com.example.Nowhere"))
          .isInstanceOf(ClassNotFoundException.class);
      assertThatThrownBy(() -> production.loadClass("com.example.Nowhere"))
          .isInstanceOf(ClassNotFoundException.class);
    } finally {
      SystemLoader.project(null);
    }
  }

  /// A signed provider jar, as a JCE provider is shipped: its classes carry its signer, as the
  /// JDK's class path gives them, and the provider it registers hands out its cipher.
  @Test
  void aSignedProvidersClassesCarryItsSignerAndItsCipherWorks() throws Exception {
    var jar = signed(jarOf(temp.resolve("provider.jar"), PROVIDER, PROVIDER + "$Rot13"));
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader(), jar.toString());

    var providerClass = system.loadClass(PROVIDER);
    assertThat(providerClass.getProtectionDomain().getCodeSource().getCodeSigners())
        .singleElement()
        .satisfies(
            signer ->
                assertThat(signer.getSignerCertPath().getCertificates().get(0).toString())
                    .contains("CN=mutastic test signer"));

    var provider = (java.security.Provider) providerClass.getConstructor().newInstance();
    var cipher = javax.crypto.Cipher.getInstance("Rot13", provider);
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(new byte[1], "Rot13"));
    assertThat(cipher.doFinal(new byte[] {1, 2})).containsExactly(14, 15);
  }

  /// A signed jar whose class no longer matches its signature is refused outright, as the JDK's
  /// class path refuses it: never defined as if it were unsigned.
  @Test
  void aSignedClassThatDoesntMatchItsSignatureIsRefused() throws Exception {
    var jar = signed(jarOf(temp.resolve("provider.jar"), PROVIDER, PROVIDER + "$Rot13"));
    var tampered = temp.resolve("tampered.jar");
    try (var in = new java.util.jar.JarFile(jar.toFile());
        var out = new JarOutputStream(Files.newOutputStream(tampered))) {
      for (var entry : Collections.list(in.entries())) {
        var bytes = in.getInputStream(entry).readAllBytes();
        if (entry.getName().equals(PROVIDER.replace('.', '/') + ".class")) {
          bytes[bytes.length - 1] ^= 1;
        }
        out.putNextEntry(new JarEntry(entry.getName()));
        out.write(bytes);
      }
    }
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader(), tampered.toString());

    assertThatThrownBy(() -> system.loadClass(PROVIDER))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("digest");
  }

  /// The providers a project names, comments and blank lines left out; only for the JDK's services.
  @Test
  void readsTheProvidersTheProjectNames() throws IOException {
    var services = Files.createDirectories(temp.resolve("META-INF/services"));
    Files.writeString(
        services.resolve("java.lang.System$LoggerFinder"),
        "# the test's finder\n\ncom.example.RecordingFinder  # records\n");
    Files.writeString(services.resolve("org.example.Plugin"), "com.example.NotTheJdks\n");

    assertThat(TestSplit.providersIn(List.of(temp, temp.resolve("missing"))))
        .containsExactly("com.example.RecordingFinder");
  }

  private static final String PROVIDER = "io.mutastic.worker.fixture.Rot13Provider";

  /// A jar of these classes, from the test classes.
  private static Path jarOf(Path jar, String... classes) throws Exception {
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (var name : classes) {
        var entry = name.replace('.', '/') + ".class";
        out.putNextEntry(new JarEntry(entry));
        out.write(Files.readAllBytes(roots().get(0).resolve(entry)));
      }
    }
    return jar;
  }

  /// `jar`, signed in place with a key made for it, by the JDK's own tools.
  private Path signed(Path jar) throws Exception {
    var bin = Path.of(System.getProperty("java.home"), "bin");
    var keystore = temp.resolve("signer.p12").toString();
    run(
        bin.resolve("keytool").toString(),
        "-genkeypair",
        "-keystore",
        keystore,
        "-storepass",
        "changeit",
        "-alias",
        "signer",
        "-keyalg",
        "RSA",
        "-dname",
        "CN=mutastic test signer",
        "-validity",
        "2");
    run(
        bin.resolve("jarsigner").toString(),
        "-keystore",
        keystore,
        "-storepass",
        "changeit",
        jar.toString(),
        "signer");
    return jar;
  }

  private static void run(String... command) throws Exception {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
  }

  private static List<Path> roots() throws Exception {
    return List.of(
        Path.of(CounterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
  }
}
