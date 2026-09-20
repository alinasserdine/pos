package com.professionalpos.service;

import com.professionalpos.db.Sql;
import com.professionalpos.model.User;
import java.sql.*;

public final class Audit {
  private Audit() {}

  public static void log(
      Connection c,
      User u,
      String action,
      String type,
      Long id,
      String oldValue,
      String newValue,
      String reason,
      Long approver)
      throws SQLException {
    Sql.insert(
        c,
        "audit_logs",
        Sql.values(
            "user_id",
            u == null ? null : u.id(),
            "action",
            action,
            "entity_type",
            type,
            "entity_id",
            id,
            "old_summary",
            oldValue,
            "new_summary",
            newValue,
            "reason",
            reason,
            "approved_by",
            approver));
  }

  public static void log(Connection c, User u, String action, String type, long id, String reason)
      throws SQLException {
    log(c, u, action, type, id, null, null, reason, null);
  }
}
