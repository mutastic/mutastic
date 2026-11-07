package io.mutastic.plugins;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// Takes one annotation away from what it's on. What the annotation asks for then doesn't happen,
/// and a test that needs it notices. Each kind of annotation is an operator of its own, as it asks
/// for a test of its own: [Validation] and [Security].
abstract class AnnotationRemoval implements ClassMutator {
  private static final Pattern LABEL =
      Pattern.compile("remove (?<descriptor>L[^ ]+;)( from parameter (?<parameter>\\d+))?");

  private final AnnotationFamily family;

  AnnotationRemoval(AnnotationFamily family) {
    this.family = family;
  }

  /// What the card asks for, for `annotation` on `what`.
  abstract String help(String annotation, String what);

  @Override
  public List<Change> changes(ClassModel owner) {
    var changes = new ArrayList<Change>();
    var record = RecordShape.of(owner);
    addAll(changes, Member.ofClass(), AnnotationEdit.on(owner), Optional.empty());
    for (RecordComponentInfo component : record.components()) {
      var member =
          Member.ofField(
              component.name().stringValue(), component.descriptorSymbol().descriptorString());
      addAll(changes, member, record.annotationsOf(component, owner), Optional.empty());
    }
    for (FieldModel field : owner.fields()) {
      if (!record.isComponent(field)) {
        var member =
            Member.ofField(field.fieldName().stringValue(), field.fieldType().stringValue());
        addAll(changes, member, AnnotationEdit.on(field), Optional.empty());
      }
    }
    for (MethodModel method : owner.methods()) {
      if (!method.flags().has(AccessFlag.SYNTHETIC)) {
        addOfMethod(changes, method, record);
      }
    }
    return changes;
  }

  private void addOfMethod(List<Change> changes, MethodModel method, RecordShape record) {
    var member = Member.ofMethod(method);
    if (!record.isAccessor(method)) {
      addAll(changes, member, AnnotationEdit.on(method), Optional.empty());
    }
    if (record.isCanonicalConstructor(method)) {
      return; // each parameter's are its component's, there already
    }
    var parameters = method.findAttribute(Attributes.runtimeVisibleParameterAnnotations());
    if (parameters.isPresent()) {
      var each = parameters.get().parameterAnnotations();
      for (int index = 0; index < each.size(); index++) {
        addAll(changes, member, each.get(index), Optional.of(index));
      }
    }
  }

  private void addAll(
      List<Change> changes,
      Member member,
      List<Annotation> annotations,
      Optional<Integer> parameter) {
    for (Annotation annotation : annotations) {
      var descriptor = annotation.className().stringValue();
      if (family.has(descriptor)) {
        changes.add(change(member, descriptor, parameter));
      }
    }
  }

  private static Change change(Member member, String descriptor, Optional<Integer> parameter) {
    var name = AnnotationFamily.simpleName(descriptor);
    var label =
        "remove " + descriptor + parameter.map(index -> " from parameter " + index).orElse("");
    return new Change(
        member,
        label,
        "@" + name,
        "@" + name.substring(0, name.contains(".") ? name.indexOf('.') : name.length()),
        equivalent(member, descriptor, parameter));
  }

  /// A primitive can't be null, so `@NotNull` on one asks for nothing.
  private static Optional<String> equivalent(
      Member member, String descriptor, Optional<Integer> parameter) {
    boolean notNull = descriptor.endsWith("/constraints/NotNull;");
    boolean primitive =
        parameter.isEmpty() && member.isField() && member.descriptor().length() == 1;
    return notNull && primitive
        ? Optional.of("equivalent: a primitive is never null, so `@NotNull` can't refuse anything")
        : Optional.empty();
  }

  @Override
  public byte[] write(ClassModel owner, Member member, String label) {
    var matched = LABEL.matcher(label);
    if (!matched.matches()) {
      throw new IllegalArgumentException("not one of this operator's changes: " + label);
    }
    var parameter = Optional.ofNullable(matched.group("parameter")).map(Integer::valueOf);
    var removal =
        new RemovedAnnotation(
            new AnnotationEdit(matched.group("descriptor")),
            member,
            parameter,
            RecordShape.of(owner));
    return ClassFile.of().transformClass(owner, removal);
  }

  @Override
  public Mutator.Words words(Mutant mutant) {
    var annotation = mutant.original();
    var what = onWhat(mutant);
    return new Mutator.Words(
        annotation + " removed",
        "no test needs `%s` on %s".formatted(annotation, what),
        help(annotation, what));
  }

  /// `` `name` ``, `` `save()` ``, `parameter 1 of `save()``, or the class.
  private static String onWhat(Mutant mutant) {
    var member = new Member(mutant.methodName(), mutant.descriptor());
    if (member.isClass()) {
      var internal = mutant.className();
      return "`%s`".formatted(internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.'));
    }
    if (member.isField()) {
      return "`%s`".formatted(member.simpleName());
    }
    var matched = LABEL.matcher(mutant.replacement());
    var method = "`%s()`".formatted(member.simpleName());
    return matched.matches() && matched.group("parameter") != null
        ? "parameter %d of %s".formatted(Integer.parseInt(matched.group("parameter")) + 1, method)
        : method;
  }

  /// Bean Validation's constraints, and the annotations that turn validation on.
  public static final class Validation extends AnnotationRemoval {
    public Validation() {
      super(
          new AnnotationFamily(
              java.util.Set.of(
                  "Ljakarta/validation/Valid;",
                  "Ljavax/validation/Valid;",
                  "Lorg/springframework/validation/annotation/Validated;"),
              java.util.Set.of(
                  "Ljakarta/validation/constraints/",
                  "Ljavax/validation/constraints/",
                  "Lorg/hibernate/validator/constraints/")));
    }

    @Override
    public String id() {
      return "annotations/remove-validation";
    }

    @Override
    public String explanation() {
      return "A Bean Validation annotation is removed, so what it refuses is let through. It goes"
          + " unnoticed when no test gives a value it must refuse, and checks that it's refused.";
    }

    @Override
    String help(String annotation, String what) {
      return "add a test that validates a value %s must refuse, on %s, and assert it's refused"
          .formatted(annotation, what);
    }
  }

  /// The annotations that say who may call a method.
  public static final class Security extends AnnotationRemoval {
    public Security() {
      super(
          new AnnotationFamily(
              java.util.Set.of(
                  "Lorg/springframework/security/access/prepost/PreAuthorize;",
                  "Lorg/springframework/security/access/prepost/PostAuthorize;",
                  "Lorg/springframework/security/access/prepost/PreFilter;",
                  "Lorg/springframework/security/access/prepost/PostFilter;",
                  "Lorg/springframework/security/access/annotation/Secured;",
                  "Ljakarta/annotation/security/RolesAllowed;",
                  "Ljakarta/annotation/security/DenyAll;",
                  "Ljavax/annotation/security/RolesAllowed;",
                  "Ljavax/annotation/security/DenyAll;"),
              java.util.Set.of()));
    }

    @Override
    public String id() {
      return "annotations/remove-security";
    }

    @Override
    public String explanation() {
      return "A security annotation is removed, so anyone may do what it guards. It goes unnoticed"
          + " when no test makes the call as someone it must refuse.";
    }

    @Override
    String help(String annotation, String what) {
      return "add a test that reaches %s as a caller %s must refuse, and assert it's refused"
          .formatted(what, annotation);
    }
  }
}
