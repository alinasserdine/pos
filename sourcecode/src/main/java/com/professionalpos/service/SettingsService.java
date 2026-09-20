package com.professionalpos.service;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class SettingsService {
  private final Database db;

  public SettingsService(Database db) {
    this.db = db;
  }

  public static String get(Connection c, String key) throws SQLException {
    return Sql.one(c, "SELECT setting_value FROM app_settings WHERE setting_key=?", key)
        .text("setting_value");
  }

  public static boolean yes(Connection c, String key) throws SQLException {
    return Boolean.parseBoolean(get(c, key));
  }

  public static int baseScale(Connection c) throws SQLException {
    return scale(c, get(c, "base_currency"));
  }

  public static int scale(Connection c, String code) throws SQLException {
    return (int)
        Sql.one(c, "SELECT decimal_places FROM currencies WHERE code=?", code)
            .number("decimal_places");
  }

  public static LocalDate today(Connection c) throws SQLException {
    return LocalDate.now(ZoneId.of(get(c, "time_zone")));
  }

  public static void put(Connection c, String key, String value) throws SQLException {
    if (Sql.update(c, "UPDATE app_settings SET setting_value=? WHERE setting_key=?", value, key)
        == 0)
      Sql.update(c, "INSERT INTO app_settings(setting_key,setting_value) VALUES (?,?)", key, value);
  }

  public Map<String, String> all() {
    return db.read(
        c -> {
          Map<String, String> r = new LinkedHashMap<>();
          for (Row row : Sql.rows(c, "SELECT * FROM app_settings ORDER BY setting_key"))
            r.put(row.text("setting_key"), row.text("setting_value"));
          return r;
        });
  }

  public void save(User u, Map<String, String> values) {
    u.require(Permission.SETTINGS_GENERAL);
    db.tx(
        c -> {
          Set<String> booleanKeys =
              Set.of(
                  "allow_negative",
                  "allow_credit",
                  "enable_hold",
                  "enable_split",
                  "auto_print",
                  "backup_exit",
                  "backup_daily",
                  "refund_approval");
          for (var e : values.entrySet()) {
            String k = e.getKey(), v = e.getValue().trim(), old = get(c, k);
            if (!old.equals(v) && Set.of("base_currency", "secondary_currency").contains(k))
              u.require(Permission.SETTINGS_CURRENCY);
            if (!old.equals(v) && k.equals("tax_mode")) u.require(Permission.SETTINGS_TAX);
            if (k.startsWith("map.")) {
              u.require(Permission.ACCOUNTING_POST);
              String expected =
                  Sql.one(c, "SELECT account_type FROM accounts WHERE code=?", old)
                      .text("account_type");
              Row a = Sql.one(c, "SELECT * FROM accounts WHERE code=?", v);
              if (!a.flag("active") || !a.text("account_type").equals(expected))
                throw new IllegalArgumentException("Account type does not match.");
              if (Sql.count(c, "SELECT COUNT(*) FROM documents") > 0 && !old.equals(v))
                throw new IllegalArgumentException(
                    "Account mappings are locked after posting transactions.");
            }
            if (Set.of("base_currency", "costing").contains(k)
                && Sql.count(c, "SELECT COUNT(*) FROM documents") > 0
                && !old.equals(v))
              throw new IllegalArgumentException(
                  "Base currency and costing are locked after the first transaction.");
            if (k.equals("base_currency"))
              Sql.one(c, "SELECT * FROM currencies WHERE code=? AND active=TRUE", v);
            if (k.equals("secondary_currency") && !v.isBlank())
              CurrencyService.rate(c, v, today(c));
            if (k.equals("costing") && !Set.of("WAC", "FIFO").contains(v))
              throw new IllegalArgumentException("Choose WAC or FIFO.");
            if (k.equals("tax_mode") && !Set.of("NONE", "EXCLUSIVE", "INCLUSIVE").contains(v))
              throw new IllegalArgumentException("Choose a valid tax mode.");
            if (k.equals("price_override")
                && !Set.of("NEVER", "PERMISSION", "APPROVAL", "ALWAYS").contains(v))
              throw new IllegalArgumentException("Choose a valid override policy.");
            if (k.equals("language") && !Set.of("en", "ar").contains(v))
              throw new IllegalArgumentException("Choose English or Arabic.");
            if (k.equals("time_zone")) ZoneId.of(v);
            if (booleanKeys.contains(k) && !Set.of("true", "false").contains(v))
              throw new IllegalArgumentException("Choose true or false.");
            if (Set.of("max_cashier_discount", "cash_tolerance").contains(k)) {
              var n = com.professionalpos.util.Money.of(v);
              if (n.signum() < 0
                  || k.equals("max_cashier_discount")
                      && n.compareTo(new java.math.BigDecimal("100")) > 0)
                throw new IllegalArgumentException("Invalid limit.");
            }
            if (Set.of("backup_keep", "due_soon_days", "session_minutes").contains(k)) {
              int n = Integer.parseInt(v);
              if (n < 1 || n > 365)
                throw new IllegalArgumentException("Enter a value from 1 to 365.");
            }
            if (k.equals("receipt_width") && !Set.of("58", "80", "A4").contains(v))
              throw new IllegalArgumentException("Choose 58, 80 or A4.");
            if (k.equals("receipt_language") && !Set.of("en", "ar", "both").contains(v))
              throw new IllegalArgumentException("Choose English, Arabic or both.");
            if (k.equals("map.cash"))
              Sql.update(c, "UPDATE payment_methods SET account_code=? WHERE code='CASH'", v);
            if (k.equals("map.bank"))
              Sql.update(
                  c,
                  "UPDATE payment_methods SET account_code=? WHERE code IN ('BANK','OTHER')",
                  v);
            if (k.equals("map.card_clearing"))
              Sql.update(c, "UPDATE payment_methods SET account_code=? WHERE code='CARD'", v);
            if (k.equals("invoice_prefix") && !v.matches("[A-Za-z][A-Za-z0-9-]{0,14}"))
              throw new IllegalArgumentException(
                  "Use a short invoice prefix containing letters, digits or hyphens.");
            put(c, k, v);
            Audit.log(c, u, "SETTING_CHANGED", "SETTING", null, old, v, k, null);
          }
          return null;
        });
  }
}
