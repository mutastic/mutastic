package io.mutastic.engine;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Filter;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import io.mutastic.api.Operator;
import io.mutastic.api.Policy;
import io.mutastic.api.Rule;
import io.mutastic.api.Skip;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Finds the mutants in a class and applies one at a time, with only `java.lang.classfile`.
final class Mutations {
  private static final Set<Opcode> DEFAULT_CONSTANTS =
      Set.of(
          Opcode.ICONST_0, Opcode.LCONST_0, Opcode.FCONST_0, Opcode.DCONST_0, Opcode.ACONST_NULL);

  /// Calls whose only effect is output: removing one changes what a person reads, not what the code
  /// does, so no test is expected to notice.
  private static final Set<String> OUTPUT_OWNERS =
      Set.of(
          "org/slf4j/Logger",
          "java/util/logging/Logger",
          "java/lang/System$Logger",
          "org/apache/logging/log4j/Logger",
          "org/apache/commons/logging/Log",
          "java/io/PrintStream");

  private final ClassFile context;
  private final Policy policy;

  /// The operators this run uses, as the policy chooses.
  private final List<Operator> operators;

  private final AnnotatedSkips annotated = new AnnotatedSkips();

  /// The filters that judge code arid or the like, built in first, then the project's own; the
  /// first with a reason decides.
  private final List<Filter> judges;

  Mutations(ClassFile context) {
    this(context, Policy.NONE);
  }

  Mutations(ClassFile context, Policy policy) {
    this.context = context;
    this.policy = policy;
    this.operators = OperatorSet.active(policy);
    var all =
        new ArrayList<Filter>(
            List.of(new TrivialAccessors(), new DebugRepresentations(this::kept)));
    all.addAll(policy.filters());
    this.judges = List.copyOf(all);
  }

  List<Mutant> find(ClassModel model) {
    var mutants = new ArrayList<Mutant>();
    for (MethodModel method : model.methods()) {
      if (mutable(model, method)) {
        method
            .code()
            .ifPresent(
                code -> {
                  var found =
                      new MethodScan(
                              model, method, code.elementList(), filtered(model, method), operators)
                          .mutants();
                  mutants.addAll(viable(model, mergeFinallyCopies(found, code)));
                });
      }
    }
    return mutants;
  }

  /// The mutants, with any of a plugin's or rules file's that wouldn't load skipped as unviable:
  /// mutastic's own operators are built to verify, and a failure of theirs stops the run, but a
  /// rule can't know every call it will meet.
  private List<Mutant> viable(ClassModel model, List<Mutant> found) {
    var checked = new ArrayList<Mutant>(found.size());
    for (Mutant mutant : found) {
      var unviable =
          mutant.operator() instanceof Mutator && mutant.skipped().isEmpty()
              ? whyUnviable(model, mutant)
              : Optional.<String>empty();
      checked.add(unviable.map(why -> mutant.skippedFor("unviable: " + why)).orElse(mutant));
    }
    return checked;
  }

  /// Why the mutated class wouldn't load, if it wouldn't: its stack maps can't be built, or it
  /// doesn't verify.
  private Optional<String> whyUnviable(ClassModel model, Mutant mutant) {
    try {
      var errors = verify(apply(model, mutant));
      return errors.isEmpty()
          ? Optional.empty()
          : Optional.of(firstLine(errors.getFirst().getMessage()));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return Optional.of(firstLine(e.getMessage()));
    }
  }

  private static String firstLine(@Nullable String message) {
    return String.valueOf(message).lines().findFirst().orElse("doesn't verify");
  }

  /// Lets the filters see the whole module, before any of its mutants are found.
  void prepare(java.util.Collection<ClassModel> module) {
    var classes = List.copyOf(module);
    judges.forEach(filter -> filter.prepare(classes));
  }

  /// Why the method's mutants shouldn't run, if they shouldn't. Most specific first: an annotation
  /// on the code, then a rule in `mutastic.yaml`, then the filters. `@DoMutate` or a `mutate:` rule
  /// keeps code a filter would call arid, never code that is generated or equivalent.
  private Optional<String> filtered(ClassModel model, MethodModel method) {
    var decided = annotated.skip(model, method).or(() -> skippedByRule(model, method));
    if (decided.isPresent()) {
      return decided.map(Skip::toString);
    }
    boolean kept = kept(model, method);
    return judges.stream()
        .map(filter -> filter.skip(model, method))
        .flatMap(Optional::stream)
        .filter(skip -> !(kept && skip.kind() == Skip.Kind.ARID))
        .findFirst()
        .map(Skip::toString);
  }

  /// A `skip:` rule's, unless the code itself says `@DoMutate`.
  private Optional<Skip> skippedByRule(ClassModel model, MethodModel method) {
    if (Annotations.has(model, method, "DoMutate")) {
      return Optional.empty();
    }
    return rule(model, method, false)
        .map(
            rule ->
                new Skip(
                    Skip.Kind.USER, "%s (rule `%s`)".formatted(rule.reason(), rule.written())));
  }

  /// Whether the project keeps the method's mutants even if a filter calls the code arid.
  private boolean kept(ClassModel model, MethodModel method) {
    return Annotations.has(model, method, "DoMutate") || rule(model, method, true).isPresent();
  }

  private Optional<Rule> rule(ClassModel model, MethodModel method, boolean mutate) {
    var className = model.thisClass().asInternalName().replace('/', '.');
    var name = method.methodName().stringValue();
    return policy.rules().stream()
        .filter(rule -> rule.mutate() == mutate && rule.covers(className, name))
        .findFirst();
  }

  /// Walks one method's code in order, counting its sites and finding the mutants at each. A skip
  /// the code itself shows (generated, equivalent) comes before a filter's.
  private static final class MethodScan {
    private final ClassModel model;
    private final MethodModel method;
    private final List<CodeElement> code;
    private final Set<Instruction> tryWithResources;
    private final Operands operands;
    private final Optional<String> filtered;
    private final List<Operator> operators;
    private final Set<Integer> stringSwitchLines = new HashSet<>();
    private final List<Mutant> mutants = new ArrayList<>();
    private int line = -1;
    private int ordinal;
    private int callOrdinal;
    private int index;
    private @Nullable Instruction previous;

    MethodScan(
        ClassModel model,
        MethodModel method,
        List<CodeElement> code,
        Optional<String> filtered,
        List<Operator> operators) {
      this.model = model;
      this.method = method;
      this.code = code;
      this.filtered = filtered;
      this.operators = operators;
      this.tryWithResources = tryWithResources(code);
      this.operands = method.code().map(Operands::new).orElseThrow();
    }

    List<Mutant> mutants() {
      for (int at = 0; at < code.size(); at++) {
        switch (code.get(at)) {
          case LineNumber lineNumber -> line = lineNumber.line();
          case LabelTarget _ -> {
            previous = null; // a jump lands here: the instruction before isn't the only way in
            operands.jumpTarget();
          }
          case Instruction instruction -> scan(instruction, at);
          default -> {}
        }
      }
      return mutants;
    }

    private void scan(Instruction instruction, int at) {
      var workedOn = operands.of(instruction);
      if (isSwitch(instruction) && isCall(previous, "java/lang/String", "hashCode")) {
        stringSwitchLines.add(line); // javac's switch on a String: hashCode, then equals
      }
      var found = new ArrayList<Found>();
      for (Operator operator : operators) {
        switch (operator) {
          case io.mutastic.api.BuiltIn builtIn -> builtInChanges(builtIn, instruction, found);
          case Mutator mutator -> addedChanges(mutator, instruction, at, found);
        }
      }
      found.forEach(each -> mutants.add(mutant(each, instruction, at, workedOn)));
      if (instruction instanceof InvokeInstruction) {
        callOrdinal++;
      } else if (isSite(instruction)) {
        ordinal++;
      }
      index++;
      previous = instruction;
    }

    /// One change an operator makes at the instruction: its site, counted as the operator counts
    /// them, and its label.
    private record Found(
        Operator operator, int site, String label, Optional<String> skippedByOperator) {}

    private void builtInChanges(
        io.mutastic.api.BuiltIn builtIn, Instruction instruction, List<Found> found) {
      boolean call = instruction instanceof InvokeInstruction;
      if ((call || isSite(instruction)) && builtIn.onCalls() == call) {
        for (String label : Operators.replacements(builtIn, instruction)) {
          found.add(new Found(builtIn, call ? callOrdinal : ordinal, label, Optional.empty()));
        }
      }
    }

    /// A plugin's or rules file's operator: a call operator at a call, counted as calls are; any
    /// other at every instruction, counted in order.
    private void addedChanges(Mutator mutator, Instruction instruction, int at, List<Found> found) {
      boolean call = instruction instanceof InvokeInstruction;
      if (mutator.onCalls() && !call) {
        return;
      }
      var place = new Mutator.Site(model, method, code, at, instruction);
      for (Mutator.Change change : mutator.changes(place)) {
        found.add(
            new Found(
                mutator,
                mutator.onCalls() ? callOrdinal : index,
                change.label(),
                change.skipped()));
      }
    }

    private Mutant mutant(Found found, Instruction instruction, int at, List<String> workedOn) {
      var operator = found.operator();
      var skipped =
          tryWithResources.contains(instruction)
              ? Optional.of("generated: closes a try-with-resources resource")
              : found
                  .skippedByOperator()
                  .or(() -> skipReason(operator, instruction))
                  .or(() -> atTheSite(found, at))
                  .or(() -> filtered);
      return new Mutant(
          model.thisClass().asInternalName(),
          method.methodName().stringValue(),
          method.methodType().stringValue(),
          found.site(),
          line,
          operator,
          describe(instruction),
          found.label(),
          skipped,
          List.of(),
          workedOn);
    }

    /// Why a mutant shouldn't run, judged by the code around it: its own and an `||`'s.
    private Optional<String> atTheSite(Found found, int at) {
      return SiteSkips.reason(found.operator(), code, at)
          .or(
              () ->
                  ImpliedOr.reason(
                      found.operator(), found.label(), new ImpliedOr.Site(model, code, at)));
    }

    /// Why a mutant shouldn't run, if it shouldn't: the reason's first word is its kind.
    private Optional<String> skipReason(Operator operator, Instruction instruction) {
      if (operator == BuiltIn.RETURN_DEFAULT
          && previous instanceof ConstantInstruction constant
          && DEFAULT_CONSTANTS.contains(constant.opcode())) {
        return Optional.of("equivalent: already returns the default");
      }
      if (instruction instanceof BranchInstruction
          && stringSwitchLines.contains(line)
          && isCall(previous, "java/lang/String", "equals")) {
        return Optional.of("generated: part of a switch on a String");
      }
      if (instruction instanceof InvokeInstruction call
          && OUTPUT_OWNERS.contains(call.owner().asInternalName())) {
        return Optional.of("arid: only writes output");
      }
      return Optional.empty();
    }
  }

  /// The instructions javac writes to close a try-with-resources resource: on the normal path
  /// `aload r; ifnull; aload r; close()`, and again in a `catch Throwable` handler that adds a
  /// failure to close as suppressed. No test can see them, so their mutants would all be missed.
  /// Found only in a method that calls `Throwable.addSuppressed`: the `addSuppressed` calls, each
  /// `close()` on a local slot javac guards with `ifnull` or closes on both paths, and those
  /// `ifnull` guards.
  private static java.util.Set<Instruction> tryWithResources(List<CodeElement> code) {
    var instructions = instructionsIn(code);
    var generated =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Instruction, Boolean>());
    if (instructions.stream().noneMatch(Mutations::isAddSuppressed)) {
      return generated;
    }
    var closesBySlot = new java.util.HashMap<Integer, List<Integer>>();
    var guardedSlots = new HashSet<Integer>();
    for (int at = 1; at < instructions.size(); at++) {
      var instruction = instructions.get(at);
      if (isAddSuppressed(instruction)) {
        generated.add(instruction);
      }
      if (isClose(instruction) && instructions.get(at - 1) instanceof LoadInstruction load) {
        closesBySlot.computeIfAbsent(load.slot(), slot -> new ArrayList<>()).add(at);
        if (isNullGuarded(instructions, at, load.slot())) {
          guardedSlots.add(load.slot());
          generated.add(instructions.get(at - 2));
        }
      }
    }
    generated.addAll(resourceCloses(instructions, closesBySlot, guardedSlots));
    return generated;
  }

  private static List<Instruction> instructionsIn(List<CodeElement> code) {
    return code.stream()
        .filter(Instruction.class::isInstance)
        .map(Instruction.class::cast)
        .toList();
  }

  /// Whether the `close()` at `at` follows javac's guard on its slot: `aload r; ifnull; aload r`.
  private static boolean isNullGuarded(List<Instruction> instructions, int at, int slot) {
    return at >= 3
        && instructions.get(at - 2).opcode() == Opcode.IFNULL
        && instructions.get(at - 3) instanceof LoadInstruction guarded
        && guarded.slot() == slot;
  }

  /// The `close()` calls on each slot javac closes on both paths or guards with `ifnull`.
  private static List<Instruction> resourceCloses(
      List<Instruction> instructions,
      java.util.Map<Integer, List<Integer>> closesBySlot,
      Set<Integer> guardedSlots) {
    var closes = new ArrayList<Instruction>();
    closesBySlot.forEach(
        (slot, positions) -> {
          if (positions.size() >= 2 || guardedSlots.contains(slot)) {
            positions.forEach(at -> closes.add(instructions.get(at)));
          }
        });
    return closes;
  }

  private static boolean isAddSuppressed(Instruction instruction) {
    return isCall(instruction, "java/lang/Throwable", "addSuppressed");
  }

  private static boolean isClose(Instruction instruction) {
    return instruction instanceof InvokeInstruction call
        && call.name().equalsString("close")
        && call.type().equalsString("()V");
  }

  /// The class with exactly one instruction changed. `CodeBuilder` regenerates the stack maps.
  byte[] apply(ClassModel model, Mutant mutant) {
    var transform = CodeTransform.ofStateful(() -> new MutantTransform(mutant));
    return context.transformClass(
        model,
        ClassTransform.transformingMethodBodies(method -> isMethodOf(mutant, method), transform));
  }

  /// Copies a method's code with the mutant's sites changed.
  private static final class MutantTransform implements CodeTransform {
    private final Mutant mutant;
    private final SiteCounter sites = new SiteCounter();

    MutantTransform(Mutant mutant) {
      this.mutant = mutant;
    }

    @Override
    public void accept(CodeBuilder builder, CodeElement element) {
      // A mutant can leave code dead (a negated loop condition). The ClassFile API patches dead
      // code and shrinks max_locals, but the debug table still names the dead locals, and the JVM
      // rejects the class with a ClassFormatError that verification doesn't catch. Mutants run
      // without local-variable debug info; line numbers stay.
      //
      // Type annotations in the code (JSpecify's `@Nullable` on a local) say where each annotated
      // local lives by bytecode offset, which the API doesn't move when a mutant changes the
      // code's length: they'd point past the end, and the class wouldn't load. They go too.
      if (element instanceof LocalVariable
          || element instanceof LocalVariableType
          || element instanceof RuntimeVisibleTypeAnnotationsAttribute
          || element instanceof RuntimeInvisibleTypeAnnotationsAttribute) {
        return;
      }
      if (element instanceof Instruction instruction && sites.changedBy(mutant, instruction)) {
        emit(builder, instruction, mutant);
        return;
      }
      builder.with(element);
    }
  }

  /// Counts a method's sites as its code is walked, each kind on its own, as a mutant's ordinal
  /// does: instruction sites, call sites, and for a plugin's instruction operator every
  /// instruction.
  private static final class SiteCounter {
    private int ordinal;
    private int callOrdinal;
    private int index;

    /// Whether the mutant changes `instruction`, counting it as a site of each kind it is.
    boolean changedBy(Mutant mutant, Instruction instruction) {
      boolean call = instruction instanceof InvokeInstruction;
      int at = index++;
      int callSite = call ? callOrdinal++ : -1;
      int site = !call && isSite(instruction) ? ordinal++ : -1;
      var operator = mutant.operator();
      if (operator instanceof Mutator && !operator.onCalls()) {
        return mutant.changes(at);
      }
      return operator.onCalls()
          ? callSite >= 0 && mutant.changes(callSite)
          : site >= 0 && mutant.changes(site);
    }
  }

  private static boolean isMethodOf(Mutant mutant, MethodModel method) {
    return method.methodName().equalsString(mutant.methodName())
        && method.methodType().equalsString(mutant.descriptor());
  }

  /// The whole-number constants loaded just before the mutant's site, `100` in `total >= 100`: the
  /// values where its change shows, if anywhere.
  List<Long> constantsNear(ClassModel model, Mutant mutant) {
    for (MethodModel method : model.methods()) {
      if (isMethodOf(mutant, method)) {
        var constants = constantsBeforeSite(method.code().orElseThrow(), mutant);
        if (constants.isPresent()) {
          return constants.get();
        }
      }
    }
    return new ArrayList<>();
  }

  /// The whole-number constants among the two instructions before the mutant's first site, if the
  /// code has that site.
  private static Optional<List<Long>> constantsBeforeSite(
      java.lang.classfile.CodeModel code, Mutant mutant) {
    var recent = new java.util.ArrayDeque<Instruction>();
    var sites = new SiteCounter();
    for (CodeElement element : code) {
      if (!(element instanceof Instruction instruction)) {
        continue;
      }
      if (sites.changedBy(mutant, instruction)) {
        return Optional.of(wholeNumberConstants(recent));
      }
      recent.addLast(instruction);
      if (recent.size() > 2) {
        recent.removeFirst();
      }
    }
    return Optional.empty();
  }

  private static List<Long> wholeNumberConstants(Iterable<Instruction> instructions) {
    var constants = new ArrayList<Long>();
    for (Instruction before : instructions) {
      if (before instanceof ConstantInstruction constant
          && constant.constantValue() instanceof Number number
          && (number instanceof Integer || number instanceof Long)) {
        constants.add(number.longValue());
      }
    }
    return constants;
  }

  /// Verification errors in a mutated class: none means the JVM will load it.
  List<VerifyError> verify(byte[] bytes) {
    return context.verify(bytes);
  }

  private static void emit(CodeBuilder builder, Instruction instruction, Mutant mutant) {
    if (mutant.operator() instanceof Mutator mutator) {
      mutator.write(builder, instruction, mutant.replacement());
      return;
    }
    var operator = (io.mutastic.api.BuiltIn) mutant.operator();
    if (operator == io.mutastic.api.BuiltIn.REMOVE_CONDITIONAL) {
      removeConditional(builder, (BranchInstruction) instruction, mutant.replacement());
      return;
    }
    switch (instruction) {
      case BranchInstruction branch ->
          builder.with(
              BranchInstruction.of(Operators.replace(operator, branch.opcode()), branch.target()));
      case OperatorInstruction arithmetic ->
          builder.with(OperatorInstruction.of(Operators.replace(operator, arithmetic.opcode())));
      case ReturnInstruction returning -> returnDefault(builder, returning.typeKind());
      case InvokeInstruction call -> removeCall(builder, call);
      default -> throw new IllegalStateException("not a mutation site: " + instruction);
    }
  }

  /// The jump's operands popped, then the jump always taken, or never: the condition no longer
  /// decides anything.
  private static void removeConditional(
      CodeBuilder builder, BranchInstruction branch, String change) {
    for (int slot = 0; slot < Operators.tested(branch.opcode()); slot++) {
      builder.pop();
    }
    if (change.equals(Operators.ALWAYS_JUMP)) {
      builder.goto_(branch.target());
    }
  }

  private static void returnDefault(CodeBuilder builder, TypeKind kind) {
    pop(builder, kind);
    switch (kind) {
      case LONG -> builder.lconst_0();
      case FLOAT -> builder.fconst_0();
      case DOUBLE -> builder.dconst_0();
      case REFERENCE -> builder.aconst_null();
      default -> builder.iconst_0(); // int, and the boolean, char, short and byte it carries
    }
    builder.return_(kind);
  }

  private static void removeCall(CodeBuilder builder, InvokeInstruction call) {
    // The arguments, last first, then the receiver: the stack as if the call had run.
    for (var parameter : call.typeSymbol().parameterList().reversed()) {
      pop(builder, TypeKind.from(parameter));
    }
    if (call.opcode() != Opcode.INVOKESTATIC) {
      builder.pop();
    }
  }

  private static void pop(CodeBuilder builder, TypeKind kind) {
    if (kind.slotSize() == 2) {
      builder.pop2();
    } else {
      builder.pop();
    }
  }

  /// One mutant for each change javac copied with a `finally` block.
  ///
  /// javac compiles `finally` by copying its code onto every way out of the `try`: once on the
  /// normal path, and once in a catch-anything handler for exceptions. Mutating one copy alone is a
  /// different, weaker change than the source suggests (removing only the exception path's
  /// `unlock()` goes unnoticed unless a test throws), and counts one source change as several
  /// mutants. So the same change on the same line, with a copy on each side of a catch-anything
  /// handler's start, becomes one mutant that changes every copy.
  private static List<Mutant> mergeFinallyCopies(
      List<Mutant> found, java.lang.classfile.CodeModel code) {
    var handlers = catchAnythingHandlers(code);
    if (handlers.isEmpty()) {
      return found;
    }
    var starts = HandlerStarts.in(code, handlers);
    var groups = byChange(found);
    var merged = new ArrayList<Mutant>();
    for (Mutant mutant : found) {
      var group = requireNonNull(groups.get(changeOf(mutant)));
      if (!straddles(group, starts.forKindOf(mutant.operator()))) {
        merged.add(mutant);
      } else if (mutant.equals(
          group.getFirst())) { // the later copies go into this one, in its place
        merged.add(withLaterCopies(mutant, group));
      }
    }
    return merged;
  }

  private static Set<java.lang.classfile.Label> catchAnythingHandlers(
      java.lang.classfile.CodeModel code) {
    return code.exceptionHandlers().stream()
        .filter(handler -> handler.catchType().isEmpty())
        .map(java.lang.classfile.instruction.ExceptionCatch::handler)
        .collect(java.util.stream.Collectors.toSet());
  }

  /// Where each catch-anything handler starts, counted in each kind of site.
  private record HandlerStarts(List<Integer> sites, List<Integer> calls) {
    static HandlerStarts in(
        java.lang.classfile.CodeModel code, Set<java.lang.classfile.Label> handlers) {
      var siteStarts = new ArrayList<Integer>();
      var callStarts = new ArrayList<Integer>();
      int ordinal = 0;
      int callOrdinal = 0;
      for (CodeElement element : code) {
        if (element instanceof LabelTarget target && handlers.contains(target.label())) {
          siteStarts.add(ordinal);
          callStarts.add(callOrdinal);
        } else if (element instanceof InvokeInstruction) {
          callOrdinal++;
        } else if (element instanceof Instruction instruction && isSite(instruction)) {
          ordinal++;
        }
      }
      return new HandlerStarts(siteStarts, callStarts);
    }

    List<Integer> forKindOf(Operator operator) {
      return operator.onCalls() ? calls : sites;
    }
  }

  /// The mutants making each change, by line, operator, instruction and replacement.
  private static java.util.Map<List<Object>, List<Mutant>> byChange(List<Mutant> found) {
    var groups = new java.util.LinkedHashMap<List<Object>, List<Mutant>>();
    for (Mutant mutant : found) {
      groups.computeIfAbsent(changeOf(mutant), key -> new ArrayList<>()).add(mutant);
    }
    return groups;
  }

  private static List<Object> changeOf(Mutant mutant) {
    return List.of(mutant.line(), mutant.operator(), mutant.original(), mutant.replacement());
  }

  /// Whether the group has a copy on each side of some handler's start.
  private static boolean straddles(List<Mutant> group, List<Integer> starts) {
    return group.size() > 1
        && starts.stream()
            .anyMatch(
                start ->
                    group.stream().anyMatch(copy -> copy.ordinal() < start)
                        && group.stream().anyMatch(copy -> copy.ordinal() >= start));
  }

  /// The group's first mutant, changing the later copies' sites too.
  private static Mutant withLaterCopies(Mutant mutant, List<Mutant> group) {
    return new Mutant(
        mutant.className(),
        mutant.methodName(),
        mutant.descriptor(),
        mutant.ordinal(),
        mutant.line(),
        mutant.operator(),
        mutant.original(),
        mutant.replacement(),
        mutant.skipped(),
        group.stream().skip(1).map(Mutant::ordinal).toList(),
        mutant.operands());
  }

  /// `IF_ICMPLT`, or for a call `INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V`.
  private static String describe(Instruction instruction) {
    return instruction instanceof InvokeInstruction call
        ? "%s %s.%s%s"
            .formatted(
                call.opcode().name(),
                call.owner().asInternalName(),
                call.name().stringValue(),
                call.type().stringValue())
        : instruction.opcode().name();
  }

  /// Instruction sites: every arithmetic, branch and return instruction, whether or not an operator
  /// applies to it today.
  private static boolean isSite(Instruction instruction) {
    return instruction instanceof OperatorInstruction
        || instruction instanceof BranchInstruction
        || instruction instanceof ReturnInstruction;
  }

  private static boolean isSwitch(Instruction instruction) {
    return instruction.opcode() == Opcode.LOOKUPSWITCH
        || instruction.opcode() == Opcode.TABLESWITCH;
  }

  private static boolean isCall(@Nullable Instruction instruction, String owner, String name) {
    return instruction instanceof InvokeInstruction call
        && call.owner().asInternalName().equals(owner)
        && call.name().equalsString(name);
  }

  /// Code the user wrote. Constructors and static initialisers are left out (their mutants are
  /// mostly field defaults no test is meant to pin), as is code the compiler wrote: synthetic
  /// methods other than lambda bodies, an enum's `values()` and `valueOf()`, and a record's
  /// `toString()`, `hashCode()` and `equals()`.
  private static boolean mutable(ClassModel model, MethodModel method) {
    var name = method.methodName().stringValue();
    if (name.startsWith("<")) {
      return false;
    }
    if (method.flags().has(AccessFlag.SYNTHETIC) && !name.startsWith("lambda$")) {
      return false;
    }
    var self = "L" + model.thisClass().asInternalName() + ";";
    var descriptor = method.methodType().stringValue();
    boolean enumMethod =
        (name.equals("values") && descriptor.equals("()[" + self))
            || (name.equals("valueOf") && descriptor.equals("(Ljava/lang/String;)" + self));
    if (model.flags().has(AccessFlag.ENUM) && enumMethod) {
      return false;
    }
    return !isRecordObjectMethod(method);
  }

  /// A record's `toString()`, `hashCode()` or `equals()`: javac's are an `invokedynamic` on
  /// `ObjectMethods`.
  private static boolean isRecordObjectMethod(MethodModel method) {
    return method.code().stream()
        .flatMap(code -> code.elementStream())
        .anyMatch(
            element ->
                element instanceof InvokeDynamicInstruction indy
                    && indy.bootstrapMethod()
                        .owner()
                        .descriptorString()
                        .equals("Ljava/lang/runtime/ObjectMethods;"));
  }
}
