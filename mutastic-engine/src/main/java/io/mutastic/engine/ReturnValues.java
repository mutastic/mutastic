package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator.Change;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// What `mutastic/return-value` makes of a `return`: the value that kind of method most plausibly
/// gets wrong. A `boolean` returns the other one, a number `0` (or `1`, where it returns `0`), an
/// `Optional`, `String`, collection or `Stream` an empty one, a boxed number or `Boolean` as its
/// primitive does, and any other reference `null`.
///
/// What the return already gives is read from the instructions just before it, with no jump landing
/// between: a `return false;` gets `true` only, and a `return List.of();` gets no change that runs,
/// since the change would return what it already does.
final class ReturnValues {
  private static final String RETURN = "return ";

  /// The primitive a box holds, by the box's descriptor. `Character` isn't a number: it gets
  /// `null`.
  private static final Map<String, ClassDesc> BOXES =
      Map.of(
          "Ljava/lang/Boolean;", ConstantDescs.CD_boolean,
          "Ljava/lang/Integer;", ConstantDescs.CD_int,
          "Ljava/lang/Long;", ConstantDescs.CD_long,
          "Ljava/lang/Short;", ConstantDescs.CD_short,
          "Ljava/lang/Byte;", ConstantDescs.CD_byte,
          "Ljava/lang/Float;", ConstantDescs.CD_float,
          "Ljava/lang/Double;", ConstantDescs.CD_double);

  /// The first class file version whose code can call a static method on an interface, `List.of()`:
  /// Java 8's.
  private static final int INTERFACE_STATICS = 52;

  private static final Empty LIST =
      new Empty(
          "List.of()",
          "an empty list",
          ClassDesc.of("java.util.List"),
          "of",
          true,
          Set.of(
              "java/util/List.of",
              "java/util/Collections.emptyList",
              "kotlin/collections/CollectionsKt.emptyList"));

  /// The empty value of each type that has one, by its descriptor.
  private static final Map<String, Empty> EMPTIES =
      Map.of(
          "Ljava/util/Optional;",
          new Empty(
              "Optional.empty()",
              "an empty Optional",
              ClassDesc.of("java.util.Optional"),
              "empty",
              false,
              Set.of("java/util/Optional.empty")),
          "Ljava/lang/String;",
          new Empty("\"\"", "an empty string", ConstantDescs.CD_String, "", false, Set.of()),
          "Ljava/util/List;",
          LIST,
          "Ljava/util/Collection;",
          LIST,
          "Ljava/lang/Iterable;",
          LIST,
          "Ljava/util/Set;",
          new Empty(
              "Set.of()",
              "an empty set",
              ClassDesc.of("java.util.Set"),
              "of",
              true,
              Set.of(
                  "java/util/Set.of",
                  "java/util/Collections.emptySet",
                  "kotlin/collections/SetsKt.emptySet")),
          "Ljava/util/Map;",
          new Empty(
              "Map.of()",
              "an empty map",
              ClassDesc.of("java.util.Map"),
              "of",
              true,
              Set.of(
                  "java/util/Map.of",
                  "java/util/Collections.emptyMap",
                  "kotlin/collections/MapsKt.emptyMap")),
          "Ljava/util/stream/Stream;",
          new Empty(
              "Stream.empty()",
              "an empty stream",
              ClassDesc.of("java.util.stream.Stream"),
              "empty",
              true,
              Set.of("java/util/stream/Stream.empty")));

  private ReturnValues() {}

  /// An empty value: how a card writes it, how a skip says it, the static factory that makes it
  /// (for the empty string, a constant: `String` and no factory) and whether that's on an
  /// interface, and the calls with no arguments that already return one of its kind.
  private record Empty(
      String written,
      String said,
      ClassDesc owner,
      String factory,
      boolean onInterface,
      Set<String> already) {
    boolean isString() {
      return owner.equals(ConstantDescs.CD_String);
    }

    /// Whether `instruction`, just before the return, already gives this empty value.
    boolean givenBy(@Nullable Instruction instruction) {
      if (isString()) {
        return instruction instanceof ConstantInstruction constant
            && "".equals(constant.constantValue());
      }
      return instruction instanceof InvokeInstruction call
          && call.opcode() == Opcode.INVOKESTATIC
          && call.type().stringValue().startsWith("()")
          && already.contains(call.owner().asInternalName() + "." + call.name().stringValue());
    }

    void push(CodeBuilder builder) {
      if (isString()) {
        builder.ldc("");
      } else {
        builder.invokestatic(owner, factory, MethodTypeDesc.of(owner), onInterface);
      }
    }
  }

  /// The changes to the return at `at` in a method of `model` that returns `returned`, each by its
  /// label, `return false`; one that would return what the code already does is skipped as
  /// equivalent.
  static List<Change> changes(
      ClassModel model, ClassDesc returned, List<CodeElement> code, int at) {
    var before = Before.in(code, at);
    var descriptor = returned.descriptorString();
    var primitive = returned.isPrimitive() ? returned : BOXES.get(descriptor);
    if (primitive != null) {
      return primitive.equals(ConstantDescs.CD_boolean)
          ? booleans(literal(before, returned))
          : numbers(literal(before, returned), primitive);
    }
    var empty = EMPTIES.get(descriptor);
    if (empty != null && (!empty.onInterface() || model.majorVersion() >= INTERFACE_STATICS)) {
      return List.of(
          change(empty.written(), empty.givenBy(before.last()), "already returns " + empty.said()));
    }
    var last = before.last();
    boolean isNull = last != null && last.opcode() == Opcode.ACONST_NULL;
    return List.of(change("null", isNull, "already returns null"));
  }

  /// `true` and `false`, or the one it doesn't return already.
  private static List<Change> booleans(Optional<Integer> literal) {
    var changes = new ArrayList<Change>();
    if (!literal.equals(Optional.of(0))) {
      changes.add(Change.of(RETURN + "false"));
    }
    if (!literal.equals(Optional.of(1))) {
      changes.add(Change.of(RETURN + "true"));
    }
    return changes;
  }

  /// `0`, or `1` where it returns `0` already, written as the source would for the type: `0L` for a
  /// `long`, `0.0` for a `double`.
  private static List<Change> numbers(Optional<Integer> literal, ClassDesc primitive) {
    boolean one = literal.equals(Optional.of(0));
    var written =
        switch (primitive.descriptorString()) {
          case "J" -> one ? "1L" : "0L";
          case "F" -> one ? "1.0f" : "0.0f";
          case "D" -> one ? "1.0" : "0.0";
          default -> one ? "1" : "0";
        };
    return List.of(Change.of(RETURN + written));
  }

  private static Change change(String value, boolean equivalent, String why) {
    return new Change(
        RETURN + value, equivalent ? Optional.of("equivalent: " + why) : Optional.empty());
  }

  /// The `0` or `1` the return gives when the code just before it is that constant: `iconst_0`, a
  /// box made from one (`Integer.valueOf`), or `Boolean.TRUE` or `Boolean.FALSE`.
  private static Optional<Integer> literal(Before before, ClassDesc returned) {
    var last = before.last();
    if (returned.isPrimitive()) {
      return last == null ? Optional.empty() : zeroOrOne(last.opcode());
    }
    if (last instanceof FieldInstruction field
        && field.opcode() == Opcode.GETSTATIC
        && field.owner().asInternalName().equals("java/lang/Boolean")) {
      return switch (field.name().stringValue()) {
        case "TRUE" -> Optional.of(1);
        case "FALSE" -> Optional.of(0);
        default -> Optional.empty();
      };
    }
    var boxed = before.second();
    return isBoxing(last, returned) && boxed != null ? zeroOrOne(boxed.opcode()) : Optional.empty();
  }

  /// `Integer.valueOf(int)` for an `Integer`: the call that boxes what the method returns.
  private static boolean isBoxing(@Nullable Instruction instruction, ClassDesc returned) {
    return instruction instanceof InvokeInstruction call
        && call.opcode() == Opcode.INVOKESTATIC
        && call.name().equalsString("valueOf")
        && returned.equals(call.owner().asSymbol());
  }

  private static Optional<Integer> zeroOrOne(Opcode opcode) {
    return switch (opcode) {
      case ICONST_0, LCONST_0, FCONST_0, DCONST_0 -> Optional.of(0);
      case ICONST_1, LCONST_1, FCONST_1, DCONST_1 -> Optional.of(1);
      default -> Optional.empty();
    };
  }

  /// The last two instructions before a return, as far back as the nearest place a jump lands:
  /// `null` where there's none, since a jump could arrive with something else.
  private record Before(@Nullable Instruction last, @Nullable Instruction second) {
    static Before in(List<CodeElement> code, int at) {
      var found = new ArrayList<Instruction>(2);
      for (int index = at - 1; index >= 0 && found.size() < 2; index--) {
        var element = code.get(index);
        if (element instanceof LabelTarget) {
          break;
        }
        if (element instanceof Instruction instruction) {
          found.add(instruction);
        }
      }
      return new Before(
          found.isEmpty() ? null : found.get(0), found.size() < 2 ? null : found.get(1));
    }
  }

  /// The return, changed as the mutant's label says: its value popped, the new one pushed and
  /// returned.
  static void write(CodeBuilder builder, ReturnInstruction returning, Mutant mutant) {
    var returned = MethodTypeDesc.ofDescriptor(mutant.descriptor()).returnType();
    var label = mutant.replacement();
    var kind = returning.typeKind();
    if (kind.slotSize() == 2) {
      builder.pop2();
    } else {
      builder.pop();
    }
    push(builder, label.substring(RETURN.length()), returned);
    builder.return_(kind);
  }

  private static void push(CodeBuilder builder, String value, ClassDesc returned) {
    if (returned.isPrimitive()) {
      constant(builder, TypeKind.from(returned), value);
      return;
    }
    var primitive = BOXES.get(returned.descriptorString());
    if (primitive != null) {
      constant(builder, TypeKind.from(primitive), value);
      builder.invokestatic(returned, "valueOf", MethodTypeDesc.of(returned, primitive));
    } else if (value.equals("null")) {
      builder.aconst_null();
    } else {
      java.util.Objects.requireNonNull(EMPTIES.get(returned.descriptorString())).push(builder);
    }
  }

  /// `true`, `false`, `0` or `1` (`0L`, `1.0`), as the kind the method returns.
  private static void constant(CodeBuilder builder, TypeKind kind, String value) {
    boolean one = value.equals("true") || value.startsWith("1");
    switch (kind) {
      case LONG -> builder.loadConstant(one ? 1L : 0L);
      case FLOAT -> builder.loadConstant(one ? 1f : 0f);
      case DOUBLE -> builder.loadConstant(one ? 1d : 0d);
      default -> builder.loadConstant(one ? 1 : 0);
    }
  }
}
