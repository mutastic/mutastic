package io.mutastic.plugins;

import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A Kotlin method returning a range or a sequence returns an empty one. mutastic's own return
/// operator makes those `null`, which Kotlin's own types say can't happen and any caller's first
/// use throws on, so it shows little about the value: an empty one is the mutant a test has to look
/// at the result to catch. Only in what kotlinc compiled, as `@kotlin.Metadata` shows.
public final class KotlinEmptyReturn implements Mutator {
  private static final String METADATA = "Lkotlin/Metadata;";
  private static final String EMPTY = "return empty";
  private static final String GET_EMPTY = "getEMPTY";
  private static final String EMPTY_SEQUENCE = "emptySequence";
  private static final ClassDesc SEQUENCE = ClassDesc.of("kotlin.sequences.Sequence");
  private static final ClassDesc SEQUENCES = ClassDesc.of("kotlin.sequences.SequencesKt");

  /// What each type's empty value is called in a card.
  private static final Map<ClassDesc, String> EMPTY_VALUES =
      Map.of(
          ClassDesc.of("kotlin.ranges.IntRange"),
          "range",
          ClassDesc.of("kotlin.ranges.LongRange"),
          "range",
          ClassDesc.of("kotlin.ranges.CharRange"),
          "range",
          SEQUENCE,
          "sequence");

  @Override
  public String id() {
    return "kotlin/empty-return";
  }

  @Override
  public String explanation() {
    return "A Kotlin method returning a range or a sequence returns an empty one. It goes unnoticed"
        + " when no test looks at what's in the result.";
  }

  @Override
  public boolean onCalls() {
    return false;
  }

  @Override
  public Tier tier() {
    return Tier.EXTENDED;
  }

  @Override
  public List<Change> changes(Site site) {
    var returned = site.method().methodTypeSymbol().returnType();
    if (!(site.instruction() instanceof ReturnInstruction)
        || !EMPTY_VALUES.containsKey(returned)
        || !isKotlin(site.owner())) {
      return List.of();
    }
    var label = EMPTY + " " + returned.descriptorString();
    return List.of(new Change(label, alreadyEmpty(site.code(), site.index())));
  }

  private static boolean isKotlin(ClassModel owner) {
    return owner
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(attribute -> attribute.annotations().stream().anyMatch(KotlinEmptyReturn::isMetadata))
        .orElse(false);
  }

  private static boolean isMetadata(Annotation annotation) {
    return annotation.className().equalsString(METADATA);
  }

  /// The empty value is what's already returned, with nothing else able to jump in between.
  private static Optional<String> alreadyEmpty(List<CodeElement> code, int at) {
    for (int before = at - 1; before >= 0; before--) {
      switch (code.get(before)) {
        case LabelTarget _ -> {
          return Optional.empty();
        }
        case InvokeInstruction call when isEmptyValue(call) -> {
          return Optional.of("equivalent: already returns an empty value");
        }
        case Instruction _ -> {
          return Optional.empty();
        }
        default -> {
          // a line number
        }
      }
    }
    return Optional.empty();
  }

  private static boolean isEmptyValue(InvokeInstruction call) {
    return call.name().equalsString(EMPTY_SEQUENCE) || call.name().equalsString(GET_EMPTY);
  }

  /// The label holds the type: all `write` has to go on, beside the return.
  @Override
  public void write(CodeBuilder builder, Instruction instruction, String label) {
    builder.pop();
    pushEmpty(builder, ClassDesc.ofDescriptor(label.substring(EMPTY.length() + 1)));
    builder.with(instruction);
  }

  @Override
  public Words words(Mutant mutant) {
    var returned = MethodTypeDesc.ofDescriptor(mutant.descriptor()).returnType();
    var value = EMPTY_VALUES.getOrDefault(returned, "value");
    var method = "`%s()`".formatted(mutant.methodName());
    return new Words(
        "returns an empty " + value,
        "no test looks at what's in the %s %s returns".formatted(value, method),
        "assert the values in the %s %s returns".formatted(value, method));
  }

  /// `IntRange.Companion`'s field and its `getEMPTY()`, or `SequencesKt.emptySequence()`.
  private static void pushEmpty(CodeBuilder builder, ClassDesc returned) {
    if (returned.equals(SEQUENCE)) {
      builder.invokestatic(SEQUENCES, EMPTY_SEQUENCE, MethodTypeDesc.of(SEQUENCE));
      return;
    }
    var companion = returned.nested("Companion");
    builder
        .getstatic(returned, "Companion", companion)
        .invokevirtual(companion, GET_EMPTY, MethodTypeDesc.of(returned));
  }
}
