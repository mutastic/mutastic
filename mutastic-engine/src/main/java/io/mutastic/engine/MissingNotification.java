package io.mutastic.engine;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// The ghost of a missing property change: a write to a field, with the call that its peers make to
/// tell listeners inserted after it ([Ghosts] finds where). Experimental, and never in the score.
/// Only `--experimental=ghosts` makes these, and never as an operator of a set: no `--operators`
/// chooses it.
final class MissingNotification implements Mutator {
  static final String ID = "ghost/missing-notify";

  private static final String LABEL_START = "firePropertyChange(\"";
  private static final String LABEL_END = "\")";
  private static final ClassDesc OBJECT = ConstantDescs.CD_Object;

  /// How a peer makes its call: on a `PropertyChangeSupport` field read from `this`, or on `this`
  /// itself, through a method of the class or a superclass.
  ///
  /// @param support the field holding the support object, read from `this`; empty to call on `this`
  record Sink(
      Optional<FieldRef> support,
      Opcode invoke,
      ClassDesc owner,
      MethodTypeDesc type,
      boolean onInterface) {
    /// A field to read, as the peer's `getfield` names it.
    record FieldRef(ClassDesc owner, String name, ClassDesc type) {}

    static Sink through(FieldInstruction field, InvokeInstruction call) {
      return new Sink(
          Optional.of(
              new FieldRef(
                  field.owner().asSymbol(), field.name().stringValue(), field.typeSymbol())),
          call.opcode(),
          call.owner().asSymbol(),
          call.typeSymbol(),
          call.isInterface());
    }

    static Sink on(InvokeInstruction call) {
      return new Sink(
          Optional.empty(),
          call.opcode(),
          call.owner().asSymbol(),
          call.typeSymbol(),
          call.isInterface());
    }
  }

  /// The call to make for each class's field, by `owner.field`, as [Ghosts] found it.
  private final Map<String, Sink> sinks = new ConcurrentHashMap<>();

  static String label(String property) {
    return LABEL_START + property + LABEL_END;
  }

  /// Whether the peer's call takes the field's value as it is, or boxed as an `Object`.
  static boolean canCopy(Sink sink, FieldInstruction field) {
    var parameters = sink.type().parameterList();
    var value = field.typeSymbol();
    return parameters.get(1).equals(parameters.get(2))
        && (parameters.get(1).equals(value) || parameters.get(1).equals(OBJECT));
  }

  void remember(String owner, String field, Sink sink) {
    sinks.put(owner + "." + field, sink);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String explanation() {
    return "Experimental. A write to a bean's field that doesn't fire the property change its peers"
        + " do gets the peers' call inserted after it. If no test notices, nothing says whether"
        + " listeners should hear of that write.";
  }

  @Override
  public boolean onCalls() {
    return false;
  }

  /// Found by [Ghosts], not by walking each instruction.
  @Override
  public List<Change> changes(Site site) {
    return List.of();
  }

  /// The write, then the peers' call: the old value is read before the write, and the new value is
  /// the one written, each kept in a local of its own.
  @Override
  public void write(CodeBuilder builder, Instruction instruction, String label) {
    var field = (FieldInstruction) instruction;
    var sink = sinks.get(field.owner().asInternalName() + "." + field.name().stringValue());
    if (sink == null) {
      throw new IllegalStateException("no call found to copy for " + field);
    }
    var kind = TypeKind.from(field.typeSymbol());
    int written = builder.allocateLocal(kind);
    int old = builder.allocateLocal(kind);
    builder.storeLocal(kind, written); // the receiver is left, to write to and then to call on
    builder.dup().getfield(field.field()).storeLocal(kind, old);
    builder.dup().loadLocal(kind, written).putfield(field.field());
    if (sink.support().isPresent()) {
      var support = sink.support().get();
      builder.getfield(support.owner(), support.name(), support.type());
    }
    builder.ldc(property(label));
    value(builder, kind, old, sink);
    value(builder, kind, written, sink);
    builder.invoke(sink.invoke(), sink.owner(), BeanWrites.FIRE, sink.type(), sink.onInterface());
  }

  /// A value as the call takes it: boxed, for an `Object`.
  private static void value(CodeBuilder builder, TypeKind kind, int slot, Sink sink) {
    builder.loadLocal(kind, slot);
    if (kind != TypeKind.REFERENCE && sink.type().parameterList().get(1).equals(OBJECT)) {
      var box = Boxes.of(kind);
      builder.invokestatic(box, "valueOf", MethodTypeDesc.of(box, kind.upperBound()));
    }
  }

  static String property(String label) {
    return label.substring(LABEL_START.length(), label.length() - LABEL_END.length());
  }

  @Override
  public Words words(Mutant mutant) {
    var property = property(mutant.replacement());
    return new Words(
        "firePropertyChange(\"%s\", old, new) added after the write".formatted(property),
        "no test notices whether `%s()` tells listeners `%s` changed"
            .formatted(mutant.methodName(), property),
        "If listeners should hear of it, fire the change there; if not, a test that listens and"
            + " expects nothing says so.");
  }

  /// The box for each primitive kind.
  private static final class Boxes {
    private Boxes() {}

    static ClassDesc of(TypeKind kind) {
      return switch (kind) {
        case BOOLEAN -> ConstantDescs.CD_Boolean;
        case BYTE -> ConstantDescs.CD_Byte;
        case CHAR -> ConstantDescs.CD_Character;
        case SHORT -> ConstantDescs.CD_Short;
        case INT -> ConstantDescs.CD_Integer;
        case LONG -> ConstantDescs.CD_Long;
        case FLOAT -> ConstantDescs.CD_Float;
        case DOUBLE -> ConstantDescs.CD_Double;
        default -> throw new IllegalArgumentException("not a primitive: " + kind);
      };
    }
  }
}
