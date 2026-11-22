package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The calls in one method whose only effect is output, `mutastic:output`'s: removing one changes
/// what a person reads, not what the code does, so no test is expected to notice.
///
/// A logger's are, every method of one of [#LOGGERS]. A print stream's are only when it's the
/// console's, `System.out` or `System.err`, read straight from there or from a local that never
/// holds anything else. A stream the code is given, as a parameter or a field, is one a test can
/// hand it and read back, as `run(args, out)` is: its calls stay counted, as a `PrintWriter`'s do.
///
/// A log-level check is output too when all it decides is whether output is written: `if
/// (log.isDebugEnabled()) { log.debug(…); }`, each statement of its body one of these calls. A body
/// that does anything else, a store, a call of the code's own, keeps the check counted.
final class OutputCalls {
  static final String REASON = "arid: only writes output";
  static final String GUARD = "arid: only decides whether output is written";

  /// Classes and interfaces each of whose methods writes a log line, makes one or checks a level:
  /// the logger APIs and their fluent builders, as a call names its receiver's declared type.
  static final Set<String> LOGGERS =
      Set.of(
          // SLF4J, and its fluent builder since 2.0: `log.atInfo().addArgument(x).log("…")`.
          "org/slf4j/Logger",
          "org/slf4j/spi/LoggingEventBuilder",
          // A logger declared as Logback's own class.
          "ch/qos/logback/classic/Logger",
          "java/util/logging/Logger",
          "java/lang/System$Logger",
          // Log4j 2, and its fluent builder: `logger.atInfo().withThrowable(e).log("…")`.
          "org/apache/logging/log4j/Logger",
          "org/apache/logging/log4j/LogBuilder",
          "org/apache/log4j/Logger",
          "org/apache/log4j/Category",
          "org/apache/commons/logging/Log",
          "org/jboss/logging/Logger",
          "org/jboss/logging/BasicLogger",
          // kotlin-logging: `logger.info { "…" }`, and `logger.atInfo { message = "…" }`.
          "mu/KLogger",
          "io/github/oshai/kotlinlogging/KLogger",
          "io/github/oshai/kotlinlogging/KLoggingEventBuilder",
          // Flogger: `logger.atInfo().withCause(e).log("…")`.
          "com/google/common/flogger/FluentLogger",
          "com/google/common/flogger/FluentLogger$Api",
          "com/google/common/flogger/GoogleLogger",
          "com/google/common/flogger/GoogleLogger$Api",
          "com/google/common/flogger/LoggingApi",
          // tinylog 2, and 1.
          "org/tinylog/Logger",
          "org/tinylog/TaggedLogger",
          "org/pmw/tinylog/Logger");

  private static final String PRINT_STREAM = "java/io/PrintStream";

  private final List<CodeElement> code;

  /// The print stream calls on the console, by where they are in the code.
  private final Set<Integer> console = new HashSet<>();

  /// Each label, and where it is in the code.
  private final Map<Label, Integer> labels = new HashMap<>();

  OutputCalls(List<CodeElement> code, MethodModel method) {
    this.code = code;
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof LabelTarget target) {
        labels.put(target.label(), at);
      }
    }
    var consoles = consoleLocals(method);
    for (int at = 0; at < code.size(); at++) {
      if (isConsole(code.get(at))
          || (code.get(at) instanceof LoadInstruction load && consoles.contains(load.slot()))) {
        TextConstants.consumer(code, at)
            .filter(taken -> taken.position() == 0)
            .filter(taken -> taken.call().opcode() != Opcode.INVOKESTATIC)
            .filter(taken -> taken.call().owner().asInternalName().equals(PRINT_STREAM))
            .ifPresent(taken -> console.add(taken.index()));
      }
    }
  }

  /// Whether the call at `code[at]` only writes output.
  boolean writes(int at) {
    return code.get(at) instanceof InvokeInstruction call
        && (LOGGERS.contains(call.owner().asInternalName()) || console.contains(at));
  }

  /// Why the mutant at `code[at]` is output, if it is: a call that only writes it, or a log-level
  /// check that only decides whether it's written.
  Optional<String> reason(int at) {
    if (writes(at)) {
      return Optional.of(REASON);
    }
    return code.get(at) instanceof BranchInstruction branch && guardsOnlyOutput(branch, at)
        ? Optional.of(GUARD)
        : Optional.empty();
  }

  /// `System.out` or `System.err`, read.
  private static boolean isConsole(CodeElement element) {
    return element instanceof FieldInstruction field
        && field.opcode() == Opcode.GETSTATIC
        && field.owner().asInternalName().equals("java/lang/System")
        && (field.name().equalsString("out") || field.name().equalsString("err"));
  }

  /// The locals every store to which is of `System.out` or `System.err`, as `var out = System.out`
  /// leaves: never a parameter's slot, whose value comes from the caller.
  private Set<Integer> consoleLocals(MethodModel method) {
    var stored = new HashSet<Integer>();
    var other = new HashSet<Integer>();
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof StoreInstruction store) {
        boolean ofConsole = at > 0 && isConsole(code.get(at - 1));
        (ofConsole ? stored : other).add(store.slot());
      }
    }
    stored.removeAll(other);
    stored.removeIf(slot -> slot < parameterSlots(method));
    return stored;
  }

  /// How many slots `this` and the parameters take.
  private static int parameterSlots(MethodModel method) {
    int slots = method.flags().has(AccessFlag.STATIC) ? 0 : 1;
    for (var type : method.methodTypeSymbol().parameterList()) {
      slots += type.equals(ConstantDescs.CD_long) || type.equals(ConstantDescs.CD_double) ? 2 : 1;
    }
    return slots;
  }

  /// `if (log.isDebugEnabled()) { … }`: a jump on what a logger's check just gave, past a body each
  /// statement of which only writes output, which nothing else jumps into.
  private boolean guardsOnlyOutput(BranchInstruction branch, int at) {
    if ((branch.opcode() != Opcode.IFEQ && branch.opcode() != Opcode.IFNE)
        || at == 0
        || !(code.get(at - 1) instanceof InvokeInstruction check)
        || !check.typeSymbol().returnType().equals(ConstantDescs.CD_boolean)
        || !writes(at - 1)) {
      return false;
    }
    var end = labels.get(branch.target());
    return end != null && end > at + 1 && enteredOnlyFromTop(at, end) && onlyOutput(at + 1, end);
  }

  /// No jump, switch or exception handler from outside `(from, to)` lands inside it, and no handler
  /// covers any of it.
  private boolean enteredOnlyFromTop(int from, int to) {
    for (int at = 0; at < code.size(); at++) {
      boolean outside = at <= from || at >= to;
      var element = code.get(at);
      boolean landsInside =
          switch (element) {
            case BranchInstruction jump -> outside && inside(jump.target(), from, to);
            case TableSwitchInstruction choice ->
                inside(choice.defaultTarget(), from, to)
                    || choice.cases().stream().anyMatch(each -> inside(each.target(), from, to));
            case LookupSwitchInstruction choice ->
                inside(choice.defaultTarget(), from, to)
                    || choice.cases().stream().anyMatch(each -> inside(each.target(), from, to));
            case ExceptionCatch handler ->
                inside(handler.handler(), from, to) || overlaps(handler, from, to);
            default -> false;
          };
      if (landsInside) {
        return false;
      }
    }
    return true;
  }

  private boolean inside(Label label, int from, int to) {
    int at = labels.getOrDefault(label, -1);
    return at > from && at < to;
  }

  private boolean overlaps(ExceptionCatch handler, int from, int to) {
    int start = labels.getOrDefault(handler.tryStart(), -1);
    int end = labels.getOrDefault(handler.tryEnd(), -1);
    return start < to && end > from;
  }

  /// Whether the code from `from` up to `to` is statements each of which ends in a call that only
  /// writes output, its value thrown away if it gives one, and does nothing else: what's on the
  /// stack goes back to empty only after one of those. Jumps inside it, a ternary's in a call's
  /// values, go forward and stay inside it.
  private boolean onlyOutput(int from, int to) {
    var ahead = new HashMap<Label, Integer>();
    int depth = 0;
    boolean reached = true;
    boolean any = false;
    for (int at = from; at < to; at++) {
      var element = code.get(at);
      if (element instanceof LabelTarget target) {
        var carried = ahead.remove(target.label());
        if (carried != null && reached && carried != depth) {
          return false;
        }
        if (carried != null) {
          depth = carried;
          reached = true;
        }
        continue;
      }
      if (!(element instanceof Instruction instruction)) {
        continue;
      }
      if (!reached) {
        return false;
      }
      if (instruction instanceof BranchInstruction jump) {
        int target = labels.getOrDefault(jump.target(), -1);
        int tested = tested(jump.opcode());
        if (target <= at || target >= to || tested > depth) {
          return false;
        }
        depth -= tested;
        if (ahead.merge(jump.target(), depth, (one, other) -> one.equals(other) ? one : -1) < 0) {
          return false;
        }
        reached = tested > 0;
        continue;
      }
      var effect = TextConstants.Follow.effect(instruction);
      if (effect.equals(TextConstants.Follow.UNKNOWN) || effect.takes() > depth) {
        return false;
      }
      depth += effect.leaves() - effect.takes();
      if (depth == 0) {
        if (!endsAnOutputStatement(instruction, at)) {
          return false;
        }
        any = true;
      }
    }
    return any && reached && depth == 0 && ahead.isEmpty();
  }

  /// An output call that gives nothing, or the `pop` of what one gave.
  private boolean endsAnOutputStatement(Instruction instruction, int at) {
    if (instruction.opcode() == Opcode.POP) {
      return at > 0 && writes(at - 1);
    }
    return writes(at)
        && ((InvokeInstruction) instruction)
            .typeSymbol()
            .returnType()
            .equals(ConstantDescs.CD_void);
  }

  /// What a jump tests, in values: none for a `goto`.
  private static int tested(Opcode opcode) {
    return switch (opcode) {
      case GOTO, GOTO_W -> 0;
      case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
          2;
      default -> 1;
    };
  }
}
