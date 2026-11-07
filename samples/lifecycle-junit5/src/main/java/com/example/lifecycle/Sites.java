package com.example.lifecycle;

/// One method for each place tests can run code from, so a mutant's result says what that place
/// does.
public final class Sites {
  private Sites() {}

  public static int needsAnotherTestValue(int x) {
    return x + 1;
  }

  public static int needsAnotherTestThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeAllValue(int x) {
    return x + 1;
  }

  public static int beforeAllThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeEachValue(int x) {
    return x + 1;
  }

  public static int beforeEachThrows(int x) {
    return 10 / (x + 1);
  }

  public static int perClassValue(int x) {
    return x + 1;
  }

  public static int perClassThrows(int x) {
    return 10 / (x + 1);
  }

  public static int perClassFieldValue(int x) {
    return x + 1;
  }

  public static int perClassFieldThrows(int x) {
    return 10 / (x + 1);
  }

  public static int perClassConstructorValue(int x) {
    return x + 1;
  }

  public static int perClassConstructorThrows(int x) {
    return 10 / (x + 1);
  }

  public static int staticExtensionValue(int x) {
    return x + 1;
  }

  public static int staticExtensionThrows(int x) {
    return 10 / (x + 1);
  }

  public static int staticExtensionCallbackValue(int x) {
    return x + 1;
  }

  public static int staticExtensionCallbackThrows(int x) {
    return 10 / (x + 1);
  }

  public static int extendWithAllValue(int x) {
    return x + 1;
  }

  public static int extendWithAllThrows(int x) {
    return 10 / (x + 1);
  }

  public static int extendWithEachValue(int x) {
    return x + 1;
  }

  public static int extendWithEachThrows(int x) {
    return 10 / (x + 1);
  }

  public static int nestedOuterValue(int x) {
    return x + 1;
  }

  public static int nestedOuterThrows(int x) {
    return 10 / (x + 1);
  }

  public static int nestedEachValue(int x) {
    return x + 1;
  }

  public static int nestedEachThrows(int x) {
    return 10 / (x + 1);
  }

  public static int methodSourceValue(int x) {
    return x + 1;
  }

  public static int methodSourceThrows(int x) {
    return 10 / (x + 1);
  }

  public static int parameterizedValue(int x) {
    return x + 1;
  }

  public static int parameterizedThrows(int x) {
    return 10 / (x + 1);
  }

  public static int superclassAllValue(int x) {
    return x + 1;
  }

  public static int superclassAllThrows(int x) {
    return 10 / (x + 1);
  }

  public static int superclassEachValue(int x) {
    return x + 1;
  }

  public static int superclassEachThrows(int x) {
    return 10 / (x + 1);
  }

  public static int staticInitValue(int x) {
    return x + 1;
  }

  public static int staticInitThrows(int x) {
    return 10 / (x + 1);
  }

  public static int fieldValue(int x) {
    return x + 1;
  }

  public static int fieldThrows(int x) {
    return 10 / (x + 1);
  }

  public static int constructorValue(int x) {
    return x + 1;
  }

  public static int constructorThrows(int x) {
    return 10 / (x + 1);
  }

  public static int afterAllChecked(int x) {
    return x + 1;
  }

  public static int afterAllThrows(int x) {
    return 10 / (x + 1);
  }

  public static int afterEachChecked(int x) {
    return x + 1;
  }

  public static int afterEachThrows(int x) {
    return 10 / (x + 1);
  }

  public static int perClassAfterChecked(int x) {
    return x + 1;
  }

  public static int perClassAfterThrows(int x) {
    return 10 / (x + 1);
  }

  public static int staticExtensionAfterChecked(int x) {
    return x + 1;
  }

  public static int staticExtensionAfterThrows(int x) {
    return 10 / (x + 1);
  }

  public static int extendWithAfterAllChecked(int x) {
    return x + 1;
  }

  public static int extendWithAfterAllThrows(int x) {
    return 10 / (x + 1);
  }

  public static int extendWithAfterEachChecked(int x) {
    return x + 1;
  }

  public static int extendWithAfterEachThrows(int x) {
    return 10 / (x + 1);
  }

  public static int nestedOuterAfterChecked(int x) {
    return x + 1;
  }

  public static int nestedOuterAfterThrows(int x) {
    return 10 / (x + 1);
  }

  public static int superclassAfterChecked(int x) {
    return x + 1;
  }

  public static int superclassAfterThrows(int x) {
    return 10 / (x + 1);
  }
}
