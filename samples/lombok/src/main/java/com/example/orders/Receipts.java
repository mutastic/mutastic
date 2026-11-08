package com.example.orders;

import java.io.BufferedReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Cleanup;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/// `@Slf4j` writes the logger, `@SneakyThrows` lets an `IOException` through undeclared, and
/// `@Cleanup` closes what it's on.
@Slf4j
public class Receipts {
  @SneakyThrows
  public static void write(@NonNull Order order, Path file) {
    @Cleanup Writer out = Files.newBufferedWriter(file);
    out.write(order.getCustomer().getName() + "\n");
    out.write(order.total().getCents() + "\n");
    log.info("receipt for {} written to {}", order.getCustomer().getName(), file);
  }

  @SneakyThrows
  public static long totalIn(Path file) {
    @Cleanup BufferedReader in = Files.newBufferedReader(file);
    in.readLine();
    return Long.parseLong(in.readLine().strip());
  }
}
