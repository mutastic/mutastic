package com.example.ledger;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/// What every bean in the ledger shares: listeners, and a way to tell them what changed.
public abstract class Bean {
  protected final PropertyChangeSupport changes = new PropertyChangeSupport(this);

  public void addPropertyChangeListener(PropertyChangeListener listener) {
    changes.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(PropertyChangeListener listener) {
    changes.removePropertyChangeListener(listener);
  }

  protected void firePropertyChange(String property, Object old, Object now) {
    changes.firePropertyChange(property, old, now);
  }
}
