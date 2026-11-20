package com.example.arid;

import java.io.PrintStream;

/** A command's output: the total, printed for the person who ran it. */
public final class Totals {
  private Totals() {}

  public static void print(PrintStream out, long total) {
    out.println("total: " + total);
  }
}
