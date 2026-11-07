package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// A call-site operator described as data, in a rules file: which calls (owner and method name) and
/// how to rewrite them. The built-in stream rules and a plugin's rules are the same kind of file
/// ([CallRules]); the engine does the bytecode.
///
/// A rewrite is one of:
///
/// - `remove`: drop the call and its arguments, keeping the receiver as its result. Only for a call
///   that returns its receiver's type, a stream stage say.
/// - `rename a->b, c->d`: the same arguments, another method of the same type.
/// - `omit last argument`: the same method without its last argument, for the overloads that
///   `descriptors` lists, each of which must have that shorter one.
/// - `unchecked arithmetic`: `Math.addExact` and the like as the plain operation, which overflows
///   rather than throwing.
/// - `keep argument`: what the call was given goes on in its place, what it was called on dropped.
///   Only for a call of one argument of the type it returns: `opt.orElse(x)` becomes `x`.
/// - `call name(ARGS) with A, B`: drop the arguments and call another method on the same receiver
///   with these, returning the same type. An argument is `0`, `0L`, `Integer.MAX_VALUE`,
///   `Long.MAX_VALUE`, `true`, `false`, `null` or a static no-argument call, `Function.identity()`
///   written in full.
///
/// @param descriptors the overloads it matches, as the JVM writes them (`(Ljava/lang/String;)Z`),
///   all of them when empty
/// @param since the Java release the rewrite needs, `11`; in a class file older than that the
///   mutant is skipped as unviable, as the method may not be there and calling it would look like a
///   catch. `0` for any
/// @param templates the `headline` and `help` wording, with any per-method variants
///   (`headline.sorted`): `{name}` is the call's method, `{to}` a rename's target, `{method}` the
///   method it's in
public record CallRule(
    String id,
    String explanation,
    Tier tier,
    Set<String> owners,
    Set<String> methods,
    Set<String> descriptors,
    int since,
    String rewrite,
    String source,
    String changed,
    Map<String, String> templates)
    implements Mutator {
  static final String OMIT_LAST = "omit last argument";
  static final String UNCHECKED = "unchecked arithmetic";
  static final String KEEP_ARGUMENT = "keep argument";

  /// What a class file's major version is more than the Java release it's for.
  private static final int RELEASE_TO_MAJOR = 44;

  /// The terminal operations whose result doesn't depend on order, or on duplicates.
  private static final Set<String> ORDER_FREE =
      Set.of(
          "count", "anyMatch", "allMatch", "noneMatch", "min", "max", "findAny", "sum", "average");

  private static final Set<String> DUPLICATE_FREE =
      Set.of("anyMatch", "allMatch", "noneMatch", "min", "max", "findAny");

  public CallRule {
    owners = Set.copyOf(owners);
    methods = Set.copyOf(methods);
    descriptors = Set.copyOf(descriptors);
    templates = Map.copyOf(templates);
  }

  @Override
  public boolean onCalls() {
    return true;
  }

  @Override
  public List<Change> changes(Site site) {
    if (!(site.instruction() instanceof InvokeInstruction call) || !matches(call)) {
      return List.of();
    }
    var changes = rewritten(call, site);
    return site.owner().majorVersion() >= since + RELEASE_TO_MAJOR
        ? changes
        : changes.stream().map(change -> tooOld(change, call)).toList();
  }

  private Change tooOld(Change change, InvokeInstruction call) {
    return new Change(
        change.label(),
        Optional.of(
            "unviable: `%s` becomes something that needs Java %d, and this class is for an older one"
                .formatted(call.name().stringValue(), since)));
  }

  private List<Change> rewritten(InvokeInstruction call, Site site) {
    var name = call.name().stringValue();
    if (rewrite.equals("remove")) {
      if (!call.typeSymbol().returnType().equals(call.owner().asSymbol())) {
        return List.of(); // nothing to leave in its place
      }
      var skipped =
          equivalent(call, site.code(), site.index())
              .or(() -> EndlessStreams.whyNotRemoved(call, site.code(), site.index()));
      return List.of(new Change("remove", skipped));
    }
    if (rewrite.startsWith("rename ")) {
      return renames().stream()
          .filter(pair -> pair[0].equals(name))
          .map(
              pair ->
                  new Change(
                      pair[1], LibrarySwaps.equivalent(call, pair[1], site.code(), site.index())))
          .toList();
    }
    if (rewrite.equals(OMIT_LAST) || rewrite.equals(UNCHECKED) || rewrite.equals(KEEP_ARGUMENT)) {
      return List.of(Change.of(rewrite));
    }
    return List.of(Change.of(source.isEmpty() ? rewrite.substring("call ".length()) : source));
  }

  private boolean matches(InvokeInstruction call) {
    return owners.contains(call.owner().asInternalName().replace('/', '.'))
        && methods.contains(call.name().stringValue())
        && (descriptors.isEmpty() || descriptors.contains(call.type().stringValue()));
  }

  private List<String[]> renames() {
    return Arrays.stream(rewrite.substring("rename ".length()).split(","))
        .map(String::strip)
        .map(
            pair ->
                new String[] {
                  pair.substring(0, pair.indexOf("->")), pair.substring(pair.indexOf("->") + 2)
                })
        .toList();
  }

  /// Why removing `.sorted()` or `.distinct()` can't change the result, if it can't: the next call
  /// on the stream (stepping over the argument it builds) ignores order, or duplicates.
  private static Optional<String> equivalent(
      InvokeInstruction call, List<CodeElement> code, int at) {
    var name = call.name().stringValue();
    if (!name.equals("sorted") && !name.equals("distinct")) {
      return Optional.empty();
    }
    var ignored = name.equals("sorted") ? ORDER_FREE : DUPLICATE_FREE;
    return nextCall(call, code, at)
        .filter(next -> ignored.contains(next.name().stringValue()))
        .map(
            next ->
                "equivalent: `.%s()` can't change what `.%s(%s)` gives"
                    .formatted(
                        name,
                        next.name().stringValue(),
                        next.typeSymbol().parameterCount() == 0 ? "" : "…"));
  }

  /// The next call on the same stream, stepping over the argument it builds; none if anything else
  /// comes first.
  private static Optional<InvokeInstruction> nextCall(
      InvokeInstruction call, List<CodeElement> code, int at) {
    for (int next = at + 1; next < code.size(); next++) {
      switch (code.get(next)) {
        case InvokeInstruction following when following.owner().equals(call.owner()) -> {
          return Optional.of(following);
        }
        case InvokeDynamicInstruction _, LoadInstruction _, ConstantInstruction _ -> {
          // building the next call's argument
        }
        case Instruction _ -> {
          return Optional.empty();
        }
        default -> {
          // labels and line numbers
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void write(CodeBuilder builder, Instruction instruction, String label) {
    var call = (InvokeInstruction) instruction;
    if (rewrite.startsWith("rename ")) {
      invoke(builder, call, label, call.typeSymbol());
      return;
    }
    switch (rewrite) {
      case UNCHECKED -> UncheckedArithmetic.write(builder, call);
      case OMIT_LAST -> omitLast(builder, call);
      case KEEP_ARGUMENT -> keepArgument(builder, call);
      default -> dropArguments(builder, call);
    }
  }

  /// The arguments popped, and for `remove` the receiver left as the stage's result; otherwise the
  /// other method called on it.
  private void dropArguments(CodeBuilder builder, InvokeInstruction call) {
    for (var parameter : call.typeSymbol().parameterList().reversed()) {
      Mutations.pop(builder, TypeKind.from(parameter));
    }
    if (!rewrite.equals("remove")) {
      callInstead(builder, call);
    }
  }

  /// What it was called on taken from under the argument, which goes on as the result. A static
  /// call has nothing under it. A plugin's rule can name any call: one whose argument isn't of the
  /// type it returns would hand later code the wrong type, so it's unviable.
  private static void keepArgument(CodeBuilder builder, InvokeInstruction call) {
    var type = call.typeSymbol();
    if (type.parameterCount() != 1 || !type.parameterType(0).equals(type.returnType())) {
      throw new IllegalStateException(
          "`keep argument` needs one argument of the type %s returns"
              .formatted(call.name().stringValue()));
    }
    if (call.opcode() == Opcode.INVOKESTATIC) {
      return;
    }
    if (TypeKind.from(type.returnType()).slotSize() == 2) {
      builder.dup2_x1().pop2().pop();
    } else {
      builder.swap().pop();
    }
  }

  /// The last argument popped, and the overload without it called.
  private static void omitLast(CodeBuilder builder, InvokeInstruction call) {
    var type = call.typeSymbol();
    int count = type.parameterCount();
    Mutations.pop(builder, TypeKind.from(type.parameterType(count - 1)));
    invoke(builder, call, call.name().stringValue(), type.dropParameterTypes(count - 1, count));
  }

  /// `call name(ARGS) with A, B`: the arguments pushed, and the other method called on the
  /// receiver, returning what the original did.
  private void callInstead(CodeBuilder builder, InvokeInstruction call) {
    var spec = rewrite.substring("call ".length());
    var name = spec.substring(0, spec.indexOf('('));
    var parameters =
        MethodTypeDesc.ofDescriptor(spec.substring(spec.indexOf('('), spec.indexOf(')') + 1) + "V");
    var with = spec.substring(spec.indexOf(')') + 1).strip();
    var arguments =
        with.isEmpty() ? new String[0] : with.substring("with ".length()).split(",", -1);
    for (int index = 0; index < arguments.length; index++) {
      push(builder, arguments[index].strip(), parameters.parameterType(index));
    }
    invoke(builder, call, name, parameters.changeReturnType(call.typeSymbol().returnType()));
  }

  private static void invoke(
      CodeBuilder builder, InvokeInstruction call, String name, MethodTypeDesc type) {
    builder.invoke(call.opcode(), call.owner().asSymbol(), name, type, call.isInterface());
  }

  /// One argument, as the parameter it's passed for: a static call returns that type, so the engine
  /// needn't load the library the rule is about.
  private static void push(CodeBuilder builder, String argument, ClassDesc parameter) {
    switch (argument) {
      case "Long.MAX_VALUE" -> builder.loadConstant(Long.MAX_VALUE);
      case "Integer.MAX_VALUE" -> builder.loadConstant(Integer.MAX_VALUE);
      case "0L" -> builder.loadConstant(0L);
      case "0" -> builder.loadConstant(0);
      case "true" -> builder.iconst_1();
      case "false" -> builder.iconst_0();
      case "null" -> builder.aconst_null();
      default -> {
        var path = argument.substring(0, argument.length() - 2);
        var owner = path.substring(0, path.lastIndexOf('.'));
        var method = path.substring(path.lastIndexOf('.') + 1);
        builder.invokestatic(
            ClassDesc.of(owner), method, MethodTypeDesc.of(parameter), isInterface(owner));
      }
    }
  }

  /// Whether a static call's owner is an interface, when the engine can tell; a library it doesn't
  /// have is taken as a class.
  private static boolean isInterface(String owner) {
    try {
      return Class.forName(owner, false, CallRule.class.getClassLoader()).isInterface();
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Override
  public Words words(Mutant mutant) {
    var name = calledName(mutant.original());
    var method = mutant.methodName() + "()";
    return new Words(
        fill(changed, name, mutant.replacement(), method),
        fill(template("headline", name), name, mutant.replacement(), method),
        fill(template("help", name), name, mutant.replacement(), method));
  }

  private String template(String key, String name) {
    return templates.getOrDefault(key + "." + name, templates.getOrDefault(key, ""));
  }

  private static String fill(String template, String name, String to, String method) {
    return template.replace("{name}", name).replace("{to}", to).replace("{method}", method);
  }

  /// `filter` from `INVOKEINTERFACE java/util/stream/Stream.filter(…)`.
  static String calledName(String original) {
    var call = original.substring(0, original.indexOf('('));
    return call.substring(call.lastIndexOf('.') + 1);
  }
}
