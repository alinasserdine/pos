package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class AuthService {
  private final Database db;

  public AuthService(Database db) {
    this.db = db;
  }

  public boolean setupRequired() {
    return db.read(c -> Sql.count(c, "SELECT COUNT(*) FROM business_profile") == 0);
  }

  public User setup(Map<String, String> info, char[] password, char[] confirmation) {
    check(Arrays.equals(password, confirmation), "Passwords do not match.");
    String hash = Passwords.hash(password);
    return db.tx(
        c -> {
          check(
              Sql.count(c, "SELECT COUNT(*) FROM users") == 0, "Setup has already been completed.");
          String currency = required(info.get("currency"), "Currency").toUpperCase(Locale.ROOT);
          int scale = Integer.parseInt(info.getOrDefault("decimals", "2"));
          check(scale >= 0 && scale <= 6, "Currency decimals must be between 0 and 6.");
          if (Sql.optional(c, "SELECT * FROM currencies WHERE code=?", currency) == null)
            Sql.insert(
                c,
                "currencies",
                Sql.values(
                    "code",
                    currency,
                    "name",
                    info.getOrDefault("currency_name", currency),
                    "symbol",
                    info.getOrDefault("symbol", currency),
                    "decimal_places",
                    scale));
          else
            Sql.update(
                c,
                "UPDATE currencies SET decimal_places=?,symbol=? WHERE code=?",
                scale,
                info.getOrDefault("symbol", currency),
                currency);
          ZoneId.of(info.getOrDefault("time_zone", "Asia/Beirut"));
          Sql.insert(
              c,
              "business_profile",
              Sql.values(
                  "store_name",
                  required(info.get("store_name"), "Store name"),
                  "legal_name",
                  info.get("legal_name"),
                  "address",
                  info.get("address"),
                  "phone",
                  info.get("phone"),
                  "email",
                  info.get("email"),
                  "tax_number",
                  info.get("tax_number"),
                  "country",
                  info.get("country"),
                  "time_zone",
                  info.getOrDefault("time_zone", "Asia/Beirut"),
                  "footer",
                  info.getOrDefault("footer", "Thank you!"),
                  "logo_path",
                  info.get("logo_path")));
          SettingsService.put(c, "base_currency", currency);
          SettingsService.put(c, "language", info.getOrDefault("language", "en"));
          SettingsService.put(c, "time_zone", info.getOrDefault("time_zone", "Asia/Beirut"));
          String costing = info.getOrDefault("costing", "WAC");
          check(Set.of("WAC", "FIFO").contains(costing), "Invalid costing method.");
          SettingsService.put(c, "costing", costing);
          String tax = info.getOrDefault("tax_mode", "NONE");
          check(Set.of("NONE", "EXCLUSIVE", "INCLUSIVE").contains(tax), "Invalid tax mode.");
          SettingsService.put(c, "tax_mode", tax);
          var taxRate = of(info.getOrDefault("tax_rate", "0"));
          check(taxRate.signum() >= 0 && taxRate.compareTo(HUNDRED) <= 0, "Invalid tax rate.");
          if (taxRate.signum() > 0)
            Sql.insert(
                c,
                "tax_rates",
                Sql.values(
                    "name",
                    required(info.getOrDefault("tax_name", "VAT"), "Tax name"),
                    "rate",
                    taxRate));
          long role = Sql.one(c, "SELECT * FROM roles WHERE role_code='ADMIN'").id();
          long id =
              Sql.insert(
                  c,
                  "users",
                  Sql.values(
                      "username",
                      required(info.get("username"), "Username").toLowerCase(Locale.ROOT),
                      "full_name",
                      required(info.get("full_name"), "Full name"),
                      "password_hash",
                      hash,
                      "role_id",
                      role));
          User user = load(c, id);
          Audit.log(c, user, "SETUP_COMPLETED", "USER", id, "First-run configuration");
          return user;
        });
  }

  private User load(Connection c, long id) throws SQLException {
    Row r =
        Sql.one(
            c,
            "SELECT u.*,r.role_code FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=?",
            id);
    Set<Permission> perms = EnumSet.noneOf(Permission.class);
    for (Row p :
        Sql.rows(
            c, "SELECT permission_code FROM role_permissions WHERE role_id=?", r.number("role_id")))
      perms.add(Permission.valueOf(p.text("permission_code")));
    return new User(
        id,
        r.text("username"),
        r.text("full_name"),
        r.text("role_code"),
        perms,
        r.flag("must_change"));
  }

  public User login(String username, char[] password) {
    User result =
        db.tx(
            c -> {
              Row r =
                  Sql.optional(
                      c,
                      "SELECT * FROM users WHERE username=?",
                      username.trim().toLowerCase(Locale.ROOT));
              if (r == null) {
                Audit.log(c, null, "LOGIN_FAILED", "USER", 0, "Unknown username");
                return null;
              }
              var locked = r.get("locked_until");
              boolean blocked =
                  locked != null
                      && ((Timestamp) locked).toLocalDateTime().isAfter(LocalDateTime.now());
              if (!r.flag("active") || blocked) return null;
              if (!Passwords.verify(password, r.text("password_hash"))) {
                long attempts = r.number("failed_attempts") + 1;
                Sql.update(
                    c,
                    "UPDATE users SET failed_attempts=?,locked_until=? WHERE id=?",
                    attempts >= 5 ? 0 : attempts,
                    attempts >= 5 ? LocalDateTime.now().plusMinutes(5) : null,
                    r.id());
                Audit.log(c, null, "LOGIN_FAILED", "USER", r.id(), "Invalid credentials");
                return null;
              }
              Sql.update(
                  c,
                  "UPDATE users SET"
                      + " failed_attempts=0,locked_until=NULL,last_login=CURRENT_TIMESTAMP WHERE"
                      + " id=?",
                  r.id());
              User u = load(c, r.id());
              Audit.log(c, u, "LOGIN_SUCCESS", "USER", u.id(), "");
              return u;
            });
    if (result == null)
      throw new IllegalArgumentException(
          "Sign-in failed. Check your credentials or wait five minutes if the account is locked.");
    return result;
  }

  public User approve(String username, char[] password, Permission p) {
    User u = login(username, password);
    u.require(p);
    return u;
  }

  public void changePassword(User u, char[] old, char[] next) {
    String hash = Passwords.hash(next);
    db.tx(
        c -> {
          Row row = Sql.one(c, "SELECT * FROM users WHERE id=?", u.id());
          check(Passwords.verify(old, row.text("password_hash")), "Current password is incorrect.");
          Sql.update(
              c, "UPDATE users SET password_hash=?,must_change=FALSE WHERE id=?", hash, u.id());
          Audit.log(c, u, "PASSWORD_CHANGED", "USER", u.id(), "");
          return null;
        });
  }

  public long saveUser(
      User actor,
      Long id,
      String username,
      String name,
      long roleId,
      boolean active,
      char[] password) {
    actor.require(Permission.SETTINGS_USERS);
    String hash = password.length > 0 ? Passwords.hash(password) : null;
    return db.tx(
        c -> {
          Map<String, Object> values =
              Sql.values(
                  "username",
                  required(username, "Username").toLowerCase(Locale.ROOT),
                  "full_name",
                  required(name, "Name"),
                  "role_id",
                  roleId,
                  "active",
                  active);
          if (id != null) {
            Row old =
                Sql.one(
                    c,
                    "SELECT u.*,r.role_code FROM users u JOIN roles r ON r.id=u.role_id WHERE"
                        + " u.id=?",
                    id);
            String nextRole =
                Sql.one(c, "SELECT * FROM roles WHERE id=?", roleId).text("role_code");
            check(
                id != actor.id() || active && nextRole.equals("ADMIN"),
                "Keep your own administrator account active.");
            if (old.text("role_code").equals("ADMIN") && (!active || !nextRole.equals("ADMIN")))
              check(
                  Sql.count(
                          c,
                          "SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE"
                              + " r.role_code='ADMIN' AND u.active=TRUE AND u.id<>?",
                          id)
                      > 0,
                  "Keep at least one active administrator.");
            if (hash != null) {
              values.put("password_hash", hash);
              values.put("must_change", true);
              values.put("failed_attempts", 0);
              values.put("locked_until", null);
            }
            Sql.edit(c, "users", id, values);
            Audit.log(c, actor, hash == null ? "USER_UPDATED" : "PASSWORD_RESET", "USER", id, "");
            return id;
          }
          check(hash != null, "Password is required.");
          values.put("password_hash", hash);
          long created = Sql.insert(c, "users", values);
          Audit.log(c, actor, "USER_CREATED", "USER", created, "");
          return created;
        });
  }

  public void permissions(User u, long roleId, Set<Permission> permissions) {
    u.require(Permission.SETTINGS_USERS);
    db.tx(
        c -> {
          check(
              !Sql.one(c, "SELECT * FROM roles WHERE id=?", roleId)
                  .text("role_code")
                  .equals("ADMIN"),
              "Administrator permissions are protected.");
          Sql.update(c, "DELETE FROM role_permissions WHERE role_id=?", roleId);
          for (Permission p : permissions)
            Sql.update(
                c,
                "INSERT INTO role_permissions(role_id,permission_code) VALUES (?,?)",
                roleId,
                p.name());
          Audit.log(c, u, "PERMISSIONS_CHANGED", "ROLE", roleId, "Takes effect at next login");
          return null;
        });
  }

  public long role(User u, String name) {
    u.require(Permission.SETTINGS_USERS);
    return db.tx(
        c ->
            Sql.insert(
                c,
                "roles",
                Sql.values(
                    "role_code",
                    required(name, "Role").toUpperCase(Locale.ROOT).replace(' ', '_'),
                    "name",
                    name)));
  }

  public void logout(User u) {
    db.tx(
        c -> {
          Audit.log(c, u, "LOGOUT", "USER", u.id(), "");
          return null;
        });
  }
}
