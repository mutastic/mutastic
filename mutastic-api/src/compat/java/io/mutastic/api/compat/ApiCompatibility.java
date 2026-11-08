package io.mutastic.api.compat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/// Fails when the plugin API breaks what a plugin built against the last release relies on: the
/// types a plugin implements (`Mutator`, `ClassMutator`) and every type their signatures reach,
/// compared class file to class file with the last release's `mutastic-api` jar.
///
/// A break is a promised type gone or no longer public, a public member gone or with another
/// signature, and an abstract method a plugin's class would now have to write. What's marked
/// `@Internal` now isn't promised.
///
/// Usage: `ApiCompatibility <baseline jar> <classes directory> <root type>...`, root types by
/// internal name (`io/mutastic/api/Mutator`).
public final class ApiCompatibility {
  private static final String PACKAGE = "io/mutastic/api/";
  private static final String INTERNAL = "Lio/mutastic/api/Internal;";

  /// A type of the API's package, named in a descriptor or a generic signature.
  private static final Pattern API_TYPE =
      Pattern.compile("L(?<name>io/mutastic/api/[A-Za-z0-9_$/]+)[;<]");

  private final Map<String, ClassModel> baseline;
  private final Map<String, ClassModel> current;
  private final List<String> breaks = new ArrayList<>();

  ApiCompatibility(Map<String, ClassModel> baseline, Map<String, ClassModel> current) {
    this.baseline = baseline;
    this.current = current;
  }

  public static void main(String[] args) throws IOException {
    var check = new ApiCompatibility(fromJar(Path.of(args[0])), fromDirectory(Path.of(args[1])));
    var found = check.breaks(List.of(args).subList(2, args.length));
    if (!found.isEmpty()) {
      found.forEach(System.err::println);
      System.exit(1);
    }
  }

  /// Every break, for the promised types reached from `roots`.
  List<String> breaks(List<String> roots) {
    for (String name : promised(roots)) {
      var now = current.get(name);
      if (now == null) {
        breaks.add(simple(name) + " is gone");
      } else if (!isInternal(now)) {
        compare(baseline.get(name), now);
      }
    }
    return List.copyOf(breaks);
  }

  /// The roots and every API type the baseline's public signatures of them reach, but not a type
  /// marked internal now.
  SequencedSet<String> promised(List<String> roots) {
    var promised = new LinkedHashSet<String>();
    var pending = new ArrayDeque<>(roots);
    while (!pending.isEmpty()) {
      var name = pending.poll();
      var type = baseline.get(name);
      if (type == null || !promised.add(name) || isInternalNow(name)) {
        continue;
      }
      reached(type).stream().filter(baseline::containsKey).forEach(pending::add);
    }
    promised.removeIf(this::isInternalNow);
    return promised;
  }

  private boolean isInternalNow(String name) {
    var now = current.get(name);
    return now != null && isInternal(now);
  }

  /// The API types `type`'s supertypes, nested types and public members name.
  private SequencedSet<String> reached(ClassModel type) {
    var texts = new ArrayList<String>();
    type.superclass().ifPresent(superclass -> texts.add("L" + superclass.asInternalName() + ";"));
    type.interfaces().forEach(each -> texts.add("L" + each.asInternalName() + ";"));
    type.findAttribute(Attributes.permittedSubclasses())
        .ifPresent(
            permitted ->
                permitted
                    .permittedSubclasses()
                    .forEach(each -> texts.add("L" + each.asInternalName() + ";")));
    type.findAttribute(Attributes.innerClasses())
        .ifPresent(
            inner ->
                inner.classes().stream()
                    .filter(each -> each.flags().contains(AccessFlag.PUBLIC))
                    .forEach(each -> texts.add("L" + each.innerClass().asInternalName() + ";")));
    publicMethods(type).forEach(method -> texts.add(signatureOf(method)));
    publicFields(type).forEach(field -> texts.add(signatureOf(field)));
    var names = new LinkedHashSet<String>();
    texts.forEach(
        text -> API_TYPE.matcher(text).results().forEach(match -> names.add(match.group("name"))));
    return names;
  }

  private void compare(ClassModel before, ClassModel now) {
    var name = simple(before.thisClass().asInternalName());
    if (!now.flags().has(AccessFlag.PUBLIC)) {
      breaks.add(name + " is no longer public");
    }
    if (!kind(before).equals(kind(now))) {
      breaks.add(name + " was " + kind(before) + " and is now " + kind(now));
    }
    compareMethods(name, before, now);
    compareFields(name, before, now);
    if (isImplementable(now)) {
      var added = new LinkedHashSet<>(abstractMethods(now, current));
      added.removeAll(abstractMethods(before, baseline));
      added.forEach(method -> breaks.add(name + " gained abstract " + method));
    }
  }

  private void compareMethods(String type, ClassModel before, ClassModel now) {
    var methods = new LinkedHashMap<String, MethodModel>();
    publicMethods(now).forEach(method -> methods.put(key(method), method));
    for (MethodModel method : publicMethods(before).toList()) {
      var same = methods.get(key(method));
      if (same == null) {
        breaks.add(type + "." + key(method) + " is gone");
      } else if (!isInternal(same)) {
        compareMember(type + "." + key(method), method.flags(), same.flags());
        compareSignature(type + "." + key(method), signatureOf(method), signatureOf(same));
      }
    }
  }

  private void compareFields(String type, ClassModel before, ClassModel now) {
    var fields = new LinkedHashMap<String, FieldModel>();
    publicFields(now).forEach(field -> fields.put(key(field), field));
    for (FieldModel field : publicFields(before).toList()) {
      var same = fields.get(key(field));
      if (same == null) {
        breaks.add(type + "." + key(field) + " is gone");
      } else if (!isInternal(same)) {
        compareMember(type + "." + key(field), field.flags(), same.flags());
      }
    }
  }

  private void compareMember(String member, AccessFlags before, AccessFlags now) {
    if (before.has(AccessFlag.STATIC) != now.has(AccessFlag.STATIC)) {
      breaks.add(member + (now.has(AccessFlag.STATIC) ? " is now static" : " is no longer static"));
    }
    if (!before.has(AccessFlag.FINAL) && now.has(AccessFlag.FINAL)) {
      breaks.add(member + " is now final");
    }
  }

  private void compareSignature(String member, String before, String now) {
    if (!before.equals(now)) {
      breaks.add(member + " was " + before + " and is now " + now);
    }
  }

  /// An interface a plugin's class implements: one that isn't sealed.
  private static boolean isImplementable(ClassModel type) {
    return type.flags().has(AccessFlag.INTERFACE)
        && type.findAttribute(Attributes.permittedSubclasses()).isEmpty();
  }

  /// The abstract methods a class implementing `type` must write: its own and its API
  /// superinterfaces', less those a more specific interface gives a body.
  private static SequencedSet<String> abstractMethods(
      ClassModel type, Map<String, ClassModel> models) {
    var bodies = new TreeMap<String, Boolean>();
    collectAbstract(type, models, bodies, new LinkedHashSet<>());
    var methods = new LinkedHashSet<String>();
    bodies.forEach(
        (method, isAbstract) -> {
          if (isAbstract) {
            methods.add(method);
          }
        });
    return methods;
  }

  private static void collectAbstract(
      ClassModel type,
      Map<String, ClassModel> models,
      Map<String, Boolean> into,
      SequencedSet<String> seen) {
    if (!seen.add(type.thisClass().asInternalName())) {
      return;
    }
    for (MethodModel method : type.methods()) {
      if (!method.flags().has(AccessFlag.STATIC) && !method.flags().has(AccessFlag.PRIVATE)) {
        into.putIfAbsent(key(method), method.flags().has(AccessFlag.ABSTRACT));
      }
    }
    type.interfaces().stream()
        .map(each -> models.get(each.asInternalName()))
        .filter(java.util.Objects::nonNull)
        .forEach(superinterface -> collectAbstract(superinterface, models, into, seen));
  }

  private static Stream<MethodModel> publicMethods(ClassModel type) {
    return type.methods().stream()
        .filter(method -> isVisible(method.flags()))
        .filter(method -> !method.flags().has(AccessFlag.SYNTHETIC))
        .filter(method -> !method.flags().has(AccessFlag.BRIDGE));
  }

  private static Stream<FieldModel> publicFields(ClassModel type) {
    return type.fields().stream()
        .filter(field -> isVisible(field.flags()))
        .filter(field -> !field.flags().has(AccessFlag.SYNTHETIC));
  }

  private static boolean isVisible(AccessFlags flags) {
    return flags.has(AccessFlag.PUBLIC) || flags.has(AccessFlag.PROTECTED);
  }

  /// `words(Lio/mutastic/api/Mutant;)Lio/mutastic/api/Mutator$Words;`
  private static String key(MethodModel method) {
    return method.methodName().stringValue() + method.methodType().stringValue();
  }

  private static String key(FieldModel field) {
    return field.fieldName().stringValue() + ":" + field.fieldType().stringValue();
  }

  /// The generic signature where there is one, which says more than the descriptor: `List<Change>`,
  /// not `List`.
  private static String signatureOf(MethodModel method) {
    return method
        .findAttribute(Attributes.signature())
        .map(signature -> signature.signature().stringValue())
        .orElse(method.methodType().stringValue());
  }

  private static String signatureOf(FieldModel field) {
    return field
        .findAttribute(Attributes.signature())
        .map(signature -> signature.signature().stringValue())
        .orElse(field.fieldType().stringValue());
  }

  private static String kind(ClassModel type) {
    if (type.flags().has(AccessFlag.ANNOTATION)) {
      return "an annotation";
    }
    if (type.flags().has(AccessFlag.INTERFACE)) {
      return "an interface";
    }
    if (type.flags().has(AccessFlag.ENUM)) {
      return "an enum";
    }
    return type.findAttribute(Attributes.record()).isPresent() ? "a record" : "a class";
  }

  /// Marked `@Internal`, which is kept in the class file but not loaded at run time.
  static boolean isInternal(AttributedElement element) {
    return element
        .findAttribute(Attributes.runtimeInvisibleAnnotations())
        .map(found -> found.annotations().stream().map(Annotation::className))
        .orElseGet(Stream::empty)
        .anyMatch(name -> name.stringValue().equals(INTERNAL));
  }

  /// `Mutator$Words` → `Mutator.Words`.
  private static String simple(String name) {
    return name.substring(PACKAGE.length()).replace('$', '.');
  }

  static Map<String, ClassModel> fromJar(Path jar) throws IOException {
    var models = new LinkedHashMap<String, ClassModel>();
    try (var zip = new ZipFile(jar.toFile())) {
      for (var entry : java.util.Collections.list(zip.entries())) {
        if (entry.getName().startsWith(PACKAGE) && entry.getName().endsWith(".class")) {
          try (var in = zip.getInputStream(entry)) {
            var model = ClassFile.of().parse(in.readAllBytes());
            models.put(model.thisClass().asInternalName(), model);
          }
        }
      }
    }
    return models;
  }

  static Map<String, ClassModel> fromDirectory(Path classes) throws IOException {
    var models = new LinkedHashMap<String, ClassModel>();
    try (Stream<Path> files = Files.walk(classes.resolve(PACKAGE))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
        var model = ClassFile.of().parse(read(file));
        models.put(model.thisClass().asInternalName(), model);
      }
    }
    return models;
  }

  private static byte[] read(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
