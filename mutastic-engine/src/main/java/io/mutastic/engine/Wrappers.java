package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ConstantDescs;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The calls [io.mutastic.api.BuiltIn#OMIT_CALL] leaves out: those whose result can only be the
/// same kind of thing as what went in, so the code still means something without them.
///
/// An allowlist, as the type alone doesn't prove it: `Stream.map` returns a `Stream` and
/// `Function.apply` takes and returns an `Object`, and leaving either out would give later code the
/// wrong type of value, a `ClassCastException` that looks like a catch. So: `String`'s and the
/// string builders' own methods, `Math.abs`, and the project's own methods, whose bytecode shows
/// they aren't generic. A library's aren't taken: nothing here can read them.
final class Wrappers {
  static final String KEEP_RECEIVER = "keep receiver";
  static final String KEEP_ARGUMENT = "keep argument";

  private static final Set<String> BUILDING =
      Set.of("append", "insert", "reverse", "delete", "deleteCharAt", "replace");

  private static final Map<String, Set<String>> RETURN_THEIR_RECEIVER =
      Map.of(
          "java/lang/String",
          Set.of(
              "trim",
              "strip",
              "stripLeading",
              "stripTrailing",
              "stripIndent",
              "toLowerCase",
              "toUpperCase",
              "replace",
              "replaceAll",
              "replaceFirst",
              "substring",
              "concat",
              "repeat",
              "intern"),
          "java/lang/StringBuilder",
          BUILDING,
          "java/lang/StringBuffer",
          BUILDING);

  private static final Map<String, Set<String>> RETURN_THEIR_ARGUMENT =
      Map.of("java/lang/Math", Set.of("abs"));

  private final Map<String, ClassModel> module;

  Wrappers(Map<String, ClassModel> module) {
    this.module = Map.copyOf(module);
  }

  /// What's kept if the call in `model` is left out, if it can be.
  Optional<String> change(ClassModel model, InvokeInstruction call) {
    if (call.name().equalsString("<init>") || returnsAnObject(call)) {
      return Optional.empty();
    }
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    if (returnsItsReceiver(call)
        && (RETURN_THEIR_RECEIVER.getOrDefault(owner, Set.of()).contains(name)
            || isOwn(model, call))) {
      return Optional.of(KEEP_RECEIVER);
    }
    return returnsItsArgument(call)
            && (RETURN_THEIR_ARGUMENT.getOrDefault(owner, Set.of()).contains(name)
                || isOwn(model, call))
        ? Optional.of(KEEP_ARGUMENT)
        : Optional.empty();
  }

  private static boolean returnsAnObject(InvokeInstruction call) {
    return call.typeSymbol().returnType().equals(ConstantDescs.CD_Object);
  }

  private static boolean returnsItsReceiver(InvokeInstruction call) {
    return call.opcode() != Opcode.INVOKESTATIC
        && call.typeSymbol().returnType().equals(call.owner().asSymbol());
  }

  /// One argument, of the type it returns: `escape(text)`, not `first(a, b)`, which has two to
  /// keep.
  private static boolean returnsItsArgument(InvokeInstruction call) {
    var type = call.typeSymbol();
    return type.parameterCount() == 1 && type.parameterType(0).equals(type.returnType());
  }

  /// Whether the method called is in the project's code and has no type parameters of its own, the
  /// way a method changes what its result holds: `<U> Box<U> map(Function<T, U>)`.
  private boolean isOwn(ClassModel model, InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    var declaring =
        model.thisClass().asInternalName().equals(owner)
            ? Optional.of(model)
            : Optional.ofNullable(module.get(owner));
    return declaring
        .flatMap(found -> declared(found, call))
        .filter(Wrappers::notGeneric)
        .isPresent();
  }

  private static Optional<MethodModel> declared(ClassModel owner, InvokeInstruction call) {
    return owner.methods().stream()
        .filter(method -> method.methodName().equals(call.name()))
        .filter(method -> method.methodType().equals(call.type()))
        .findFirst();
  }

  private static boolean notGeneric(MethodModel method) {
    return method
        .findAttribute(Attributes.signature())
        .filter(signature -> signature.signature().stringValue().startsWith("<"))
        .isEmpty();
  }

  /// The call left out: a kept receiver has its arguments popped from above it; a kept argument has
  /// the receiver, if there's one, popped from under it.
  static void omit(CodeBuilder builder, InvokeInstruction call, String change) {
    var type = call.typeSymbol();
    if (change.equals(KEEP_RECEIVER)) {
      for (var parameter : type.parameterList().reversed()) {
        Mutations.pop(builder, TypeKind.from(parameter));
      }
    } else if (call.opcode() != Opcode.INVOKESTATIC) {
      if (TypeKind.from(type.returnType()).slotSize() == 2) {
        builder.dup2_x1().pop2().pop();
      } else {
        builder.swap().pop();
      }
    }
  }
}
