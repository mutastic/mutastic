package com.example.arguments;

public final class Bookings {
  public record Booking(String guest, String room) {}

  public static Booking book(String guest, String room) {
    return new Booking(guest, room);
  }
}
