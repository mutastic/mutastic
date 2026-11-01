package com.example.observable;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/// A bean whose setters tell listeners what changed. Every write to `name` should go through
/// `setName`, so listeners hear of it.
public class Person {
  private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
  private String name = "";
  private String email = "";
  private String nickname = "";

  public void addPropertyChangeListener(PropertyChangeListener listener) {
    changes.addPropertyChangeListener(listener);
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    var old = this.name;
    this.name = name;
    changes.firePropertyChange("name", old, name);
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    var old = this.email;
    this.email = email;
    changes.firePropertyChange("email", old, email);
  }

  public String getNickname() {
    return nickname;
  }

  /// Not bound: no listener hears of it, by design.
  public void setNickname(String nickname) {
    this.nickname = nickname;
  }

  /// Writes `name` without telling anyone, where `setName` would: the omission no mutant of the
  /// existing code can show, because there is nothing here to change. A ghost (an inserted
  /// `firePropertyChange`) would.
  public void rename(String first, String last) {
    this.name = first + " " + last;
  }
}
