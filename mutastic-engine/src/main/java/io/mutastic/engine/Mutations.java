package io.mutastic.engine;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.ClassMutator;
import io.mutastic.api.Filter;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import io.mutastic.api.Operator;
import io.mutastic.api.Policy;
import io.mutastic.api.Project;
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
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/// Finds the mutants in a class and applies one at a time, with only `java.lang.classfile`.
final class Mutations {
  /// Calls whose only effect is output: removing one changes what a person reads, not what the code
  /// does, so no test is expected to notice.
  static final Set<String> OUTPUT_OWNERS =
      Set.of(
          "org/slf4j/Logger",
          "java/util/logging/Logger",
          "java/lang/System$Logger",
          "org/apache/logging/log4j/Logger",
          "org/apache/commons/logging/Log",
          "java/io/PrintStream");

  /// mutastic's own operators whose mutants are verified as a plugin's are: each is built to load,
  /// but on what another compiler writes, or what a method here doesn't foresee, the mutant is
  /// skipped rather than stopping the run.
  private static final Set<Operator> VERIFIED =
      Set.of(
          BuiltIn.REMOVE_ASSIGNMENT,
          BuiltIn.OMIT_CALL,
          BuiltIn.SWAP_ARGUMENTS,
          BuiltIn.NULL_PARAMETER,
          BuiltIn.DROP_VARARG,
          BuiltIn.REMOVE_SWITCH_ARM,
          BuiltIn.REPLACE_BODY,
          BuiltIn.DROP_CATCH_TYPE,
          BuiltIn.REMOVE_THROW,
          BuiltIn.STRING_CONSTANT,
          BuiltIn.REGEX_CONSTANT);

  /// mutastic's own operators on an instruction that isn't a site of the other kinds, which can't
  /// be counted in with them without moving their ids.
  private static final Set<Operator> ON_ANY_INSTRUCTION =
      Set.of(
          BuiltIn.REMOVE_ASSIGNMENT,
          BuiltIn.REMOVE_FIELD_WRITE,
          BuiltIn.NULL_PARAMETER,
          BuiltIn.FLIP_INCREMENT,
          BuiltIn.CONSTANT_VALUE,
          BuiltIn.REMOVE_SWITCH_ARM,
          BuiltIn.REPLACE_BODY,
          BuiltIn.DROP_CATCH_TYPE,
          BuiltIn.REMOVE_THROW,
          BuiltIn.STRING_CONSTANT,
          BuiltIn.REGEX_CONSTANT);

  private final ClassFile context;
  private final Policy policy;

  private static final String NULL_PARAMETER = "null parameter";

  /// The operators this run uses on what's in a method, as the policy chooses.
  private final List<Operator> operators;

  private final AnnotatedSkips annotated = new AnnotatedSkips();

  /// The filters that judge code arid or the like, built in first, then the project's own; the
  /// first with a reason decides.
  private final List<Filter> judges;

  /// The module's classes by internal name, once [#prepare] has seen them.
  private Map<String, ClassModel> module = Map.of();

  /// Finds the mutants of the operators that change a class as a whole.
  private final WholeClasses wholeClasses;

  Mutations(ClassFile context) {
    this(context, Policy.NONE);
  }

  Mutations(ClassFile context, Policy policy) {
    this.context = context;
    this.policy = policy;
    var chosen = OperatorSet.active(policy);
    this.operators =
        chosen.stream().filter(operator -> !(operator instanceof ClassMutator)).toList();
    var all =
        new ArrayList<Filter>(
            List.of(
                new KotlinGenerated(),
                new TrivialAccessors(),
                new DebugRepresentations(this::kept)));
    all.addAll(policy.filters());
    this.judges = List.copyOf(all);
    this.wholeClasses = new WholeClasses(chosen, this::decidedByTheProject);
  }

  List<Mutant> find(ClassModel model) {
    var mutants = new ArrayList<Mutant>();
    for (MethodModel method : model.methods()) {
      var here = operatorsIn(model, method);
      if (!here.isEmpty()) {
        method
            .code()
            .ifPresent(
                code -> {
                  var found =
                      new MethodScan(
                              model,
                              method,
                              code.elementList(),
                              filtered(model, method),
                              here,
                              new Wrappers(module))
                          .mutants();
                  mutants.addAll(viable(model, mergeFinallyCopies(found, code)));
                });
      }
    }
    mutants.addAll(viable(model, wholeClasses.find(model)));
    return mutants;
  }

  /// The operators that change this method: all of them in code the user wrote, and in a
  /// constructor or static initialiser only a regular expression's, as a `Pattern` is usually a
  /// field, and left out of those it would have next to nothing to change.
  private List<Operator> operatorsIn(ClassModel model, MethodModel method) {
    if (mutable(model, method)) {
      return operators;
    }
    return method.methodName().stringValue().startsWith("<")
            && operators.contains(BuiltIn.REGEX_CONSTANT)
        ? List.of(BuiltIn.REGEX_CONSTANT)
        : List.of();
  }

  /// The mutants, with any of a plugin's or rules file's that wouldn't load skipped as unviable:
  /// mutastic's own operators are built to verify, and a failure of theirs stops the run, but a
  /// rule can't know every call it will meet. A removed assignment is checked too: javac's table of
  /// variables says it has a value, but another compiler's might not be so exact.
  private List<Mutant> viable(ClassModel model, List<Mutant> found) {
    var checked = new ArrayList<Mutant>(found.size());
    for (Mutant mutant : found) {
      var unviable =
          (isAdded(mutant.operator()) || VERIFIED.contains(mutant.operator()))
                  && mutant.skipped().isEmpty()
              ? whyUnviable(model, mutant)
              : Optional.<String>empty();
      checked.add(unviable.map(why -> mutant.skippedFor("unviable: " + why)).orElse(mutant));
    }
    return checked;
  }

  private static boolean isAdded(Operator operator) {
    return operator instanceof Mutator || operator instanceof ClassMutator;
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
    this.module =
        classes.stream()
            .collect(Collectors.toMap(model -> model.thisClass().asInternalName(), model -> model));
    judges.forEach(filter -> filter.prepare(classes));
  }

  /// Tells the operators that change a class as a whole of the project, before any of its classes.
  void prepareFor(Project project) {
    wholeClasses.prepareFor(project);
  }

  /// What the project itself says of a member of `model`: an annotation on the code, or a rule in
  /// `mutastic.yaml`. The filters aren't asked: they judge code, and a trivial getter is arid for
  /// what it runs, not for the annotation on it.
  private Optional<String> decidedByTheProject(ClassModel model, ClassMutator.Member member) {
    var method =
        model.methods().stream()
            .filter(
                candidate ->
                    candidate.methodName().equalsString(member.name())
                        && candidate.methodType().equalsString(member.descriptor()))
            .findFirst();
    var annotation =
        method.isPresent()
            ? annotated.skip(model, method.get())
            : annotated.skipClass(model, member.name());
    return annotation
        .or(
            () ->
                method.isPresent()
                    ? skippedByRule(model, method.get())
                    : skippedByRule(model, member.name()))
        .map(Skip::toString);
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
    return skippedByRule(model, method.methodName().stringValue());
  }

  private Optional<Skip> skippedByRule(ClassModel model, String member) {
    return rule(model, member, false)
        .map(
            rule ->
                new Skip(
                    Skip.Kind.USER, "%s (rule `%s`)".formatted(rule.reason(), rule.written())));
  }

  /// Whether the project keeps the method's mutants even if a filter calls the code arid.
  private boolean kept(ClassModel model, MethodModel method) {
    return Annotations.has(model, method, "DoMutate")
        || rule(model, method.methodName().stringValue(), true).isPresent();
  }

  private Optional<Rule> rule(ClassModel model, String member, boolean mutate) {
    var className = model.thisClass().asInternalName().replace('/', '.');
    return policy.rules().stream()
        .filter(rule -> rule.mutate() == mutate && rule.covers(className, member))
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
    private final Stores stores;
    private final Optional<String> filtered;
    private final List<Operator> operators;
    private final Wrappers wrappers;
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
        List<Operator> operators,
        Wrappers wrappers) {
      this.model = model;
      this.wrappers = wrappers;
      this.method = method;
      this.code = code;
      this.filtered = filtered;
      this.operators = operators;
      this.tryWithResources = tryWithResources(code);
      this.operands = method.code().map(Operands::new).orElseThrow();
      this.stores = new Stores(code);
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
          case io.mutastic.api.BuiltIn builtIn -> builtInChanges(builtIn, instruction, at, found);
          case Mutator mutator -> addedChanges(mutator, instruction, at, found);
          case ClassMutator _ ->
              throw new IllegalStateException("changes a class as a whole: see WholeClasses");
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
        Operator operator, int site, String label, Optional<String> skippedByOperator, int line) {
      /// A change on the line the scan has reached.
      Found(Operator operator, int site, String label, Optional<String> skippedByOperator) {
        this(operator, site, label, skippedByOperator, -1);
      }
    }

    private void builtInChanges(
        io.mutastic.api.BuiltIn builtIn, Instruction instruction, int at, List<Found> found) {
      switch (builtIn) {
        case RETURN_VALUE -> returnChanges(instruction, at, found);
        case BOUNDARY, COMPARISON, OTHER_COMPARISON -> {
          for (Mutator.Change change : Comparisons.changes(builtIn, code, at)) {
            found.add(new Found(builtIn, ordinal, change.label(), change.skipped()));
          }
        }
        case LOGICAL_CONNECTOR ->
            addIf(
                Connectors.at(code, at).isPresent(),
                new Found(builtIn, ordinal, Connectors.SWAPPED, Optional.empty()),
                found);
        case REMOVE_DISCARDED_CALL, OMIT_CALL, SWAP_ARGUMENTS, DROP_VARARG ->
            callChange(builtIn, instruction, at, found);
        case NULL_PARAMETER -> parameterChanges(found);
        case REMOVE_ASSIGNMENT -> assignmentChange(at, found);
        case REMOVE_FIELD_WRITE -> fieldWriteChange(instruction, found);
        default -> instructionChanges(builtIn, instruction, at, found);
      }
    }

    /// A call operator that reads the code around the call.
    private void callChange(BuiltIn builtIn, Instruction instruction, int at, List<Found> found) {
      switch (builtIn) {
        case REMOVE_DISCARDED_CALL ->
            addIf(
                isDiscarded(code, at),
                new Found(builtIn, callOrdinal, "remove call", Optional.empty()),
                found);
        case OMIT_CALL -> omittedCall(instruction, at, found);
        case SWAP_ARGUMENTS -> swappedArguments(at, found);
        default ->
            addIf(
                DroppedElement.at(code, at),
                new Found(builtIn, callOrdinal, DroppedElement.DROP, Optional.empty()),
                found);
      }
    }

    private static void addIf(boolean applies, Found change, List<Found> found) {
      if (applies) {
        found.add(change);
      }
    }

    private void swappedArguments(int at, List<Found> found) {
      SwappedArguments.at(code, at)
          .ifPresent(
              change ->
                  found.add(
                      new Found(
                          BuiltIn.SWAP_ARGUMENTS, callOrdinal, change.label(), change.skipped())));
    }

    /// A call that only wraps a value, unless its result is thrown away: taking away one of those
    /// is [BuiltIn#REMOVE_DISCARDED_CALL]'s change.
    private void omittedCall(Instruction instruction, int at, List<Found> found) {
      if (instruction instanceof InvokeInstruction call && !isDiscarded(code, at)) {
        wrappers
            .change(model, call)
            .ifPresent(
                change ->
                    found.add(new Found(BuiltIn.OMIT_CALL, callOrdinal, change, Optional.empty())));
      }
    }

    /// Each parameter holding an object, at the method's first instruction, unless nothing reads
    /// the value it comes in with. The label holds the slot, for [MutantTransform#atStart].
    private void parameterChanges(List<Found> found) {
      if (index == 0) {
        for (var parameter : stores.objectParameters(method)) {
          found.add(
              new Found(
                  BuiltIn.NULL_PARAMETER,
                  index,
                  "%s %d %s".formatted(NULL_PARAMETER, parameter.slot(), parameter.name()),
                  stores.parameterIsRead(parameter.slot())
                      ? Optional.empty()
                      : Optional.of("equivalent: nothing reads the value it comes in with")));
        }
      }
    }

    /// An assignment to a variable that has a value already, unless nothing reads the new one.
    private void assignmentChange(int at, List<Found> found) {
      stores
          .reassigned(at)
          .ifPresent(
              name ->
                  found.add(
                      new Found(
                          BuiltIn.REMOVE_ASSIGNMENT,
                          index,
                          "remove assignment to " + name,
                          stores.valueIsRead(at)
                              ? Optional.empty()
                              : Optional.of("equivalent: nothing reads the value it's given"))));
    }

    private void fieldWriteChange(Instruction instruction, List<Found> found) {
      if (instruction instanceof FieldInstruction field && isFieldWrite(field)) {
        var label = "remove write to " + field.name().stringValue();
        found.add(new Found(BuiltIn.REMOVE_FIELD_WRITE, index, label, Optional.empty()));
      }
    }

    /// An operator whose change is one instruction's, on its own kind of instruction.
    private void instructionChanges(
        io.mutastic.api.BuiltIn builtIn, Instruction instruction, int at, List<Found> found) {
      switch (builtIn) {
        case FLIP_INCREMENT -> incrementChange(instruction, at, found);
        case CONSTANT_VALUE -> {
          for (Mutator.Change change : new Constants(code, stores, method).changes(at)) {
            found.add(new Found(builtIn, index, change.label(), change.skipped()));
          }
        }
        case KEEP_OPERAND -> {
          for (String label : Arithmetic.operandsKept(code, at)) {
            found.add(new Found(builtIn, ordinal, label, Optional.empty()));
          }
        }
        default -> wholeCodeChanges(builtIn, instruction, at, found);
      }
    }

    /// An operator that reads more of the method than the code just around its site.
    private void wholeCodeChanges(
        BuiltIn builtIn, Instruction instruction, int at, List<Found> found) {
      switch (builtIn) {
        case REPLACE_BODY -> bodyChanges(found);
        case STRING_CONSTANT -> addChanges(builtIn, TextConstants.strings(code, at, stores), found);
        case REGEX_CONSTANT -> addChanges(builtIn, TextConstants.regexes(code, at), found);
        case DROP_CATCH_TYPE -> {
          for (String label : Exceptions.droppedCatches(code, at)) {
            found.add(new Found(builtIn, index, label, Optional.empty()));
          }
        }
        case REMOVE_THROW -> addChanges(builtIn, Exceptions.removedThrow(code, at, stores), found);
        case REMOVE_SWITCH_ARM -> {
          for (var arm : SwitchArms.at(code, at)) {
            found.add(new Found(builtIn, index, arm.label(), arm.skipped(), arm.line()));
          }
        }
        default -> siteChanges(builtIn, instruction, found);
      }
    }

    private void addChanges(BuiltIn builtIn, List<Mutator.Change> changes, List<Found> found) {
      for (Mutator.Change change : changes) {
        found.add(new Found(builtIn, index, change.label(), change.skipped()));
      }
    }

    /// The whole method's changes, once, at its first instruction.
    private void bodyChanges(List<Found> found) {
      if (index == 0) {
        for (Mutator.Change change : MethodBodies.changes(model, method, code)) {
          found.add(new Found(BuiltIn.REPLACE_BODY, index, change.label(), change.skipped()));
        }
      }
    }

    /// A step of a source variable the other way, unless nothing reads what it leaves. A step of
    /// `0` has no other way, and `-32768` has no `32768` to become; the table of local variables
    /// says what's a variable, so an enhanced `for`'s own index isn't one.
    private void incrementChange(Instruction instruction, int at, List<Found> found) {
      if (instruction instanceof IncrementInstruction step && Arithmetic.flips(step)) {
        stores
            .variableAt(step.slot(), at)
            .ifPresent(
                name ->
                    found.add(
                        new Found(
                            BuiltIn.FLIP_INCREMENT,
                            index,
                            Arithmetic.flipped(name, step),
                            stores.slotIsRead(step.slot(), at)
                                ? Optional.empty()
                                : Optional.of("equivalent: nothing reads the value it leaves"))));
      }
    }

    /// An operator that swaps one instruction for another, or removes a call, whatever's around it.
    private void siteChanges(
        io.mutastic.api.BuiltIn builtIn, Instruction instruction, List<Found> found) {
      boolean call = instruction instanceof InvokeInstruction;
      if ((call || isSite(instruction)) && builtIn.onCalls() == call) {
        for (String label : Operators.replacements(builtIn, instruction)) {
          found.add(new Found(builtIn, call ? callOrdinal : ordinal, label, Optional.empty()));
        }
      }
    }

    /// A return's changes, which depend on the method's type and the code just before it.
    private void returnChanges(Instruction instruction, int at, List<Found> found) {
      if (instruction instanceof ReturnInstruction returning
          && returning.typeKind() != TypeKind.VOID) {
        var returned = method.methodTypeSymbol().returnType();
        for (Mutator.Change change : ReturnValues.changes(model, returned, code, at)) {
          found.add(new Found(BuiltIn.RETURN_VALUE, ordinal, change.label(), change.skipped()));
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
                  .or(() -> skipReason(instruction))
                  .or(() -> atTheSite(found, at))
                  .or(() -> filtered);
      return new Mutant(
          model.thisClass().asInternalName(),
          method.methodName().stringValue(),
          method.methodType().stringValue(),
          found.site(),
          found.line() >= 0 ? found.line() : line,
          operator,
          // A handler's first instruction isn't what changes, and differs between finally's copies.
          operator == BuiltIn.DROP_CATCH_TYPE ? "catch" : describe(instruction),
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
    private Optional<String> skipReason(Instruction instruction) {
      if (instruction instanceof BranchInstruction
          && stringSwitchLines.contains(line)
          && isCall(previous, "java/lang/String", "equals")) {
        return Optional.of("generated: part of a switch on a String");
      }
      if (instruction instanceof InvokeInstruction call
          && OUTPUT_OWNERS.contains(call.owner().asInternalName())) {
        return Optional.of("arid: only writes output");
      }
      if (instruction instanceof InvokeInstruction call && KotlinGenerated.isInserted(call)) {
        return Optional.of("generated: kotlinc's own check on a value its types already promise");
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
    if (mutant.operator() instanceof ClassMutator mutator) {
      return WholeClasses.apply(model, mutant, mutator);
    }
    var targets =
        mutant.operator() == BuiltIn.LOGICAL_CONNECTOR
            ? connectorTargets(model, mutant)
            : List.<Optional<java.lang.classfile.Label>>of();
    var handlers =
        mutant.operator() == BuiltIn.DROP_CATCH_TYPE
            ? handlersChanged(model, mutant)
            : Set.<java.lang.classfile.Label>of();
    var transform = CodeTransform.ofStateful(() -> new MutantTransform(mutant, targets, handlers));
    return context.transformClass(
        model,
        ClassTransform.transformingMethodBodies(method -> isMethodOf(mutant, method), transform));
  }

  /// The handlers that start a dropped `catch` type's sites.
  private static Set<java.lang.classfile.Label> handlersChanged(ClassModel model, Mutant mutant) {
    var handlers = new HashSet<java.lang.classfile.Label>();
    for (MethodModel method : model.methods()) {
      if (isMethodOf(mutant, method)) {
        var code = method.code().orElseThrow().elementList();
        var sites = new SiteCounter();
        for (int at = 0; at < code.size(); at++) {
          if (code.get(at) instanceof Instruction instruction
              && sites.changedBy(mutant, instruction)) {
            handlers.addAll(Exceptions.handlersAt(code, at));
          }
        }
      }
    }
    return handlers;
  }

  /// Where each of a connector mutant's first jumps goes instead, site by site: the second's
  /// target, when the first jumped to just past it; empty when both jumped to one place, and the
  /// first now jumps to just past the second.
  private static List<Optional<java.lang.classfile.Label>> connectorTargets(
      ClassModel model, Mutant mutant) {
    var targets = new ArrayList<Optional<java.lang.classfile.Label>>();
    for (MethodModel method : model.methods()) {
      if (isMethodOf(mutant, method)) {
        var code = method.code().orElseThrow().elementList();
        var sites = new SiteCounter();
        for (int at = 0; at < code.size(); at++) {
          if (code.get(at) instanceof Instruction instruction
              && sites.changedBy(mutant, instruction)) {
            var pair = Connectors.at(code, at).orElseThrow();
            var second = (BranchInstruction) code.get(pair.second());
            targets.add(pair.oneTarget() ? Optional.empty() : Optional.of(second.target()));
          }
        }
      }
    }
    return targets;
  }

  /// Copies a method's code with the mutant's sites changed.
  private static final class MutantTransform implements CodeTransform {
    private final Mutant mutant;
    private final SiteCounter sites = new SiteCounter();
    private final List<Optional<java.lang.classfile.Label>> connectorTargets;
    private final Set<java.lang.classfile.Label> catchHandlers;
    private int connectorsChanged;

    /// A label to bind just after the next jump: where a connector's first jump now goes.
    private java.lang.classfile.@Nullable Label pastTheSecond;

    MutantTransform(
        Mutant mutant,
        List<Optional<java.lang.classfile.Label>> connectorTargets,
        Set<java.lang.classfile.Label> catchHandlers) {
      this.mutant = mutant;
      this.connectorTargets = connectorTargets;
      this.catchHandlers = catchHandlers;
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
      if (isOffsetBound(element) || isLeftOut(element)) {
        return;
      }
      if (element instanceof Instruction instruction
          && !leavesInstructions(mutant.operator())
          && sites.changedBy(mutant, instruction)) {
        if (mutant.operator() == BuiltIn.LOGICAL_CONNECTOR) {
          swapConnector(builder, (BranchInstruction) instruction);
        } else {
          emit(builder, instruction, mutant);
        }
        return;
      }
      builder.with(element);
      if (pastTheSecond != null && element instanceof BranchInstruction) {
        builder.labelBinding(pastTheSecond);
        pastTheSecond = null;
      }
    }

    /// A replaced body's code, which atStart has written instead, or a dropped `catch` type's
    /// entry.
    private boolean isLeftOut(CodeElement element) {
      return mutant.operator() == BuiltIn.REPLACE_BODY
          || (element instanceof ExceptionCatch entry
              && Exceptions.drops(entry, catchHandlers, mutant.replacement()));
    }

    /// Whether its change isn't to the instruction at its site: a `null` written at the start, or
    /// an entry out of the exception table.
    private static boolean leavesInstructions(Operator operator) {
      return operator == BuiltIn.NULL_PARAMETER || operator == BuiltIn.DROP_CATCH_TYPE;
    }

    private static boolean isOffsetBound(CodeElement element) {
      return element instanceof LocalVariable
          || element instanceof LocalVariableType
          || element instanceof RuntimeVisibleTypeAnnotationsAttribute
          || element instanceof RuntimeInvisibleTypeAnnotationsAttribute;
    }

    /// A parameter's `null` goes before everything, a label included: a loop that starts the method
    /// jumps back past it.
    @Override
    public void atStart(CodeBuilder builder) {
      if (mutant.operator() == BuiltIn.NULL_PARAMETER) {
        var slot = mutant.replacement().substring(NULL_PARAMETER.length() + 1).split(" ", 2)[0];
        builder.aconst_null().astore(Integer.parseInt(slot));
      } else if (mutant.operator() == BuiltIn.REPLACE_BODY) {
        var returned = java.lang.constant.MethodTypeDesc.ofDescriptor(mutant.descriptor());
        MethodBodies.write(builder, returned.returnType(), mutant.replacement());
      }
    }

    /// The first test's jump the other way round, to the second's target or to just past it.
    private void swapConnector(CodeBuilder builder, BranchInstruction first) {
      var target = connectorTargets.get(connectorsChanged++);
      var to = target.orElseGet(builder::newLabel);
      if (target.isEmpty()) {
        pastTheSecond = to;
      }
      builder.with(BranchInstruction.of(Connectors.negated(first.opcode()), to));
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
      if (countsInstructions(operator)) {
        return mutant.changes(at);
      }
      return operator.onCalls()
          ? callSite >= 0 && mutant.changes(callSite)
          : site >= 0 && mutant.changes(site);
    }
  }

  /// Whether the operator's sites are every instruction, counted in order: a plugin's that isn't on
  /// calls, or one of mutastic's on an instruction that isn't a site of the other kinds, which
  /// can't be counted in with them without moving their ids.
  private static boolean countsInstructions(Operator operator) {
    return (operator instanceof Mutator && !operator.onCalls()) || isOnAnyInstruction(operator);
  }

  private static boolean isOnAnyInstruction(Operator operator) {
    return ON_ANY_INSTRUCTION.contains(operator);
  }

  private static boolean isFieldWrite(FieldInstruction field) {
    return field.opcode() == Opcode.PUTFIELD || field.opcode() == Opcode.PUTSTATIC;
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
          // A comparison's change is the jump it takes instead, by name.
          builder.with(BranchInstruction.of(Opcode.valueOf(mutant.replacement()), branch.target()));
      case OperatorInstruction arithmetic -> Arithmetic.write(builder, arithmetic, mutant);
      case ConstantInstruction constant -> Constants.write(builder, constant, mutant);
      case IncrementInstruction step -> builder.iinc(step.slot(), -step.constant());
      case ReturnInstruction returning -> ReturnValues.write(builder, returning, mutant);
      case InvokeInstruction call -> changeCall(builder, call, mutant);
      case StoreInstruction store -> pop(builder, store.typeKind());
      case FieldInstruction field -> removeWrite(builder, field);
      default -> emitWhole(builder, instruction, mutant);
    }
  }

  /// A change to an instruction with no kind of its own in [#emit].
  private static void emitWhole(CodeBuilder builder, Instruction instruction, Mutant mutant) {
    if (mutant.operator() == BuiltIn.REMOVE_SWITCH_ARM) {
      SwitchArms.write(builder, instruction, mutant.replacement());
    } else if (mutant.operator() == BuiltIn.REMOVE_THROW) {
      builder.pop();
    } else {
      throw new IllegalStateException("not a mutation site: " + instruction);
    }
  }

  /// A built-in call operator's change.
  private static void changeCall(CodeBuilder builder, InvokeInstruction call, Mutant mutant) {
    if (mutant.operator() == BuiltIn.OMIT_CALL) {
      Wrappers.omit(builder, call, mutant.replacement());
    } else if (mutant.operator() == BuiltIn.SWAP_ARGUMENTS) {
      SwappedArguments.write(builder, call);
    } else if (mutant.operator() == BuiltIn.DROP_VARARG) {
      DroppedElement.write(builder, call);
    } else {
      removeCall(builder, call, mutant.operator() == BuiltIn.REMOVE_DISCARDED_CALL);
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

  /// The call's arguments and receiver popped; for a call whose result is thrown away, a value of
  /// its type in the result's place, for the `pop` after it.
  private static void removeCall(CodeBuilder builder, InvokeInstruction call, boolean discarded) {
    // The arguments, last first, then the receiver: the stack as if the call had run.
    for (var parameter : call.typeSymbol().parameterList().reversed()) {
      pop(builder, TypeKind.from(parameter));
    }
    if (call.opcode() != Opcode.INVOKESTATIC) {
      builder.pop();
    }
    if (discarded) {
      placeholder(builder, TypeKind.from(call.typeSymbol().returnType()));
    }
  }

  /// The value popped, and for an instance field the object it was going into.
  private static void removeWrite(CodeBuilder builder, FieldInstruction field) {
    pop(builder, TypeKind.from(field.typeSymbol()));
    if (field.opcode() == Opcode.PUTFIELD) {
      builder.pop();
    }
  }

  /// A value of the kind a removed call returned, for the `pop` after it to throw away in its
  /// place.
  private static void placeholder(CodeBuilder builder, TypeKind kind) {
    switch (kind) {
      case LONG -> builder.lconst_0();
      case FLOAT -> builder.fconst_0();
      case DOUBLE -> builder.dconst_0();
      case REFERENCE -> builder.aconst_null();
      default -> builder.iconst_0();
    }
  }

  static void pop(CodeBuilder builder, TypeKind kind) {
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
  private record HandlerStarts(List<Integer> sites, List<Integer> calls, List<Integer> all) {
    static HandlerStarts in(
        java.lang.classfile.CodeModel code, Set<java.lang.classfile.Label> handlers) {
      var siteStarts = new ArrayList<Integer>();
      var callStarts = new ArrayList<Integer>();
      var allStarts = new ArrayList<Integer>();
      int ordinal = 0;
      int callOrdinal = 0;
      int index = 0;
      for (CodeElement element : code) {
        if (element instanceof LabelTarget target && handlers.contains(target.label())) {
          siteStarts.add(ordinal);
          callStarts.add(callOrdinal);
          allStarts.add(index);
        } else if (element instanceof Instruction instruction) {
          index++;
          if (instruction instanceof InvokeInstruction) {
            callOrdinal++;
          } else if (isSite(instruction)) {
            ordinal++;
          }
        }
      }
      return new HandlerStarts(siteStarts, callStarts, allStarts);
    }

    List<Integer> forKindOf(Operator operator) {
      if (isOnAnyInstruction(operator)) {
        return all;
      }
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

  /// The group's first mutant, changing the later copies' sites too. An assignment or step nothing
  /// reads after one copy may be read after another: `return` in a `try` runs a copy of the
  /// `finally` with nothing after it, and so it counts as read if any copy's is.
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
        (mutant.operator() == BuiltIn.REMOVE_ASSIGNMENT
                    || mutant.operator() == BuiltIn.FLIP_INCREMENT)
                && group.stream().anyMatch(copy -> copy.skipped().isEmpty())
            ? Optional.empty()
            : mutant.skipped(),
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

  /// A call whose result the next instruction throws away: `invoke…; pop`, or `pop2` for a `long`
  /// or `double`, with no jump landing between them. Never a constructor: `new X();` alone is `new;
  /// dup; invokespecial; pop`, and its call returns nothing.
  static boolean isDiscarded(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof InvokeInstruction call)) {
      return false;
    }
    var returned = TypeKind.from(call.typeSymbol().returnType());
    if (returned == TypeKind.VOID) {
      return false;
    }
    for (int index = at + 1; index < code.size(); index++) {
      switch (code.get(index)) {
        case LabelTarget _ -> {
          return false;
        }
        case Instruction next -> {
          return next.opcode() == (returned.slotSize() == 2 ? Opcode.POP2 : Opcode.POP);
        }
        default -> {}
      }
    }
    return false;
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
