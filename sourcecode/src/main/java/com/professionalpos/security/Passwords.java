package com.professionalpos.security;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
  private static final int ITERATIONS = 600_000;

  private Passwords() {}

  public static String hash(char[] password) {
    if (password.length < 8)
      throw new IllegalArgumentException("Use at least 8 characters for the password.");
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return ITERATIONS
        + ":"
        + Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
  }

  public static boolean verify(char[] password, String encoded) {
    try {
      String[] p = encoded.split(":");
      int iterations = Integer.parseInt(p[0]);
      if (iterations < 100_000 || iterations > 2_000_000) return false;
      return MessageDigest.isEqual(
          Base64.getDecoder().decode(p[2]),
          derive(password, Base64.getDecoder().decode(p[1]), iterations));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static byte[] derive(char[] p, byte[] salt, int count) {
    PBEKeySpec spec = new PBEKeySpec(p, salt, count, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }
}
