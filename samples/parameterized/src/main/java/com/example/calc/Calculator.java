package com.example.calc;

public class Calculator {
  public int add(int a, int b) {
    return a + b;
  }

  public int subtract(int a, int b) {
    return a - b;
  }

  public int multiply(int a, int b) {
    return a * b;
  }

  public int discount(int total) {
    if (total >= 100) {
      return total / 10;
    }
    return 0;
  }

  public boolean isEven(int n) {
    return n % 2 == 0;
  }
}
