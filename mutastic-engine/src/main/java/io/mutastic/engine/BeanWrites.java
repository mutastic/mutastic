package io.mutastic.engine;

import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The writes to a bean's own fields in a method, and the property changes fired after each: what
/// [Ghosts] counts. A property change is a call to `PropertyChangeSupport`'s `firePropertyChange`,
/// or to a method of the class or a superclass in the module that makes one; its name is the
/// constant it's given, when it's given one.
final class BeanWrites {
  private static final Set<String> SUPPORTS =
      Set.of("java/beans/PropertyChangeSupport", "javax/swing/event/SwingPropertyChangeSupport");

  static final String FIRE = "firePropertyChange";

  /// One write to a field of the class, and what fired after it in the same method.
  ///
  /// @param index the write's place among its method's instructions: a ghost's site
  /// @param fired the property change after it, if there was one
  /// @param credited whether a method of the class that fires was called after it
  record Write(
      MethodModel method,
      int index,
      int line,
      FieldInstruction field,
      Optional<Fire> fired,
      boolean credited) {
    boolean notified() {
      return fired.isPresent() || credited;
    }

    String fieldName() {
      return field.name().stringValue();
    }

    boolean inSetter() {
      var type = method.methodTypeSymbol();
      return method.methodName().stringValue().startsWith("set")
          && type.parameterCount() == 1
          && type.returnType().descriptorString().equals("V");
    }
  }

  /// A property change fired: the name it gives, and how to make the same call when it can be.
  record Fire(Optional<String> property, Optional<MissingNotification.Sink> sink) {}

  /// A method's instructions, the line each is on, and the places a jump lands.
  private record Code(List<Instruction> instructions, List<Integer> lines, Set<Integer> targets) {
    Instruction at(int index) {
      return instructions.get(index);
    }

    Optional<Integer> producer(int at, int below) {
      return StackProducers.producer(instructions, at, below, targets);
    }
  }

  private final Map<String, ClassModel> module;

  BeanWrites(Map<String, ClassModel> module) {
    this.module = module;
  }

  /// Every write to one of the class's own fields in `method`, with what fired after it; none in a
  /// method that fires a change with no name, as everything changed at once.
  List<Write> in(ClassModel model, MethodModel method) {
    var code = code(method);
    var fires = new LinkedHashMap<Integer, Fire>();
    var credits = new HashSet<Integer>();
    for (int at = 0; at < code.instructions().size(); at++) {
      if (code.at(at) instanceof InvokeInstruction call) {
        var fire = fireAt(model, code, at, call);
        if (fire.isPresent() && firesForEverything(code, at, call)) {
          return List.of();
        }
        int place = at;
        fire.ifPresent(found -> fires.put(place, found));
        if (fire.isEmpty() && callsAFiringMethod(model, call)) {
          credits.add(at);
        }
      }
    }
    return writes(model, method, code, new Notices(fires, credits));
  }

  /// Where a method fires a property change, and where it calls a method of the class that does.
  private record Notices(Map<Integer, Fire> fires, Set<Integer> credits) {}

  private static List<Write> writes(
      ClassModel model, MethodModel method, Code code, Notices notices) {
    var fires = notices.fires();
    var credits = notices.credits();
    var writes = new ArrayList<Write>();
    for (int at = 0; at < code.instructions().size(); at++) {
      if (code.at(at) instanceof FieldInstruction field && isOwnWrite(model, field)) {
        int from = at;
        var fired =
            fires.entrySet().stream()
                .filter(entry -> entry.getKey() > from)
                .map(Map.Entry::getValue)
                .filter(fire -> namesThisOrNoOtherField(model, fire, field))
                .findFirst();
        boolean credited = credits.stream().anyMatch(call -> call > from);
        writes.add(new Write(method, at, code.lines().get(at), field, fired, credited));
      }
    }
    return writes;
  }

  private static boolean isOwnWrite(ClassModel model, FieldInstruction field) {
    return field.opcode() == Opcode.PUTFIELD
        && field.owner().asInternalName().equals(model.thisClass().asInternalName())
        && model.fields().stream()
            .anyMatch(
                each ->
                    each.fieldName().equals(field.name())
                        && each.fieldType().equals(field.type())
                        && !each.flags().has(AccessFlag.STATIC));
  }

  private static Code code(MethodModel method) {
    var elements = method.code().orElseThrow().elementList();
    var instructions = new ArrayList<Instruction>();
    var lines = new ArrayList<Integer>();
    var labels = jumpTargets(elements);
    var targets = new HashSet<Integer>();
    int line = -1;
    for (CodeElement element : elements) {
      switch (element) {
        case LineNumber number -> line = number.line();
        case LabelTarget target when labels.contains(target.label()) ->
            targets.add(instructions.size());
        case Instruction instruction -> {
          instructions.add(instruction);
          lines.add(line);
        }
        default -> {}
      }
    }
    return new Code(instructions, lines, targets);
  }

  private static Set<Label> jumpTargets(List<CodeElement> elements) {
    var targets = new HashSet<Label>();
    for (CodeElement element : elements) {
      switch (element) {
        case BranchInstruction branch -> targets.add(branch.target());
        case TableSwitchInstruction table -> {
          targets.add(table.defaultTarget());
          table.cases().forEach(each -> targets.add(each.target()));
        }
        case LookupSwitchInstruction lookup -> {
          targets.add(lookup.defaultTarget());
          lookup.cases().forEach(each -> targets.add(each.target()));
        }
        case ExceptionCatch handler -> targets.add(handler.handler());
        default -> {}
      }
    }
    return targets;
  }

  /// A fired change counts for a write unless it names another of the class's fields: a backing
  /// field may be named apart from its property.
  private static boolean namesThisOrNoOtherField(
      ClassModel model, Fire fire, FieldInstruction field) {
    return fire.property()
        .map(
            name ->
                name.equals(field.name().stringValue())
                    || model.fields().stream()
                        .noneMatch(each -> each.fieldName().equalsString(name)))
        .orElse(true);
  }

  /// A property change fired at `code[at]`, and how to make it again, if it's one.
  private Optional<Fire> fireAt(ClassModel model, Code code, int at, InvokeInstruction call) {
    if (!call.name().equalsString(FIRE) || !takesANameAndTwoValues(call)) {
      return Optional.empty();
    }
    int arguments = call.typeSymbol().parameterCount();
    var property = nameAt(code, code.producer(at, arguments));
    var receiver = code.producer(at, arguments + 1);
    if (SUPPORTS.contains(call.owner().asInternalName())) {
      var support =
          receiver
              .filter(place -> place > 0 && isThis(code.at(place - 1)))
              .map(code::at)
              .filter(FieldInstruction.class::isInstance)
              .map(FieldInstruction.class::cast)
              .filter(field -> field.opcode() == Opcode.GETFIELD);
      return Optional.of(
          new Fire(property, support.map(field -> MissingNotification.Sink.through(field, call))));
    }
    if (inHierarchy(model, call.owner().asInternalName()) && firesInModule(call)) {
      var self = receiver.map(code::at).filter(BeanWrites::isThis);
      return Optional.of(
          new Fire(property, self.map(ignored -> MissingNotification.Sink.on(call))));
    }
    return Optional.empty();
  }

  /// `firePropertyChange(null, …)`: everything changed. Only when the name is known to be null.
  private static boolean firesForEverything(Code code, int at, InvokeInstruction call) {
    return code.producer(at, call.typeSymbol().parameterCount())
        .map(code::at)
        .filter(producer -> producer.opcode() == Opcode.ACONST_NULL)
        .isPresent();
  }

  private static Optional<String> nameAt(Code code, Optional<Integer> producer) {
    return producer
        .map(code::at)
        .filter(ConstantInstruction.class::isInstance)
        .map(instruction -> ((ConstantInstruction) instruction).constantValue())
        .filter(String.class::isInstance)
        .map(String.class::cast);
  }

  private static boolean isThis(Instruction instruction) {
    return instruction instanceof LoadInstruction load && load.opcode() == Opcode.ALOAD_0;
  }

  private static boolean takesANameAndTwoValues(InvokeInstruction call) {
    var parameters = call.typeSymbol().parameterList();
    return parameters.size() == 3
        && parameters.getFirst().descriptorString().equals("Ljava/lang/String;");
  }

  /// Whether `owner` is the class or one of its superclasses in the module.
  private boolean inHierarchy(ClassModel model, String owner) {
    Optional<ClassModel> at = Optional.of(model);
    while (at.isPresent()) {
      if (at.get().thisClass().asInternalName().equals(owner)) {
        return true;
      }
      at = superclassOf(at.get());
    }
    return false;
  }

  private Optional<ClassModel> superclassOf(ClassModel model) {
    return model
        .superclass()
        .flatMap(superclass -> Optional.ofNullable(module.get(superclass.asInternalName())));
  }

  /// Whether the method called, as the module declares it in the class or a superclass, calls
  /// `PropertyChangeSupport`'s `firePropertyChange`.
  private boolean firesInModule(InvokeInstruction call) {
    var at = Optional.ofNullable(module.get(call.owner().asInternalName()));
    while (at.isPresent()) {
      var found =
          at.get().methods().stream()
              .filter(
                  method ->
                      method.methodName().equals(call.name())
                          && method.methodType().equals(call.type()))
              .findFirst();
      if (found.isPresent()) {
        return callsSupport(found.get());
      }
      at = superclassOf(at.get());
    }
    return false;
  }

  private static boolean callsSupport(MethodModel method) {
    return method.code().stream()
        .flatMap(code -> code.elementStream())
        .anyMatch(
            element ->
                element instanceof InvokeInstruction call
                    && call.name().equalsString(FIRE)
                    && SUPPORTS.contains(call.owner().asInternalName()));
  }

  /// A call to a method of the class itself that fires a property change: a helper such as
  /// `changed("status", old, now)`.
  private boolean callsAFiringMethod(ClassModel model, InvokeInstruction call) {
    if (!call.owner().asInternalName().equals(model.thisClass().asInternalName())) {
      return false;
    }
    return model.methods().stream()
        .filter(
            method ->
                method.methodName().equals(call.name()) && method.methodType().equals(call.type()))
        .findFirst()
        .map(method -> callsSupport(method) || callsInheritedFire(model, method))
        .orElse(false);
  }

  private boolean callsInheritedFire(ClassModel model, MethodModel method) {
    return method.code().stream()
        .flatMap(code -> code.elementStream())
        .anyMatch(
            element ->
                element instanceof InvokeInstruction call
                    && call.name().equalsString(FIRE)
                    && inHierarchy(model, call.owner().asInternalName())
                    && firesInModule(call));
  }
}
