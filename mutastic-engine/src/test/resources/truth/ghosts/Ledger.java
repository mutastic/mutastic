package ghosts;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Objects;

/// Beans whose writes fire property changes, and some that don't: what ghosts are found in, and
/// run on. Each write that may get a ghost is a statement of its own line.
public class Ledger {
  public abstract static class Bean {
    protected final PropertyChangeSupport changes = new PropertyChangeSupport(this);

    public void addPropertyChangeListener(PropertyChangeListener listener) {
      changes.addPropertyChangeListener(listener);
    }

    protected void firePropertyChange(String property, Object old, Object now) {
      changes.firePropertyChange(property, old, now);
    }
  }

  public static class Account extends Bean {
    private String owner = "";
    private long balance;
    private String status = "open";

    public Account() {
      this.balance = 100;
    }

    public void setOwner(String owner) {
      if (Objects.equals(this.owner, owner)) {
        return;
      }
      var old = this.owner;
      this.owner = owner;
      firePropertyChange("owner", old, owner);
    }

    public void setBalance(long balance) {
      long old = this.balance;
      this.balance = balance;
      firePropertyChange("balance", old, balance);
    }

    public void deposit(long cents) {
      long old = balance;
      balance = balance + cents;
      firePropertyChange("balance", old, balance);
    }

    public void withdraw(long cents) {
      long old = balance;
      balance = balance - cents;
      firePropertyChange("balance", old, balance);
    }

    public void applyInterest(int basisPoints) {
      balance = balance + balance * basisPoints / 10_000;
    }

    public void restore(long saved) {
      this.balance = saved;
    }

    public void setStatus(String status) {
      var old = this.status;
      this.status = status;
      changed("status", old, status);
    }

    public void freeze() {
      var old = status;
      status = "frozen";
      firePropertyChange("status", old, status);
    }

    public void thaw() {
      status = "thawed";
    }

    private void changed(String property, Object old, Object now) {
      firePropertyChange(property, old, now);
    }

    public void close() {
      balance = 0;
      status = "closed";
      firePropertyChange(null, null, null);
    }

    public long getBalance() {
      return balance;
    }
  }

  public static class Customer extends Bean {
    private String name = "";
    private int age;
    private boolean vip;
    private double rating;

    public void setName(String name) {
      var old = this.name;
      this.name = name;
      firePropertyChange("name", old, name);
    }

    public void setAge(int age) {
      int old = this.age;
      this.age = age;
      firePropertyChange("age", old, age);
    }

    public void setVip(boolean vip) {
      boolean old = this.vip;
      this.vip = vip;
      firePropertyChange("vip", old, vip);
    }

    public void setRating(double rating) {
      this.rating = rating;
    }

    public double getRating() {
      return rating;
    }
  }

  public static class Branch {
    private final PropertyChangeSupport support = new PropertyChangeSupport(this);
    private String name = "";
    private String manager = "";
    private String city = "";
    private int openingHour = 9;

    public void addPropertyChangeListener(PropertyChangeListener listener) {
      support.addPropertyChangeListener(listener);
    }

    public void setName(String name) {
      var old = this.name;
      this.name = name;
      support.firePropertyChange("name", old, name);
    }

    public void setManager(String manager) {
      var old = this.manager;
      this.manager = manager;
      support.firePropertyChange("manager", old, manager);
    }

    public void setCity(String city) {
      var old = this.city;
      this.city = city;
      support.firePropertyChange("city", old, city);
    }

    public void setOpeningHour(int openingHour) {
      this.openingHour = openingHour;
    }
  }

  /// Two siblings fire: too few to say a third should.
  public static class Pair extends Bean {
    private String left = "";
    private String right = "";
    private String note = "";

    public void setLeft(String left) {
      var old = this.left;
      this.left = left;
      firePropertyChange("left", old, left);
    }

    public void setRight(String right) {
      var old = this.right;
      this.right = right;
      firePropertyChange("right", old, right);
    }

    public void setNote(String note) {
      this.note = note;
    }
  }

  /// Fires nothing at all: no peers, no ghosts.
  public static class Plain {
    private String name = "";

    public void setName(String name) {
      this.name = name;
    }

    public void rename(String name) {
      this.name = name.trim();
    }
  }
}
