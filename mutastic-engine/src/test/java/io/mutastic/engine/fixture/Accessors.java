package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;

/// Accessors of every shape the trivial-accessor rule must tell apart: the trivial ones whose
/// mutants are noise, and near misses with logic, which must still be mutated.
public class Accessors {
  private static String region = "eu";

  private String first = "Ada";
  private String last = "Lovelace";
  private boolean active = true;
  private int count;
  private Integer boxed = 1;
  private final List<String> tags = new ArrayList<>();
  private String cached;

  // Trivial: the field, as it is.
  public String getFirst() {
    return first;
  }

  public boolean isActive() {
    return active;
  }

  public int getCount() {
    return count;
  }

  public static String getRegion() {
    return region;
  }

  public String first() {
    return first;
  }

  public void setFirst(String first) {
    this.first = first;
  }

  public Accessors withCount(int count) {
    this.count = count;
    return this;
  }

  public Accessors count(int count) {
    this.count = count;
    return this;
  }

  public static void setRegion(String region) {
    Accessors.region = region;
  }

  // Not trivial: each has logic, or isn't what its name says.
  public String getLast() {
    return first; // the wrong field: its name says last
  }

  public int getBoxed() {
    return boxed; // unboxes: a call, and null throws
  }

  public List<String> getTags() {
    return List.copyOf(tags); // a defensive copy
  }

  public String getCached() {
    if (cached == null) {
      cached = first + " " + last; // lazy
    }
    return cached;
  }

  public String getName() {
    return first == null ? "" : first; // null to empty
  }

  // Near-miss fluent setters: each returns, so has a mutant to keep, but isn't trivial.
  public Accessors withLast(String last) {
    this.first = last; // the wrong field: its name says last
    return this;
  }

  public Accessors withBoxed(int boxed) {
    this.count = boxed; // the wrong field, of the right type
    return this;
  }

  public Accessors withActive(boolean active) {
    this.active = !active; // changes what it stores
    return this;
  }

  public Accessors withFirst(String first) {
    this.first = first;
    return new Accessors(); // not this
  }

  public Accessors withTags(Accessors other) {
    other.first = first; // another object's field
    return this;
  }

  public static Accessors withRegion(String region) {
    Accessors.region = region;
    return new Accessors(); // static: there's no this
  }
}
