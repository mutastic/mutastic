package com.example.lifecycle;

/// One method for each place tests can run code from, so a mutant's result says what that place
/// does.
public final class Sites {
  private Sites() {}

  public static int beforeMethodValue(int x) {
    return x + 1;
  }

  public static int beforeMethodThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeClassValue(int x) {
    return x + 1;
  }

  public static int beforeClassThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeSuiteValue(int x) {
    return x + 1;
  }

  public static int beforeSuiteThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeTestValue(int x) {
    return x + 1;
  }

  public static int beforeTestThrows(int x) {
    return 10 / (x + 1);
  }

  public static int beforeGroupsValue(int x) {
    return x + 1;
  }

  public static int beforeGroupsThrows(int x) {
    return 10 / (x + 1);
  }

  public static int dataProviderValue(int x) {
    return x + 1;
  }

  public static int dataProviderThrows(int x) {
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

  public static int afterMethodChecked(int x) {
    return x + 1;
  }

  public static int afterMethodThrows(int x) {
    return 10 / (x + 1);
  }

  public static int afterClassChecked(int x) {
    return x + 1;
  }

  public static int afterClassThrows(int x) {
    return 10 / (x + 1);
  }

  public static int afterSuiteChecked(int x) {
    return x + 1;
  }

  public static int afterSuiteThrows(int x) {
    return 10 / (x + 1);
  }

  public static int afterTestChecked(int x) {
    return x + 1;
  }

  public static int afterTestThrows(int x) {
    return 10 / (x + 1);
  }
}
