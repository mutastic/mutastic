package com.example.sales;

/** Where timings go, for the dashboards to read. */
public interface Metrics {
  void record(String name, long value);
}
