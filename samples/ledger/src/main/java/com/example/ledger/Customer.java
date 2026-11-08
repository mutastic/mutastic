package com.example.ledger;

/// Who holds an account. Every setter tells listeners, but one.
public class Customer extends Bean {
  private String name;
  private String email = "";
  private String phone = "";
  private String address = "";

  public Customer(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    var old = this.name;
    this.name = name;
    firePropertyChange("name", old, name);
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    var old = this.email;
    this.email = email.trim();
    firePropertyChange("email", old, this.email);
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    var old = this.phone;
    this.phone = phone;
    firePropertyChange("phone", old, phone);
  }

  public String getAddress() {
    return address;
  }

  /// Forgets to tell anyone.
  public void setAddress(String address) {
    this.address = address;
  }
}
