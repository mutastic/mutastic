package io.mutastic.plugins;

import io.mutastic.api.ClassMutator.Member;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Optional;

/// A class with one annotation taken off one member, and off every copy javac made of it: a record
/// component's field, accessor and constructor parameter, and the bridge methods of a method whose
/// return type narrows what it overrides.
record RemovedAnnotation(
    AnnotationEdit edit, Member member, Optional<Integer> parameter, RecordShape record)
    implements ClassTransform {
  @Override
  public void accept(ClassBuilder builder, ClassElement element) {
    switch (element) {
      case RuntimeVisibleAnnotationsAttribute attribute when member.isClass() -> {
        var left = edit.without(attribute);
        if (left != null) {
          builder.with((ClassElement) left);
        }
      }
      case RecordAttribute attribute when isComponent() ->
          builder.with(withoutOnComponent(attribute));
      case FieldModel field when isTheField(field) ->
          builder.transformField(field, FieldTransform.ofStateful(RemovingFromField::new));
      case MethodModel method -> method(builder, method);
      default -> builder.with(element);
    }
  }

  private boolean isComponent() {
    return member.isField() && record.parameterOf(member.simpleName()) >= 0;
  }

  private boolean isTheField(FieldModel field) {
    return member.isField() && field.fieldName().equalsString(member.simpleName());
  }

  private void method(ClassBuilder builder, MethodModel method) {
    if (isComponent() && record.isCanonicalConstructor(method)) {
      int index = record.parameterOf(member.simpleName());
      builder.transformMethod(method, (to, on) -> onMethod(to, on, Optional.of(index), false));
    } else if (isComponent() && isAccessorOfTheComponent(method)) {
      builder.transformMethod(method, (to, on) -> onMethod(to, on, Optional.empty(), true));
    } else if (member.isMethod() && (isTheMethod(method) || isItsBridge(method))) {
      builder.transformMethod(method, (to, on) -> onMethod(to, on, parameter, parameter.isEmpty()));
    } else {
      builder.with(method);
    }
  }

  private boolean isAccessorOfTheComponent(MethodModel method) {
    return record.isAccessor(method) && method.methodName().equalsString(member.simpleName());
  }

  private boolean isTheMethod(MethodModel method) {
    return method.methodName().equalsString(member.name())
        && method.methodType().equalsString(member.descriptor());
  }

  /// javac copies a method's annotations to its bridge, by which an overridden method's callers
  /// reach it.
  private boolean isItsBridge(MethodModel method) {
    return method.flags().has(AccessFlag.BRIDGE) && method.methodName().equalsString(member.name());
  }

  /// One method's element: its own annotations go when `onMethod`, a parameter's when one is named.
  private void onMethod(
      MethodBuilder to, MethodElement on, Optional<Integer> inParameter, boolean onMethod) {
    switch (on) {
      case RuntimeVisibleAnnotationsAttribute attribute when onMethod -> {
        var left = edit.without(attribute);
        if (left != null) {
          to.with((MethodElement) left);
        }
      }
      case RuntimeVisibleParameterAnnotationsAttribute attribute when inParameter.isPresent() ->
          to.with(edit.withoutOnParameter(attribute, inParameter.get()));
      case RuntimeVisibleTypeAnnotationsAttribute attribute -> {
        var left = edit.withoutOnType(attribute, target -> isTarget(target, inParameter, onMethod));
        if (left != null) {
          to.with((MethodElement) left);
        }
      }
      default -> to.with(on);
    }
  }

  private static boolean isTarget(
      TypeAnnotation.TargetInfo target, Optional<Integer> inParameter, boolean onMethod) {
    return switch (target) {
      case TypeAnnotation.FormalParameterTarget formal ->
          inParameter.filter(index -> index == formal.formalParameterIndex()).isPresent();
      case TypeAnnotation.EmptyTarget empty ->
          onMethod && empty.targetType() == TypeAnnotation.TargetType.METHOD_RETURN;
      default -> false;
    };
  }

  /// The component's own annotations, and everything else in the record as it was.
  private RecordAttribute withoutOnComponent(RecordAttribute attribute) {
    var components = new ArrayList<RecordComponentInfo>();
    for (RecordComponentInfo component : attribute.components()) {
      components.add(
          component.name().equalsString(member.simpleName()) ? stripped(component) : component);
    }
    return RecordAttribute.of(components);
  }

  private RecordComponentInfo stripped(RecordComponentInfo component) {
    var kept = new ArrayList<java.lang.classfile.Attribute<?>>();
    for (var attribute : component.attributes()) {
      switch (attribute) {
        case RuntimeVisibleAnnotationsAttribute annotations ->
            addIfAny(kept, edit.without(annotations));
        case RuntimeVisibleTypeAnnotationsAttribute types ->
            addIfAny(kept, edit.withoutOnType(types, target -> true));
        default -> kept.add(attribute);
      }
    }
    return RecordComponentInfo.of(component.name(), component.descriptor(), kept);
  }

  private static void addIfAny(
      java.util.List<java.lang.classfile.Attribute<?>> kept,
      java.lang.classfile.@org.jspecify.annotations.Nullable Attribute<?> attribute) {
    if (attribute != null) {
      kept.add(attribute);
    }
  }

  /// A field's annotations, on the declaration and on its type.
  private final class RemovingFromField implements FieldTransform {
    @Override
    public void accept(java.lang.classfile.FieldBuilder to, java.lang.classfile.FieldElement on) {
      switch (on) {
        case RuntimeVisibleAnnotationsAttribute attribute -> {
          var left = edit.without(attribute);
          if (left != null) {
            to.with((java.lang.classfile.FieldElement) left);
          }
        }
        case RuntimeVisibleTypeAnnotationsAttribute attribute -> {
          var left = edit.withoutOnType(attribute, target -> true);
          if (left != null) {
            to.with((java.lang.classfile.FieldElement) left);
          }
        }
        default -> to.with(on);
      }
    }
  }
}
