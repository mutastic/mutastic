package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A method's parameters by the names the source gives them, read from its class file: the
/// MethodParameters attribute when javac wrote one (`-parameters`), else the LocalVariableTable it
/// writes with debug information (`-g`, as Gradle and Maven compile by default), else `arg0`,
/// `arg1`, as nothing else says.
final class ParameterNames {
  private ParameterNames() {}

  /// Every parameter's name, in order.
  static List<String> of(MethodModel method) {
    var types = method.methodTypeSymbol().parameterList();
    var declared = declared(method, types.size());
    var bySlot = bySlot(method);
    var names = new ArrayList<String>();
    int slot = method.flags().has(java.lang.reflect.AccessFlag.STATIC) ? 0 : 1;
    for (int index = 0; index < types.size(); index++) {
      var name =
          declared.size() == types.size() && !declared.get(index).isEmpty()
              ? declared.get(index)
              : bySlot.getOrDefault(slot, "arg" + index);
      names.add(KotlinNames.parameter(name));
      slot += wide(types.get(index)) ? 2 : 1;
    }
    return names;
  }

  /// The names in the MethodParameters attribute, empty where it has none.
  private static List<String> declared(MethodModel method, int count) {
    return method
        .findAttribute(Attributes.methodParameters())
        .map(
            attribute ->
                attribute.parameters().stream()
                    .map(parameter -> parameter.name().map(name -> name.stringValue()).orElse(""))
                    .toList())
        .filter(names -> names.size() == count)
        .orElse(List.of());
  }

  /// The LocalVariableTable's names for what's in each slot as the method starts: its parameters.
  private static Map<Integer, String> bySlot(MethodModel method) {
    var names = new HashMap<Integer, String>();
    method
        .code()
        .flatMap(code -> code.findAttribute(Attributes.localVariableTable()))
        .ifPresent(
            table ->
                table.localVariables().stream()
                    .filter(variable -> variable.startPc() == 0)
                    .forEach(
                        variable -> names.put(variable.slot(), variable.name().stringValue())));
    return names;
  }

  private static boolean wide(ClassDesc type) {
    return type.descriptorString().equals("J") || type.descriptorString().equals("D");
  }
}
