package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.util.SerializationUtils;

class NameTest {
  @Test
  void aNameWithLettersIsntBlank() {
    assertFalse(new Name("Zaphod").isBlank());
    assertTrue(new Name(" ").isBlank());
  }

  /// As spring-petclinic's VetTests#serialization: spring-core resolves demo.Name by its own
  /// loader.
  @Test
  @SuppressWarnings("deprecation")
  void survivesSerialization() {
    var copy =
        (Name) SerializationUtils.deserialize(SerializationUtils.serialize(new Name("Zaphod")));
    assertEquals("Zaphod", copy.first());
  }

  /// What reads the class path to compile or scan with it finds the libraries on it, as in a build.
  @Test
  void theClassPathListsTheLibraries() {
    var entries = Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
    assertTrue(
        entries.stream().anyMatch(entry -> entry.contains("spring-core")), entries::toString);
  }

  /// A library knows the jar it came from.
  @Test
  void aLibraryKnowsItsJar() {
    var location = SerializationUtils.class.getProtectionDomain().getCodeSource().getLocation();
    assertTrue(location.toString().contains("spring-core"), location::toString);
  }
}
