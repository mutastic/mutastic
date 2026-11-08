package io.mutastic.engine;

import io.mutastic.api.Ghost;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Finds where a write to a bean's field doesn't fire the property change that its peers do, on
/// bytecode evidence, and makes a ghost there: the peers' call, inserted after the write.
///
/// A class counts when its code fires property changes: a call to `PropertyChangeSupport`'s
/// `firePropertyChange`, or to a method of its own or of a superclass in the module that makes one.
/// Each write to one of its own fields, outside a constructor, `readObject`, `clone` and what the
/// compiler made, is notified when the same method fires a property change after it, or calls a
/// method of the class that does ([BeanWrites]). A method that fires a change with no name
/// (everything changed) is left out. Two rules make a ghost:
///
/// - **Same field:** at least two writes to the field are notified, and this one isn't.
/// - **Sibling fields:** at least three of the class's other fields fire on every write, and a
///   setter writes this one without firing.
///
/// What's inserted is the call most of the peers make, with the property name they give: the old
/// value read before the write, and the value written.
final class Ghosts {
  /// Rule A: at least this many writes to the field are notified.
  private static final int SAME_FIELD = 2;

  /// Rule B: at least this many other fields fire on every write.
  private static final int SIBLINGS = 3;

  private static final Set<String> EXEMPT = Set.of("<init>", "<clinit>", "readObject", "clone");

  /// A ghost found, with what makes it look missing; run, it becomes a [Ghost].
  record Found(
      Mutant mutant,
      String field,
      String property,
      Ghost.Rule rule,
      List<Ghost.Peer> peers,
      int writes) {
    Ghost with(MutantResult result) {
      return new Ghost(result, field, property, rule, peers, writes);
    }
  }

  /// Why a write looks like it's missing a call: the rule, what to insert, and the peers.
  private record Evidence(
      Ghost.Rule rule,
      String property,
      Optional<MissingNotification.Sink> sink,
      List<Ghost.Peer> peers,
      int writes) {}

  private final MissingNotification operator;
  private final BeanWrites writes;

  /// @param module the module's classes by internal name: where a superclass's method is found
  Ghosts(MissingNotification operator, Map<String, ClassModel> module) {
    this.operator = operator;
    this.writes = new BeanWrites(module);
  }

  /// The ghosts in `model`: none in a class that fires no property change.
  List<Found> find(ClassModel model) {
    var all = new ArrayList<BeanWrites.Write>();
    for (MethodModel method : model.methods()) {
      if (isLookedAt(model, method)) {
        all.addAll(writes.in(model, method));
      }
    }
    if (all.stream().noneMatch(write -> write.fired().flatMap(BeanWrites.Fire::sink).isPresent())) {
      return List.of();
    }
    var byField =
        all.stream()
            .collect(
                Collectors.groupingBy(
                    BeanWrites.Write::fieldName, LinkedHashMap::new, Collectors.toList()));
    var found = new ArrayList<Found>();
    byField.forEach((field, ofField) -> found.addAll(sameField(model, field, ofField)));
    byField.forEach(
        (field, ofField) -> found.addAll(siblingFields(model, field, ofField, byField)));
    return found;
  }

  private static boolean isLookedAt(ClassModel model, MethodModel method) {
    var flags = method.flags();
    return method.code().isPresent()
        && !EXEMPT.contains(method.methodName().stringValue())
        && !flags.has(AccessFlag.STATIC)
        && !flags.has(AccessFlag.SYNTHETIC)
        && !flags.has(AccessFlag.BRIDGE)
        && !Annotations.has(model, method, "DoNotMutate");
  }

  /// Rule A: the writes to `field` that aren't notified, where at least two others are.
  private List<Found> sameField(ClassModel model, String field, List<BeanWrites.Write> ofField) {
    var notified = ofField.stream().filter(BeanWrites.Write::notified).toList();
    var fired = notified.stream().filter(write -> write.fired().isPresent()).toList();
    if (notified.size() < SAME_FIELD || fired.isEmpty()) {
      return List.of();
    }
    var property = mostCommon(fired, write -> write.fired().orElseThrow().property().orElse(field));
    var evidence =
        new Evidence(
            Ghost.Rule.SAME_FIELD,
            property,
            sinkOf(fired),
            notified.stream().map(Ghosts::peer).toList(),
            ofField.size());
    return ofField.stream()
        .filter(write -> !write.notified())
        .flatMap(write -> ghost(model, write, evidence).stream())
        .toList();
  }

  /// Rule B: a setter's write to `field`, which no write fires for, where at least three other
  /// fields fire on every write.
  private List<Found> siblingFields(
      ClassModel model,
      String field,
      List<BeanWrites.Write> ofField,
      Map<String, List<BeanWrites.Write>> byField) {
    if (ofField.stream().anyMatch(BeanWrites.Write::notified)) {
      return List.of();
    }
    var siblings = alwaysFiring(field, byField);
    if (siblings.size() < SIBLINGS) {
      return List.of();
    }
    var evidence =
        new Evidence(
            Ghost.Rule.SIBLING_FIELDS,
            field,
            sinkOf(siblings.stream().flatMap(List::stream).toList()),
            siblings.stream().map(writes -> peer(writes.getFirst())).toList(),
            siblings.size() + 1);
    return ofField.stream()
        .filter(BeanWrites.Write::inSetter)
        .flatMap(write -> ghost(model, write, evidence).stream())
        .toList();
  }

  /// The writes of each field but `field` that fires on every write.
  private static List<List<BeanWrites.Write>> alwaysFiring(
      String field, Map<String, List<BeanWrites.Write>> byField) {
    return byField.entrySet().stream()
        .filter(entry -> !entry.getKey().equals(field))
        .map(Map.Entry::getValue)
        .filter(writes -> writes.stream().allMatch(write -> write.fired().isPresent()))
        .toList();
  }

  /// A notified write, with the name it fires: none when a method of the class fires for it.
  private static Ghost.Peer peer(BeanWrites.Write write) {
    var property = write.fired().map(fire -> fire.property().orElse(write.fieldName())).orElse("");
    return new Ghost.Peer(write.method().methodName().stringValue(), write.line(), property);
  }

  /// The call most of the peers make, when it can be copied.
  private static Optional<MissingNotification.Sink> sinkOf(List<BeanWrites.Write> peers) {
    var sinks =
        peers.stream()
            .map(write -> write.fired().flatMap(BeanWrites.Fire::sink))
            .flatMap(Optional::stream)
            .toList();
    return sinks.isEmpty() ? Optional.empty() : Optional.of(mostCommon(sinks, sink -> sink));
  }

  private static <T, K> K mostCommon(List<T> items, Function<T, K> key) {
    var counts =
        items.stream()
            .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()));
    return counts.entrySet().stream()
        .max(Comparator.comparingLong(Map.Entry::getValue))
        .orElseThrow()
        .getKey();
  }

  private Optional<Found> ghost(ClassModel model, BeanWrites.Write write, Evidence evidence) {
    var sink = evidence.sink();
    if (sink.isEmpty() || !MissingNotification.canCopy(sink.get(), write.field())) {
      return Optional.empty();
    }
    var owner = model.thisClass().asInternalName();
    operator.remember(owner, write.fieldName(), sink.get());
    var mutant = mutantAt(owner, write, evidence.property());
    return Optional.of(
        new Found(
            mutant,
            write.fieldName(),
            evidence.property(),
            evidence.rule(),
            evidence.peers(),
            evidence.writes()));
  }

  /// The ghost's mutant: at the write, by its place among the method's instructions.
  private Mutant mutantAt(String owner, BeanWrites.Write write, String property) {
    return new Mutant(
        owner,
        write.method().methodName().stringValue(),
        write.method().methodType().stringValue(),
        write.index(),
        write.line(),
        operator,
        "PUTFIELD " + owner + "." + write.fieldName(),
        MissingNotification.label(property),
        Optional.empty());
  }
}
