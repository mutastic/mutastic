package com.example.ledger;

import java.util.Objects;

/// A bank account, in cents. Listeners hear of every change to its balance, owner, limit and
/// status, but not of all of them: see [#applyInterest].
public class Account extends Bean {
  /// What [#restore] puts back.
  public record Snapshot(long balance, String status) {}

  private String owner;
  private long balance;
  private long limit;
  private String status = "open";
  private String nickname = "";

  public Account(String owner, long balance) {
    this.owner = owner;
    this.balance = balance;
  }

  public String getOwner() {
    return owner;
  }

  /// Tells listeners only when the owner really changes.
  public void setOwner(String owner) {
    if (Objects.equals(this.owner, owner)) {
      return;
    }
    var old = this.owner;
    this.owner = owner;
    firePropertyChange("owner", old, owner);
  }

  public long getBalance() {
    return balance;
  }

  public void setBalance(long balance) {
    long old = this.balance;
    this.balance = balance;
    firePropertyChange("balance", old, balance);
  }

  public void deposit(long cents) {
    if (cents <= 0) {
      throw new IllegalArgumentException("a deposit is more than nothing");
    }
    long old = balance;
    balance = balance + cents;
    firePropertyChange("balance", old, balance);
  }

  public void withdraw(long cents) {
    if (cents > balance + limit) {
      throw new IllegalArgumentException("over the limit");
    }
    long old = balance;
    balance = balance - cents;
    firePropertyChange("balance", old, balance);
  }

  /// Adds a month's interest at `basisPoints` a year. The balance changes, and no listener hears of
  /// it: the write deposit, withdraw and setBalance all announce, this one forgets.
  public void applyInterest(int basisPoints) {
    balance = balance + balance * basisPoints / 10_000 / 12;
  }

  public long getLimit() {
    return limit;
  }

  public void setLimit(long limit) {
    long old = this.limit;
    this.limit = limit;
    firePropertyChange("limit", old, limit);
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    var old = this.status;
    this.status = status;
    changed("status", old, status);
  }

  private void changed(String property, Object old, Object now) {
    firePropertyChange(property, old, now);
  }

  public String getNickname() {
    return nickname;
  }

  /// A label only its owner sees: never announced, which may or may not be on purpose.
  public void setNickname(String nickname) {
    this.nickname = nickname;
  }

  /// Puts back what a snapshot holds, quietly: a test says so.
  public void restore(Snapshot snapshot) {
    this.balance = snapshot.balance();
    this.status = snapshot.status();
  }

  /// Closes the account and tells listeners that everything changed at once.
  public void close() {
    this.balance = 0;
    this.limit = 0;
    this.status = "closed";
    firePropertyChange(null, null, null);
  }
}
