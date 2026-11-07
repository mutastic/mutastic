package com.example.orders;

import org.springframework.security.access.prepost.PreAuthorize;

/// A test that's refused never runs a line of the method, so coverage can't say which test that is.
public class Payroll {
  @PreAuthorize("hasRole('ADMIN')")
  public String pay() {
    return "paid";
  }

  @PreAuthorize("hasRole('ADMIN')")
  public String audit() {
    return "audited";
  }
}
