package com.example.ledger;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/// A bank branch, which keeps its own listeners rather than sharing [Bean]'s.
public class Branch {
  private final PropertyChangeSupport support = new PropertyChangeSupport(this);
  private String name;
  private String manager = "";
  private String city = "";
  private int openingHour = 9;

  public Branch(String name) {
    this.name = name;
  }

  public void addPropertyChangeListener(PropertyChangeListener listener) {
    support.addPropertyChangeListener(listener);
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    var old = this.name;
    this.name = name;
    support.firePropertyChange("name", old, name);
  }

  public String getManager() {
    return manager;
  }

  public void setManager(String manager) {
    var old = this.manager;
    this.manager = manager;
    support.firePropertyChange("manager", old, manager);
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    var old = this.city;
    this.city = city;
    support.firePropertyChange("city", old, city);
  }

  public int getOpeningHour() {
    return openingHour;
  }

  public void setOpeningHour(int openingHour) {
    if (openingHour < 0 || openingHour > 23) {
      throw new IllegalArgumentException("an hour of the day");
    }
    this.openingHour = openingHour;
  }
}
