package com.professionalpos.service;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class NotificationService {
  private final Database db;

  public NotificationService(Database db) {
    this.db = db;
  }

  public void refresh() {
    db.tx(
        c -> {
          Sql.update(c, "UPDATE notifications SET resolved=TRUE");
          for (Row p :
              Sql.rows(
                  c,
                  "SELECT * FROM products WHERE active=TRUE AND track_stock=TRUE AND"
                      + " on_hand<=reorder_level"))
            upsert(
                c,
                "STOCK-" + p.id(),
                "STOCK",
                p.id(),
                p.text("name") + ": " + p.money("on_hand") + " remaining");
          LocalDate today = SettingsService.today(c),
              soon = today.plusDays(Integer.parseInt(SettingsService.get(c, "due_soon_days")));
          for (Row e :
              Sql.rows(
                  c,
                  "SELECT e.*,p.name,p.kind,d.number FROM party_entries e JOIN parties p ON"
                      + " p.id=e.party_id JOIN documents d ON d.id=e.document_id WHERE"
                      + " e.open_amount>0 AND (e.due_date<=? OR e.reminder_date<=?)",
                  soon,
                  today))
            upsert(
                c,
                "DEBT-" + e.id(),
                "DEBT",
                e.id(),
                e.text("name")
                    + " · "
                    + e.text("number")
                    + " · "
                    + e.money("open_amount")
                    + " "
                    + e.text("currency_code")
                    + " · due "
                    + e.date("due_date"));
          for (Row s :
              Sql.rows(
                  c,
                  "SELECT * FROM cash_sessions WHERE difference<>0 ORDER BY id DESC FETCH FIRST 10"
                      + " ROWS ONLY"))
            upsert(
                c,
                "CASH-" + s.id(),
                "CASH",
                s.id(),
                "Cash session " + s.id() + ": difference " + s.money("difference"));
          try (var files = Files.list(db.home().resolve("backups"))) {
            long latest =
                files
                    .filter(p -> p.toString().endsWith(".zip"))
                    .mapToLong(
                        p -> {
                          try {
                            return Files.getLastModifiedTime(p).toMillis();
                          } catch (Exception ex) {
                            return 0;
                          }
                        })
                    .max()
                    .orElse(0);
            if (latest < System.currentTimeMillis() - Duration.ofDays(2).toMillis())
              upsert(c, "BACKUP-STALE", "BACKUP", 0, "No backup in the last two days.");
          }
          return null;
        });
  }

  private static void upsert(
      java.sql.Connection c, String key, String kind, long id, String message) throws Exception {
    Row old = Sql.optional(c, "SELECT * FROM notifications WHERE notification_key=?", key);
    if (old == null)
      Sql.insert(
          c,
          "notifications",
          Sql.values("notification_key", key, "kind", kind, "entity_id", id, "message", message));
    else
      Sql.update(
          c, "UPDATE notifications SET resolved=FALSE,message=? WHERE id=?", message, old.id());
  }

  public void read(User u, long id) {
    u.require(Permission.REPORT_SALES);
    db.tx(
        c -> {
          if (Sql.count(
                  c,
                  "SELECT COUNT(*) FROM notification_reads WHERE notification_id=? AND user_id=?",
                  id,
                  u.id())
              == 0)
            Sql.update(
                c,
                "INSERT INTO notification_reads(notification_id,user_id) VALUES (?,?)",
                id,
                u.id());
          return null;
        });
  }
}
