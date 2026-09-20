package com.professionalpos.util;

import java.math.*;

public final class Money {
  public static final BigDecimal ZERO = BigDecimal.ZERO, HUNDRED = new BigDecimal("100");

  private Money() {}

  public static BigDecimal of(String s) {
    try {
      return new BigDecimal(s.trim());
    } catch (Exception e) {
      throw new IllegalArgumentException("Enter a valid decimal number.");
    }
  }

  public static BigDecimal round(BigDecimal n, int scale) {
    return n.setScale(scale, RoundingMode.HALF_UP);
  }

  public static BigDecimal divide(BigDecimal n, BigDecimal d) {
    return n.divide(d, 12, RoundingMode.HALF_UP);
  }

  public static BigDecimal positive(BigDecimal n, String label) {
    if (n == null || n.signum() <= 0)
      throw new IllegalArgumentException(label + " must be greater than zero.");
    return n;
  }

  public static BigDecimal nonnegative(BigDecimal n, String label) {
    if (n == null || n.signum() < 0)
      throw new IllegalArgumentException(label + " cannot be negative.");
    return n;
  }

  public static void check(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  public static String required(String s, String label) {
    if (s == null || s.isBlank()) throw new IllegalArgumentException(label + " is required.");
    return s.trim();
  }
}
