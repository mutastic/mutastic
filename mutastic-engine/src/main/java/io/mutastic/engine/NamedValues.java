package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Suggestion;
import java.io.IOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/// The value a suggested call is at the edge of, when the source names it, so the test says it as
/// the source does: a parameter compared with a constant javac wrote in as its number (`total >=
/// FREE_FROM`, where the bytecode has `100`), passed that number; or with a field the constructor
/// sets from one of its arguments (`total >= freeFrom`), passed what the constructor was. Only an
/// exact match counts: the parameter's argument is the constant's value, or the constructor's
/// argument for the field.
final class NamedValues {
  /// What a suggested call says differently, for a value the source names.
  ///
  /// @param arguments the method's arguments written otherwise, by index: `Pricing.FREE_FROM`
  /// @param constructed the innermost constructor's arguments written otherwise, by index
  record Naming(
      Suggestion.Named named, Map<Integer, String> arguments, Map<Integer, String> constructed) {}

  /// The suggested call, as far as it matters here.
  ///
  /// @param parameters the method's parameter names
  /// @param inputs the method's arguments
  /// @param constructor the innermost receiver's constructor, if the call makes one
  /// @param constructed that constructor's arguments
  /// @param testClass the test class it goes in, by binary name
  /// @param built how a covering test makes the receiver, when the call makes it the same way
  record Call(
      List<String> parameters,
      List<Long> inputs,
      Optional<MethodModel> constructor,
      List<Long> constructed,
      String testClass,
      Optional<Constructions.Built> built) {}

  private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

  private final Map<String, ClassModel> models;
  private final List<Path> sources;

  /// @param models the project's classes, by binary name
  /// @param sources the source directories, where the comparison's statement is read
  NamedValues(Map<String, ClassModel> models, List<Path> sources) {
    this.models = models;
    this.sources = sources;
  }

  /// How the call says the value the mutant's comparison is at, if the source names it.
  Optional<Naming> of(Mutant mutant, Call call) {
    var operands = mutant.operands();
    if (operands.size() != 2) {
      return Optional.empty();
    }
    for (int side = 0; side < 2; side++) {
      int parameter = call.parameters().indexOf(operands.get(side));
      if (parameter >= 0) {
        var other = operands.get(1 - side);
        var found =
            other.matches("-?\\d+")
                ? constant(mutant, call, parameter, Long.parseLong(other))
                : field(mutant, call, parameter, other);
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return Optional.empty();
  }

  /// `total >= FREE_FROM` with `total` passed 100: the constant in the call, if the test can use
  /// it, and its value in the comment.
  private Optional<Naming> constant(Mutant mutant, Call call, int parameter, long value) {
    if (call.inputs().get(parameter) != value) {
      return Optional.empty();
    }
    var name = call.parameters().get(parameter);
    return writtenAgainst(mutant, name)
        .flatMap(written -> declared(mutant, written, value))
        .map(constant -> constantNaming(constant, call, parameter, value));
  }

  /// `Discounts.FREE_FROM` in the call and `FREE_FROM = 100` in the comment, or for a constant the
  /// test can't use, the number, and `100 is Discounts's FREE_FROM`.
  private static Naming constantNaming(Constant constant, Call call, int parameter, long value) {
    var owner = simpleName(constant.owner());
    var field = constant.field().fieldName().stringValue();
    if (!reachable(constant, call.testClass())) {
      return new Naming(
          new Suggestion.Named(
              field,
              "%d is %s's %s".formatted(value, owner, field),
              Optional.empty(),
              "%s (%s's private %s = %d)".formatted(field, owner, field, value)),
          Map.of(),
          Map.of());
    }
    return new Naming(
        new Suggestion.Named(
            field,
            field + " = " + value,
            Optional.empty(),
            "%s (%s.%s = %d)".formatted(field, owner, field, value)),
        Map.of(parameter, owner + "." + field),
        Map.of());
  }

  /// `total >= freeFrom`, with `freeFrom` set by the constructor from an argument the call passes
  /// the same value as `total`: the value the covering test made the receiver with, said, when the
  /// call makes it that way; else one local, passed to both.
  private Optional<Naming> field(Mutant mutant, Call call, int parameter, String field) {
    if (call.constructor().isEmpty() || !NAME.matcher(field).matches() || field.contains(".")) {
      return Optional.empty();
    }
    var constructor = call.constructor().get();
    int setBy = setFrom(constructor, field);
    long input = call.inputs().get(parameter);
    int argument =
        setBy - constructor.methodTypeSymbol().parameterCount() + call.constructed().size();
    if (!passes(call.constructed(), argument, input)) {
      return Optional.empty();
    }
    var tie =
        new Tie(field, constructor.parent().map(NamedValues::simpleName).orElse(""), parameter);
    if (call.built().isPresent()) {
      return Optional.of(tie.fromATest(call));
    }
    var passed = java.lang.constant.MethodTypeDesc.ofDescriptor(mutant.descriptor());
    var types =
        List.of(
            constructor.methodTypeSymbol().parameterType(setBy), passed.parameterType(parameter));
    return local(field, input, types.stream().map(type -> type.descriptorString()).toList())
        .map(local -> tie.withALocal(local, argument));
  }

  /// A field of `owner`'s the call's argument at `parameter` is compared with.
  private record Tie(String field, String owner, int parameter) {
    /// Made as a covering test makes it: its value is the test's, said, not a local's.
    Naming fromATest(Call call) {
      return new Naming(
          new Suggestion.Named(
              field,
              "%s == %s".formatted(call.parameters().get(parameter), field),
              Optional.empty(),
              "%s = %d (%s's field, from %s)"
                  .formatted(
                      field,
                      call.inputs().get(parameter),
                      owner,
                      call.built().orElseThrow().text())),
          Map.of(),
          Map.of());
    }

    /// One local, `local`, passed to the method and to the constructor's `argument`.
    Naming withALocal(String local, int argument) {
      return new Naming(
          new Suggestion.Named(
              field,
              "",
              Optional.of(local),
              "%s (%s's field, set by its constructor)".formatted(field, owner)),
          Map.of(parameter, field),
          Map.of(argument, field));
    }
  }

  /// Whether the constructor's argument at `argument` is there, and is `value`.
  private static boolean passes(List<Long> constructed, int argument, long value) {
    return argument >= 0 && argument < constructed.size() && constructed.get(argument) == value;
  }

  /// `int freeFrom = 20;`: a local of a type that passes as both `types`, if one does. An `int`
  /// widens to a `long` and boxes to an `Integer`, but isn't boxed to a `Long`.
  private static Optional<String> local(String field, long value, List<String> types) {
    boolean anInt = types.contains("I") || types.contains("Ljava/lang/Integer;");
    if (anInt && types.contains("Ljava/lang/Long;")) {
      return Optional.empty();
    }
    return Optional.of(
        anInt ? "int %s = %d;".formatted(field, value) : "long %s = %dL;".formatted(field, value));
  }

  /// Which of the constructor's parameters, by index, it stores in `field` as it is: `this.freeFrom
  /// = freeFrom`. -1 if none.
  private static int setFrom(MethodModel constructor, String field) {
    var code = constructor.code().map(model -> model.elementList()).orElse(List.of());
    var instructions =
        code.stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
    for (int at = 2; at < instructions.size(); at++) {
      if (instructions.get(at) instanceof FieldInstruction put
          && put.opcode() == Opcode.PUTFIELD
          && put.name().equalsString(field)
          && instructions.get(at - 1) instanceof LoadInstruction value
          && instructions.get(at - 2) instanceof LoadInstruction self
          && self.slot() == 0) {
        return parameterAt(constructor, value.slot());
      }
    }
    return -1;
  }

  /// The parameter, by index, in `slot` as the method starts; -1 if it's no parameter's.
  private static int parameterAt(MethodModel method, int slot) {
    int next = 1;
    var types = method.methodTypeSymbol().parameterList();
    for (int index = 0; index < types.size(); index++) {
      if (next == slot) {
        return index;
      }
      var type = types.get(index).descriptorString();
      next += type.equals("J") || type.equals("D") ? 2 : 1;
    }
    return -1;
  }

  /// The name the source compares `parameter` with on the mutant's line: `FREE_FROM` in `total >=
  /// FREE_FROM` or `FREE_FROM <= total`, `Pricing.FREE_FROM` qualified.
  private Optional<String> writtenAgainst(Mutant mutant, String parameter) {
    var statement = statement(mutant);
    var quoted = Pattern.quote(parameter);
    var comparison = "\\s*(?:[<>]=?|[=!]=)\\s*";
    var after = Pattern.compile("(?<![\\w$.])" + quoted + comparison + "(?<name>" + NAME + ")");
    var before = Pattern.compile("(?<name>" + NAME + ")" + comparison + quoted + "(?![\\w$])");
    for (var pattern : List.of(after, before)) {
      var found = pattern.matcher(statement);
      if (found.find() && !found.group("name").equals(parameter)) {
        return Optional.of(found.group("name"));
      }
    }
    return Optional.empty();
  }

  /// A constant's declaration and the class it's in.
  private record Constant(ClassModel owner, FieldModel field) {}

  /// The `static final` field `written` names, of `value`: in the mutant's class or one it's nested
  /// in, or the class it's qualified by.
  private Optional<Constant> declared(Mutant mutant, String written, long value) {
    int dot = written.lastIndexOf('.');
    var field = written.substring(dot + 1);
    var owners =
        dot < 0
            ? enclosing(mutant.binaryName())
            : models.values().stream()
                .filter(model -> simpleName(model).equals(written.substring(0, dot)))
                .toList();
    return owners.stream().flatMap(owner -> constantIn(owner, field, value).stream()).findFirst();
  }

  /// The class, then each it's nested in.
  private List<ClassModel> enclosing(String binaryName) {
    var found = new java.util.ArrayList<ClassModel>();
    var name = binaryName;
    while (true) {
      var model = models.get(name);
      if (model != null) {
        found.add(model);
      }
      int dollar = name.lastIndexOf('$');
      if (dollar < 0) {
        return found;
      }
      name = name.substring(0, dollar);
    }
  }

  private static Optional<Constant> constantIn(ClassModel owner, String name, long value) {
    return owner.fields().stream()
        .filter(field -> field.fieldName().equalsString(name))
        .filter(
            field -> field.flags().has(AccessFlag.STATIC) && field.flags().has(AccessFlag.FINAL))
        .filter(
            field ->
                field
                    .findAttribute(java.lang.classfile.Attributes.constantValue())
                    .map(ConstantValueAttribute::constant)
                    .map(constant -> constant.constantValue())
                    .filter(Number.class::isInstance)
                    .map(number -> ((Number) number).longValue() == value)
                    .orElse(false))
        .findFirst()
        .map(field -> new Constant(owner, field));
  }

  /// Whether a test in `testClass` can name the constant: it's public, or not private and in the
  /// test's package. A Kotlin class's constants are left as numbers.
  private static boolean reachable(Constant constant, String testClass) {
    var flags = constant.field().flags();
    if (flags.has(AccessFlag.PRIVATE) || KotlinGenerated.isKotlin(constant.owner())) {
      return false;
    }
    var owner = constant.owner().thisClass().asInternalName().replace('/', '.');
    return flags.has(AccessFlag.PUBLIC) || packageOf(owner).equals(packageOf(testClass));
  }

  private static String packageOf(String binaryName) {
    int dot = binaryName.lastIndexOf('.');
    return dot < 0 ? "" : binaryName.substring(0, dot);
  }

  /// `Pricing`, or `Outer.Inner` for a nested class.
  private static String simpleName(ClassModel model) {
    var internal = model.thisClass().asInternalName();
    return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
  }

  /// The mutant's statement in its source, from its line to the one that ends it; empty if the
  /// source isn't there.
  private String statement(Mutant mutant) {
    var lines = sourceLines(mutant);
    int first = mutant.line() - 1;
    var text = new StringBuilder();
    for (int at = Math.max(0, first); at < Math.min(lines.size(), first + 12); at++) {
      text.append(lines.get(at).replaceAll("//.*$", "")).append('\n');
      var end = lines.get(at).strip();
      if (end.endsWith(";") || end.endsWith("{") || end.endsWith("}")) {
        break;
      }
    }
    return text.toString();
  }

  /// The lines of the mutant's source file, if it's there.
  private List<String> sourceLines(Mutant mutant) {
    var name = mutant.className();
    int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
    var file = (dollar < 0 ? name : name.substring(0, dollar)) + ".java";
    for (Path source : sources) {
      var path = source.resolve(file);
      if (Files.isRegularFile(path)) {
        try {
          return Files.readAllLines(path);
        } catch (IOException e) {
          return List.of();
        }
      }
    }
    return List.of();
  }
}
