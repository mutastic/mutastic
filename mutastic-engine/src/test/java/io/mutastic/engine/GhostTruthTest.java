package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Ghost;
import io.mutastic.api.Mutant;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// What a ghost says it is, checked against Java itself: "firePropertyChange("p", old, new) added
/// after the write" is that call written into the source after the write, and that source,
/// compiled, must tell a listener what the ghost does. And the counts a ghost's card gives are the
/// fixture's.
class GhostTruthTest {
  private static final Path SOURCE = Path.of("src/test/resources/truth/ghosts/Ledger.java");
  private static final String OUTER = "ghosts.Ledger";

  /// How each class's peers fire, in the source.
  private static final Map<String, String> SINKS =
      Map.of(
          "Account",
          "firePropertyChange",
          "Customer",
          "firePropertyChange",
          "Branch",
          "support.firePropertyChange");

  private static Map<String, byte[]> classes;
  private static Map<String, ClassModel> models;
  private static List<Ghosts.Found> found;
  private static List<String> lines;

  @BeforeAll
  static void findTheGhosts() throws IOException {
    lines = Files.readAllLines(SOURCE);
    classes = Javac.compile(List.of(new Javac.Source(OUTER, String.join("\n", lines))), List.of());
    var context = ClassFile.of();
    models = new HashMap<>();
    classes.forEach(
        (name, bytes) -> {
          var model = context.parse(bytes);
          models.put(model.thisClass().asInternalName(), model);
        });
    var ghosts = new Ghosts(new MissingNotification(), models);
    found = models.values().stream().flatMap(model -> ghosts.find(model).stream()).toList();
  }

  @Test
  void aWriteIsAGhostWhereItsPeersFireAndItDoesNot() {
    assertThat(found.stream().map(GhostTruthTest::named).sorted())
        .containsExactly(
            "Account.applyInterest balance firePropertyChange(\"balance\") SAME_FIELD 3 of 5",
            "Account.restore balance firePropertyChange(\"balance\") SAME_FIELD 3 of 5",
            "Account.thaw status firePropertyChange(\"status\") SAME_FIELD 2 of 3",
            "Branch.setOpeningHour openingHour firePropertyChange(\"openingHour\") SIBLING_FIELDS 3 of 4",
            "Customer.setRating rating firePropertyChange(\"rating\") SIBLING_FIELDS 3 of 4");
  }

  @Test
  void aPeerIsWhereTheWriteThatFiresIs() {
    var thaw =
        found.stream()
            .filter(ghost -> ghost.mutant().methodName().equals("thaw"))
            .findFirst()
            .orElseThrow();

    assertThat(thaw.peers())
        .extracting(
            peer ->
                peer.method() + ":" + lines.get(peer.line() - 1).strip() + ":" + peer.property())
        .containsExactly("setStatus:this.status = status;:", "freeze:status = \"frozen\";:status");
  }

  /// Each ghost tells a listener what the source with its call written in does, and something the
  /// original doesn't.
  @Test
  void everyGhostIsItsCallWrittenIntoTheSource() throws Exception {
    var mutations = new Mutations(ClassFile.of());
    var failures = new ArrayList<String>();
    var variants = variants();
    var original = Javac.loader(classes);
    for (int at = 0; at < found.size(); at++) {
      var mutant = found.get(at).mutant();
      var model = models.get(mutant.className());
      var mutated = new HashMap<>(classes);
      mutated.put(mutant.binaryName(), mutations.apply(model, mutant));
      var heard = heard(Javac.loader(mutated), mutant.binaryName(), mutant);
      var variant = variants.get(at);
      var inVariant = variant.className() + mutant.binaryName().substring(OUTER.length());
      if (!heard.equals(heard(Javac.loader(variant.classes()), inVariant, mutant))) {
        failures.add(named(found.get(at)) + ": isn't its call written in: " + heard);
      }
      if (heard.equals(heard(original, mutant.binaryName(), mutant))) {
        failures.add(named(found.get(at)) + ": changes nothing");
      }
    }
    assertThat(failures).isEmpty();
  }

  /// A ghost's source, compiled: its outer class is named for its text.
  private record Variant(String className, Map<String, byte[]> classes) {}

  /// Each ghost's source: the fixture with its call written in, the old value read before the write
  /// and the new one after it.
  private static List<Variant> variants() throws IOException {
    var sources = new ArrayList<Javac.Source>();
    for (var ghost : found) {
      var mutant = ghost.mutant();
      var variant = new ArrayList<>(lines);
      var simple = mutant.className().substring(mutant.className().lastIndexOf('$') + 1);
      var call =
          "%s(\"%s\", ghostOld, this.%s);"
              .formatted(
                  SINKS.get(simple),
                  MissingNotification.property(mutant.replacement()),
                  ghost.field());
      int line = mutant.line() - 1;
      variant.set(
          line,
          "var ghostOld = this.%s; %s %s"
              .formatted(ghost.field(), variant.get(line).strip(), call));
      var text = String.join("\n", variant);
      var name = Javac.nameFor("Ledger", text);
      sources.add(
          new Javac.Source("ghosts." + name, text.replace("class Ledger", "class " + name)));
    }
    var compiled = Javac.compile(sources, List.of());
    return sources.stream().map(source -> new Variant(source.className(), compiled)).toList();
  }

  /// What a listener hears when the mutant's method runs on a new bean.
  private static List<String> heard(ClassLoader loader, String className, Mutant mutant)
      throws Exception {
    var type = loader.loadClass(className);
    var bean = type.getConstructor().newInstance();
    var heard = new ArrayList<String>();
    PropertyChangeListener listener =
        event ->
            heard.add(
                event.getPropertyName() + " " + event.getOldValue() + " → " + event.getNewValue());
    type.getMethod("addPropertyChangeListener", PropertyChangeListener.class)
        .invoke(bean, listener);
    Method method =
        java.util.Arrays.stream(type.getMethods())
            .filter(each -> each.getName().equals(mutant.methodName()))
            .findFirst()
            .orElseThrow();
    method.invoke(bean, arguments(method));
    return heard;
  }

  private static Object[] arguments(Method method) {
    return java.util.Arrays.stream(method.getParameterTypes())
        .map(
            type ->
                type == long.class
                    ? (Object) 500L
                    : type == int.class
                        ? (Object) 1_200
                        : type == double.class ? (Object) 4.5 : (Object) "x")
        .toArray();
  }

  /// `Account.restore balance firePropertyChange("balance") SAME_FIELD 3 of 5`.
  private static String named(Ghosts.Found ghost) {
    var mutant = ghost.mutant();
    var simple = mutant.className().substring(mutant.className().lastIndexOf('$') + 1);
    int notified =
        ghost.rule() == Ghost.Rule.SAME_FIELD ? ghost.peers().size() : ghost.writes() - 1;
    return "%s.%s %s %s %s %d of %d"
        .formatted(
            simple,
            mutant.methodName(),
            ghost.field(),
            mutant.replacement(),
            ghost.rule(),
            notified,
            ghost.writes());
  }
}
