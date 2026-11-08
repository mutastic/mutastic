package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountTest {
  private final Account account = new Account("Ada", 10_000);
  private final List<PropertyChangeEvent> heard = new ArrayList<>();

  {
    account.addPropertyChangeListener(heard::add);
  }

  private List<String> heardOf() {
    return heard.stream().map(PropertyChangeEvent::getPropertyName).toList();
  }

  @Test
  void aDepositIsAnnounced() {
    account.deposit(500);

    assertEquals(10_500, account.getBalance());
    assertEquals(List.of("balance"), heardOf());
    assertEquals(10_500L, heard.get(0).getNewValue());
  }

  @Test
  void aWithdrawalIsAnnounced() {
    account.withdraw(2_500);

    assertEquals(7_500, account.getBalance());
    assertEquals(List.of("balance"), heardOf());
  }

  @Test
  void aWithdrawalOverTheLimitIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> account.withdraw(10_001));
  }

  @Test
  void interestIsAddedMonthly() {
    account.applyInterest(1_200);

    assertEquals(10_100, account.getBalance());
  }

  @Test
  void aNewOwnerIsAnnouncedOnce() {
    account.setOwner("Grace");
    account.setOwner("Grace");

    assertEquals(List.of("owner"), heardOf());
  }

  @Test
  void restoringASnapshotIsQuiet() {
    account.restore(new Account.Snapshot(42, "frozen"));

    assertEquals(42, account.getBalance());
    assertEquals(List.of(), heardOf());
  }

  @Test
  void aNicknameIsKept() {
    account.setNickname("savings");

    assertEquals("savings", account.getNickname());
  }

  @Test
  void closingAnnouncesEverything() {
    account.close();

    assertEquals("closed", account.getStatus());
    assertEquals(1, heard.size());
  }
}
