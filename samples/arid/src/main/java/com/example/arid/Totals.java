package com.example.arid;

/** A command's output: the total, printed for the person who ran it. */
public final class Totals {
  private Totals() {}

  public static void print(long total) {
    System.out.println("total: " + total);
  }
}
