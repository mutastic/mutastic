package com.example.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HoursController {
  private final OpeningHours hours;

  public HoursController(OpeningHours hours) {
    this.hours = hours;
  }

  @GetMapping("/open")
  public String open() {
    return hours.isOpen() ? "open" : "closed";
  }
}
