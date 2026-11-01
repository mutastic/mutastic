package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Skip;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.AccessFlag;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// `toString()` is a debug representation, and `hashCode()` may return any value consistent with
/// `equals()`: tests of either are rarely worth writing, so both are arid, with the lambdas they
/// define and the helpers only they call. A helper is one anyone outside can't call (private or
/// package-private) whose every caller in the module is already arid; a method calling itself
/// doesn't count. `@DoMutate` keeps one that is a contract, such as a `toString()` customers see.
final class DebugRepresentations implements Filter {
  /// The methods that start it, by name and descriptor, and why each is arid.
  private static final Map<String, String> ROOTS =
      Map.of(
          "toString()Ljava/lang/String;", "`toString()` is a debug representation",
          "hashCode()I", "`hashCode()` may return any value consistent with `equals()`");

  /// A method and the class it's in.
  private record Method(ClassModel owner, MethodModel model) {}

  /// Each arid method, `owner#name(descriptor)`, and the root it serves: `toString`.
  private final Map<String, String> arid = new HashMap<>();

  @Override
  public String id() {
    return "mutastic:debug-representations";
  }

  @Override
  public void prepare(List<ClassModel> module) {
    var methods = new HashMap<String, Method>();
    var callers = new HashMap<String, Set<String>>();
    for (ClassModel owner : module) {
      for (MethodModel method : owner.methods()) {
        var key = key(owner, method);
        methods.put(key, new Method(owner, method));
        callersOf(key, method, callers);
        if (isRoot(owner, method)) {
          arid.put(key, method.methodName().stringValue());
        }
      }
    }
    spread(methods, callers);
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    var root = arid.get(key(owner, method));
    if (root == null) {
      return Optional.empty();
    }
    var name = method.methodName().stringValue();
    var reason = ROOTS.get(name + method.methodType().stringValue());
    if (reason == null) {
      reason =
          name.startsWith("lambda$")
              ? "a lambda in `%s()`".formatted(root)
              : "`%s()` only serves `%s()`".formatted(name, root);
    }
    return Optional.of(new Skip(Skip.Kind.ARID, reason));
  }

  private static boolean isRoot(ClassModel owner, MethodModel method) {
    return ROOTS.containsKey(method.methodName().stringValue() + method.methodType().stringValue())
        && !method.flags().has(AccessFlag.STATIC)
        && !Annotations.has(owner, method, "DoMutate");
  }

  /// Marks helpers arid until no more are: each pass may make another's every caller arid.
  private void spread(Map<String, Method> methods, Map<String, Set<String>> callers) {
    boolean grew = !arid.isEmpty();
    while (grew) {
      grew = false;
      for (var entry : methods.entrySet()) {
        var key = entry.getKey();
        var method = entry.getValue();
        var from = callers.getOrDefault(key, Set.of());
        if (!arid.containsKey(key)
            && isHelper(method.model())
            && !Annotations.has(method.owner(), method.model(), "DoMutate")
            && !from.isEmpty()
            && arid.keySet().containsAll(from)) {
          arid.put(key, arid.getOrDefault(from.iterator().next(), "toString"));
          grew = true;
        }
      }
    }
  }

  /// A lambda body, or a method only its package can call.
  private static boolean isHelper(MethodModel method) {
    var flags = method.flags();
    return method.methodName().stringValue().startsWith("lambda$")
        || (!flags.has(AccessFlag.PUBLIC) && !flags.has(AccessFlag.PROTECTED));
  }

  /// Records `key` as a caller of each method it calls or refers to, other than itself.
  private static void callersOf(String key, MethodModel method, Map<String, Set<String>> callers) {
    method
        .code()
        .ifPresent(
            code ->
                code.elementStream()
                    .flatMap(DebugRepresentations::calleesIn)
                    .filter(callee -> !callee.equals(key))
                    .forEach(
                        callee ->
                            callers.computeIfAbsent(callee, ignored -> new HashSet<>()).add(key)));
  }

  /// What an instruction calls or refers to, as `owner#name(descriptor)`: plain calls, and the
  /// method handles in `invokedynamic` (lambda bodies, `this::helper`).
  private static Stream<String> calleesIn(CodeElement element) {
    return switch (element) {
      case InvokeInstruction call ->
          Stream.of(
              call.owner().asInternalName()
                  + "#"
                  + call.name().stringValue()
                  + call.type().stringValue());
      case InvokeDynamicInstruction dynamic ->
          dynamic.bootstrapArgs().stream()
              .filter(DirectMethodHandleDesc.class::isInstance)
              .map(DirectMethodHandleDesc.class::cast)
              .map(
                  handle -> {
                    var owner = handle.owner().descriptorString();
                    return owner.substring(1, owner.length() - 1)
                        + "#"
                        + handle.methodName()
                        + handle.lookupDescriptor();
                  });
      default -> Stream.empty();
    };
  }

  private static String key(ClassModel owner, MethodModel method) {
    return owner.thisClass().asInternalName()
        + "#"
        + method.methodName().stringValue()
        + method.methodType().stringValue();
  }
}
