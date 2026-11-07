package com.example.lifecycle;

/// One method for each place tests can run code from, so a mutant's result says what that place
/// does.
public final class Sites {
  private Sites() {}

  public static int beforeValue(int x) {
    return x + 1;
  }

  public static int beforeThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeClassValue(int x) {
    return x + 1;
  }

  public static int beforeClassThrows(int x) {
    return 10 / (x + 1);
  }

  public static int ruleValue(int x) {
    return x + 1;
  }

  public static int ruleThrows(int x) {
    return 10 / (x + 1);
  }

  public static int classRuleValue(int x) {
    return x + 1;
  }

  public static int classRuleThrows(int x) {
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

  public static int afterChecked(int x) {
    return x + 1;
  }

  public static int afterThrows(int x) {
    return 10 / (x + 1);
  }

  public static int afterClassChecked(int x) {
    return x + 1;
  }

  public static int afterClassThrows(int x) {
    return 10 / (x + 1);
  }

  public static int ruleAfterChecked(int x) {
    return x + 1;
  }

  public static int ruleAfterThrows(int x) {
    return 10 / (x + 1);
  }

  public static int classRuleAfterChecked(int x) {
    return x + 1;
  }

  public static int classRuleAfterThrows(int x) {
    return 10 / (x + 1);
  }
}
