package com.professionalpos.accounting;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import com.professionalpos.service.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class AccountingService {
  private final Database db;

  public AccountingService(Database db) {
    this.db = db;
  }

  /**
   * Signed amount: positive debit, negative credit. Duplicate accounts are combined before posting.
   */
  public static final class Posting extends LinkedHashMap<String, BigDecimal> {
    public Posting add(String code, BigDecimal signed) {
      merge(code, signed, BigDecimal::add);
      return this;
    }

    public Posting mapped(Connection c, String key, BigDecimal signed) throws SQLException {
      return add(SettingsService.get(c, "map." + key), signed);
    }
  }

  public static long post(Connection c, User u, long documentId, Posting p, String description)
      throws SQLException {
    Row doc = Sql.one(c, "SELECT * FROM documents WHERE id=?", documentId);
    int scale = SettingsService.baseScale(c);
    check(
        Sql.count(
                c,
                "SELECT COUNT(*) FROM period_locks WHERE active=TRUE AND start_date<=? AND"
                    + " end_date>=?",
                doc.date("document_date"),
                doc.date("document_date"))
            == 0,
        "This accounting period is locked.");
    BigDecimal balance = ZERO;
    for (BigDecimal value : p.values()) {
      check(
          round(value, scale).compareTo(value) == 0,
          "Journal amounts must use base-currency precision.");
      balance = balance.add(value);
    }
    check(balance.signum() == 0, "Journal entry is not balanced.");
    long id =
        Sql.insert(
            c,
            "journal_entries",
            Sql.values(
                "number",
                Documents.next(c, "JOURNAL"),
                "document_id",
                documentId,
                "journal_date",
                doc.date("document_date"),
                "description",
                description,
                "created_by",
                u.id()));
    for (var line : p.entrySet()) {
      if (line.getValue().signum() == 0) continue;
      Row account =
          Sql.one(c, "SELECT * FROM accounts WHERE code=? AND active=TRUE", line.getKey());
      Sql.insert(
          c,
          "journal_lines",
          Sql.values(
              "entry_id",
              id,
              "account_id",
              account.id(),
              "debit",
              line.getValue().max(ZERO),
              "credit",
              line.getValue().negate().max(ZERO),
              "memo",
              doc.text("number")));
    }
    return id;
  }

  public long manual(User u, LocalDate date, String description, Posting p) {
    u.require(Permission.ACCOUNTING_POST);
    required(description, "Description");
    return db.tx(
        c -> {
          Set<String> controls = new HashSet<>();
          for (String k :
              List.of(
                  "ar",
                  "ap",
                  "inventory",
                  "cash",
                  "revenue",
                  "returns",
                  "cogs",
                  "tax",
                  "input_tax")) controls.add(SettingsService.get(c, "map." + k));
          for (String code : p.keySet())
            check(
                !controls.contains(code),
                "Use the customer, supplier, inventory or cash workflow for control accounts.");
          check(
              p.values().stream().filter(v -> v.signum() != 0).count() >= 2,
              "Enter at least two journal lines.");
          long id =
              Documents.create(
                  c,
                  u,
                  "MANUAL",
                  date,
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("notes", description));
          post(c, u, id, p, description);
          Audit.log(c, u, "JOURNAL_POSTED", "DOCUMENT", id, description);
          return id;
        });
  }

  public long reverse(User u, long journalId, LocalDate date, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    required(reason, "Reason");
    return db.tx(
        c -> {
          Row original =
              Sql.one(
                  c,
                  "SELECT j.*,d.kind FROM journal_entries j JOIN documents d ON d.id=j.document_id"
                      + " WHERE j.id=?",
                  journalId);
          check(
              original.text("kind").equals("MANUAL"),
              "Reverse business documents through their return or void workflow.");
          check(
              Sql.count(c, "SELECT COUNT(*) FROM journal_entries WHERE reversal_of=?", journalId)
                  == 0,
              "This journal has already been reversed.");
          Posting p = new Posting();
          for (Row l :
              Sql.rows(
                  c,
                  "SELECT a.code,l.debit,l.credit FROM journal_lines l JOIN accounts a ON"
                      + " a.id=l.account_id WHERE l.entry_id=?",
                  journalId)) p.add(l.text("code"), l.money("credit").subtract(l.money("debit")));
          long doc =
              Documents.create(
                  c,
                  u,
                  "REVERSAL",
                  date,
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("original_id", original.number("document_id"), "reason", reason));
          long posted = post(c, u, doc, p, reason);
          Sql.update(c, "UPDATE journal_entries SET reversal_of=? WHERE id=?", journalId, posted);
          Audit.log(c, u, "JOURNAL_REVERSED", "DOCUMENT", doc, reason);
          return doc;
        });
  }

  public void lock(User u, LocalDate from, LocalDate to, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    check(!to.isBefore(from), "Invalid date range.");
    db.tx(
        c -> {
          long id =
              Sql.insert(
                  c,
                  "period_locks",
                  Sql.values(
                      "start_date",
                      from,
                      "end_date",
                      to,
                      "locked_by",
                      u.id(),
                      "reason",
                      required(reason, "Reason")));
          Audit.log(c, u, "ACCOUNTING_PERIOD_LOCKED", "PERIOD", id, reason);
          return null;
        });
  }

  public void reopen(User u, long id, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    db.tx(
        c -> {
          check(
              Sql.count(
                      c,
                      "SELECT COUNT(*) FROM period_closures WHERE lock_id=? AND status='POSTED'",
                      id)
                  == 0,
              "Reverse the period close instead of reopening its lock.");
          Sql.update(c, "UPDATE period_locks SET active=FALSE WHERE id=?", id);
          Audit.log(c, u, "ACCOUNTING_PERIOD_REOPENED", "PERIOD", id, required(reason, "Reason"));
          return null;
        });
  }

  /** Closes revenue and expense balances to retained earnings, then locks the period. */
  public long closePeriod(User u, LocalDate from, LocalDate to, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    check(!to.isBefore(from), "Invalid date range.");
    required(reason, "Reason");
    return db.tx(
        c -> {
          check(
              Sql.count(
                      c,
                      "SELECT COUNT(*) FROM period_closures WHERE status='POSTED' AND NOT"
                          + " (end_date<? OR start_date>?)",
                      from,
                      to)
                  == 0,
              "This period overlaps an existing close.");
          check(
              Sql.count(
                      c,
                      "SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines GROUP BY entry_id"
                          + " HAVING SUM(debit)<>SUM(credit)) x")
                  == 0,
              "Unbalanced journals must be corrected before closing.");
          List<Row> balances =
              Sql.rows(
                  c,
                  "SELECT a.code,COALESCE(SUM(l.debit-l.credit),0) AS amount FROM accounts a JOIN"
                      + " journal_lines l ON l.account_id=a.id JOIN journal_entries j ON"
                      + " j.id=l.entry_id JOIN documents d ON d.id=j.document_id LEFT JOIN"
                      + " period_closures pc ON pc.document_id=d.id OR"
                      + " pc.reversed_by_document_id=d.id WHERE a.account_type IN"
                      + " ('REVENUE','EXPENSE') AND j.journal_date BETWEEN ? AND ? AND pc.id IS"
                      + " NULL GROUP BY a.code ORDER BY a.code",
                  from,
                  to);
          Posting posting = new Posting();
          BigDecimal total = ZERO;
          for (Row balance : balances) {
            BigDecimal amount = balance.money("amount");
            if (amount.signum() == 0) continue;
            posting.add(balance.text("code"), amount.negate());
            total = total.add(amount);
          }
          check(!posting.isEmpty(), "There are no income or expense balances to close.");
          posting.mapped(c, "retained_earnings", total);
          long doc =
              Documents.create(
                  c,
                  u,
                  "MANUAL",
                  to,
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("reason", reason));
          post(c, u, doc, posting, "Period close " + from + " to " + to);
          long lockId =
              Sql.insert(
                  c,
                  "period_locks",
                  Sql.values(
                      "start_date",
                      from,
                      "end_date",
                      to,
                      "locked_by",
                      u.id(),
                      "reason",
                      "Closed: " + reason));
          long closeId =
              Sql.insert(
                  c,
                  "period_closures",
                  Sql.values(
                      "start_date",
                      from,
                      "end_date",
                      to,
                      "document_id",
                      doc,
                      "lock_id",
                      lockId,
                      "status",
                      "POSTED",
                      "reason",
                      reason,
                      "created_by",
                      u.id()));
          Audit.log(c, u, "ACCOUNTING_PERIOD_CLOSED", "PERIOD_CLOSE", closeId, reason);
          return closeId;
        });
  }

  /** Controlled reversal of a period close; the linked lock is reopened atomically. */
  public long reverseClose(User u, long closeId, LocalDate date, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    required(reason, "Reason");
    return db.tx(
        c -> {
          Row close = Sql.one(c, "SELECT * FROM period_closures WHERE id=? FOR UPDATE", closeId);
          check(close.text("status").equals("POSTED"), "This period close is already reversed.");
          Row original =
              Sql.one(c, "SELECT id FROM journal_entries WHERE document_id=?", close.number("document_id"));
          Posting posting = new Posting();
          for (Row line :
              Sql.rows(
                  c,
                  "SELECT a.code,l.debit,l.credit FROM journal_lines l JOIN accounts a ON"
                      + " a.id=l.account_id WHERE l.entry_id=?",
                  original.id()))
            posting.add(line.text("code"), line.money("credit").subtract(line.money("debit")));
          long doc =
              Documents.create(
                  c,
                  u,
                  "REVERSAL",
                  date,
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("original_id", close.number("document_id"), "reason", reason));
          post(c, u, doc, posting, reason);
          Sql.update(c, "UPDATE period_locks SET active=FALSE WHERE id=?", close.number("lock_id"));
          Sql.update(
              c,
              "UPDATE period_closures SET status='REVERSED',reversed_by_document_id=? WHERE id=?",
              doc,
              closeId);
          Audit.log(c, u, "ACCOUNTING_PERIOD_CLOSE_REVERSED", "PERIOD_CLOSE", closeId, reason);
          return doc;
        });
  }

  public long account(User u, String code, String name, String ar, String type) {
    u.require(Permission.ACCOUNTING_POST);
    return db.tx(
        c -> {
          long id =
              Sql.insert(
                  c,
                  "accounts",
                  Sql.values(
                      "code",
                      required(code, "Code"),
                      "name",
                      required(name, "Name"),
                      "name_ar",
                      ar,
                      "account_type",
                      type));
          Audit.log(c, u, "ACCOUNT_CREATED", "ACCOUNT", id, "");
          return id;
        });
  }
}
