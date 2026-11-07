package io.mutastic.plugins;

import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/// What javac made of a record's header. An annotation on a component is written once in the source
/// and copied to the field, the accessor and the constructor's parameter, each read by someone, so
/// it's one change to all of them. For a class that's no record, it has no components.
record RecordShape(List<RecordComponentInfo> components) {
  static RecordShape of(ClassModel model) {
    return new RecordShape(
        model
            .findAttribute(Attributes.record())
            .map(attribute -> attribute.components())
            .orElse(List.of()));
  }

  boolean isComponent(FieldModel field) {
    return components.stream().anyMatch(component -> component.name().equals(field.fieldName()));
  }

  /// The component's accessor, whether javac wrote it or the source did.
  boolean isAccessor(MethodModel method) {
    return components.stream()
        .anyMatch(
            component ->
                component.name().equals(method.methodName())
                    && method
                        .methodType()
                        .equalsString("()" + component.descriptorSymbol().descriptorString()));
  }

  boolean isCanonicalConstructor(MethodModel method) {
    return !components.isEmpty()
        && method.methodName().equalsString("<init>")
        && method.methodType().equalsString(canonicalDescriptor());
  }

  private String canonicalDescriptor() {
    return components.stream()
        .map(component -> component.descriptorSymbol().descriptorString())
        .collect(Collectors.joining("", "(", ")V"));
  }

  /// What's on a component in any of the places javac copies to: which it copies an annotation to
  /// goes by what the annotation may target, and `@NotNull` can't target a component itself. Each
  /// type once, in the order they're found.
  List<Annotation> annotationsOf(RecordComponentInfo component, ClassModel owner) {
    var byType = new LinkedHashMap<String, Annotation>();
    var found = new ArrayList<>(AnnotationEdit.on(component));
    var name = component.name().stringValue();
    for (FieldModel field : owner.fields()) {
      if (field.fieldName().equalsString(name)) {
        found.addAll(AnnotationEdit.on(field));
      }
    }
    for (MethodModel method : owner.methods()) {
      if (isAccessor(method) && method.methodName().equalsString(name)) {
        found.addAll(AnnotationEdit.on(method));
      } else if (isCanonicalConstructor(method)) {
        method
            .findAttribute(Attributes.runtimeVisibleParameterAnnotations())
            .ifPresent(each -> found.addAll(each.parameterAnnotations().get(parameterOf(name))));
      }
    }
    found.forEach(
        annotation -> byType.putIfAbsent(annotation.className().stringValue(), annotation));
    return List.copyOf(byType.values());
  }

  /// Which parameter of the canonical constructor the component is.
  int parameterOf(String name) {
    for (int index = 0; index < components.size(); index++) {
      if (components.get(index).name().equalsString(name)) {
        return index;
      }
    }
    return -1;
  }
}
