package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.model.*;
import com.professionalpos.security.Permission;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class CashService {
  private final Database db;

  public CashService(Database db) {
    this.db = db;
  }

  public static Long session(Connection c, User u) throws SQLException {
    Row r =
        Sql.optional(
            c, "SELECT * FROM cash_sessions WHERE user_id=? AND closed_at IS NULL", u.id());
    return r == null ? null : r.id();
  }

  public Row getSession(User u, long id) {
    u.require(Permission.CASH_SESSION);
    return db.read(
        c -> {
          Row row =
              Sql.one(
                  c,
                  "SELECT s.*,s.opening_cash+COALESCE((SELECT SUM(m.amount) FROM cash_movements m"
                      + " WHERE m.session_id=s.id),0) AS expected FROM cash_sessions s WHERE s.id=?"
                      + " AND s.closed_at IS NULL",
                  id);
          check(
              row.number("user_id") == u.id() || u.can(Permission.ACCOUNTING_POST),
              "This is another user's session.");
          return row;
        });
  }

  public Row current(User u) {
    u.require(Permission.CASH_SESSION);
    return db.read(
        c ->
            Sql.optional(
                c,
                "SELECT s.*,s.opening_cash+COALESCE((SELECT SUM(m.amount) FROM cash_movements m"
                    + " WHERE m.session_id=s.id),0) AS expected FROM cash_sessions s WHERE"
                    + " s.user_id=? AND s.closed_at IS NULL",
                u.id()));
  }

  public long open(User u, BigDecimal amount, String reason) {
    u.require(Permission.CASH_SESSION);
    nonnegative(amount, "Opening cash");
    return db.tx(
        c -> {
          check(
              Sql.count(c, "SELECT COUNT(*) FROM cash_sessions WHERE closed_at IS NULL") == 0,
              "Close the existing cash session first.");
          int scale = SettingsService.baseScale(c);
          BigDecimal opening = round(amount, scale);
          String cash = SettingsService.get(c, "map.cash");
          BigDecimal balance =
              Sql.one(
                      c,
                      "SELECT COALESCE(SUM(l.debit-l.credit),0) AS balance FROM journal_lines l"
                          + " JOIN accounts a ON a.id=l.account_id WHERE a.code=?",
                      cash)
                  .money("balance");
          boolean first = Sql.count(c, "SELECT COUNT(*) FROM cash_sessions") == 0;
          BigDecimal delta = opening.subtract(balance);
          if (delta.signum() != 0) {
            u.require(Permission.ACCOUNTING_POST);
            if (!first) required(reason, "Opening difference reason");
            long doc =
                Documents.create(
                    c,
                    u,
                    "OPENING",
                    SettingsService.today(c),
                    SettingsService.get(c, "base_currency"),
                    Documents.key(),
                    null,
                    Sql.values("reason", first ? "Opening cash" : reason));
            AccountingService.post(
                c,
                u,
                doc,
                new Posting()
                    .mapped(c, "cash", delta)
                    .mapped(c, first ? "capital" : "cash_difference", delta.negate()),
                "Opening cash reconciliation");
          }
          long id =
              Sql.insert(
                  c,
                  "cash_sessions",
                  Sql.values("user_id", u.id(), "opening_cash", opening, "notes", reason));
          Audit.log(c, u, "CASH_SESSION_OPENED", "SESSION", id, "Opening " + opening);
          return id;
        });
  }

  public static void movement(
      Connection c, User u, long session, long doc, String type, BigDecimal amount, String reason)
      throws SQLException {
    Sql.insert(
        c,
        "cash_movements",
        Sql.values(
            "session_id",
            session,
            "document_id",
            doc,
            "movement_type",
            type,
            "amount",
            amount,
            "reason",
            reason,
            "created_by",
            u.id()));
  }

  public static void payment(
      Connection c,
      User u,
      long doc,
      String currency,
      BigDecimal rate,
      Trade.Payment pay,
      int direction,
      BigDecimal base,
      Long session)
      throws SQLException {
    Row method =
        Sql.one(c, "SELECT * FROM payment_methods WHERE code=? AND active=TRUE", pay.method());
    positive(pay.amount(), "Payment");
    check(pay.tendered().compareTo(pay.amount()) >= 0, "Tendered amount is less than the payment.");
    if (!method.flag("is_cash"))
      check(
          pay.tendered().compareTo(pay.amount()) == 0,
          "Change is supported only for cash payments.");
    if (method.flag("is_cash"))
      check(session != null, "Open a cash session before taking or refunding cash.");
    Sql.insert(
        c,
        "payments",
        Sql.values(
            "document_id",
            doc,
            "method_code",
            pay.method(),
            "currency_code",
            currency,
            "rate_to_base",
            rate,
            "amount",
            pay.amount(),
            "base_amount",
            base,
            "tendered",
            pay.tendered(),
            "change_amount",
            pay.tendered().subtract(pay.amount()),
            "direction",
            direction,
            "reference",
            pay.reference(),
            "cash_session_id",
            method.flag("is_cash") ? session : null));
    if (method.flag("is_cash"))
      movement(
          c,
          u,
          session,
          doc,
          direction > 0 ? "RECEIPT" : "DISBURSEMENT",
          base.multiply(BigDecimal.valueOf(direction)),
          pay.reference());
  }

  public long moveCash(User u, boolean in, BigDecimal amount, String offset, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    positive(amount, "Amount");
    required(reason, "Reason");
    return db.tx(
        c -> {
          Long session = session(c, u);
          check(session != null, "Open a cash session first.");
          check(
              Set.of("bank", "capital", "drawings", "cash_difference").contains(offset),
              "Choose a valid cash movement category.");
          BigDecimal value = round(amount, SettingsService.baseScale(c)),
              signed = in ? value : value.negate();
          long doc =
              Documents.create(
                  c,
                  u,
                  in ? "CASH_IN" : "CASH_OUT",
                  SettingsService.today(c),
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values(
                      "reason",
                      reason,
                      "total",
                      value,
                      "base_total",
                      value,
                      "cash_session_id",
                      session));
          movement(c, u, session, doc, in ? "CASH_IN" : "CASH_OUT", signed, reason);
          AccountingService.post(
              c,
              u,
              doc,
              new Posting().mapped(c, "cash", signed).mapped(c, offset, signed.negate()),
              reason);
          Audit.log(c, u, in ? "CASH_IN" : "CASH_OUT", "DOCUMENT", doc, reason);
          return doc;
        });
  }

  /** Transfers a processor settlement from card clearing to bank and recognizes its fees. */
  public long settleCard(
      User u,
      LocalDate date,
      BigDecimal gross,
      BigDecimal feeNet,
      BigDecimal feeTax,
      String reference,
      String requestKey) {
    u.require(Permission.ACCOUNTING_POST);
    positive(gross, "Gross settlement");
    nonnegative(feeNet, "Card fee");
    nonnegative(feeTax, "Card fee tax");
    return db.tx(
        c -> {
          Row prior = Sql.optional(c, "SELECT id FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          int scale = SettingsService.baseScale(c);
          BigDecimal roundedGross = round(gross, scale),
              roundedFee = round(feeNet, scale),
              roundedTax = round(feeTax, scale),
              net = roundedGross.subtract(roundedFee).subtract(roundedTax);
          check(net.signum() >= 0, "Card fees cannot exceed the settlement.");
          String clearing = SettingsService.get(c, "map.card_clearing");
          BigDecimal available =
              Sql.one(
                      c,
                      "SELECT COALESCE(SUM(l.debit-l.credit),0) AS amount FROM journal_lines l"
                          + " JOIN accounts a ON a.id=l.account_id WHERE a.code=?",
                      clearing)
                  .money("amount");
          check(roundedGross.compareTo(available) <= 0, "Settlement exceeds card clearing balance.");
          long doc =
              Documents.create(
                  c,
                  u,
                  "MANUAL",
                  date,
                  SettingsService.get(c, "base_currency"),
                  requestKey,
                  null,
                  Sql.values(
                      "total",
                      roundedGross,
                      "base_total",
                      roundedGross,
                      "reference",
                      reference,
                      "reason",
                      "Card settlement"));
          Posting posting =
              new Posting()
                  .mapped(c, "bank", net)
                  .mapped(c, "card_clearing", roundedGross.negate());
          if (roundedFee.signum() != 0) posting.mapped(c, "card_fee", roundedFee);
          if (roundedTax.signum() != 0) posting.mapped(c, "input_tax", roundedTax);
          AccountingService.post(c, u, doc, posting, "Card settlement " + reference);
          Sql.insert(
              c,
              "card_settlements",
              Sql.values(
                  "document_id",
                  doc,
                  "settlement_date",
                  date,
                  "gross_amount",
                  roundedGross,
                  "fee_net",
                  roundedFee,
                  "fee_tax",
                  roundedTax,
                  "net_deposit",
                  net,
                  "reference",
                  reference,
                  "created_by",
                  u.id()));
          Audit.log(c, u, "CARD_SETTLED", "DOCUMENT", doc, reference);
          return doc;
        });
  }

  public void close(User u, long id, BigDecimal counted, String reason) {
    u.require(Permission.CASH_SESSION);
    nonnegative(counted, "Counted cash");
    db.tx(
        c -> {
          Row s = Sql.one(c, "SELECT * FROM cash_sessions WHERE id=? AND closed_at IS NULL", id);
          check(
              s.number("user_id") == u.id() || u.can(Permission.ACCOUNTING_POST),
              "This is another user's session.");
          BigDecimal
              expected =
                  s.money("opening_cash")
                      .add(
                          Sql.one(
                                  c,
                                  "SELECT COALESCE(SUM(amount),0) AS amount FROM cash_movements"
                                      + " WHERE session_id=?",
                                  id)
                              .money("amount")),
              actual = round(counted, SettingsService.baseScale(c)),
              difference = actual.subtract(expected);
          if (difference.abs().compareTo(of(SettingsService.get(c, "cash_tolerance"))) > 0)
            required(reason, "Cash difference explanation");
          if (difference.signum() != 0) {
            long doc =
                Documents.create(
                    c,
                    u,
                    "ADJUSTMENT",
                    SettingsService.today(c),
                    SettingsService.get(c, "base_currency"),
                    Documents.key(),
                    null,
                    Sql.values("reason", "Cash session difference: " + reason));
            AccountingService.post(
                c,
                u,
                doc,
                new Posting()
                    .mapped(c, "cash", difference)
                    .mapped(c, "cash_difference", difference.negate()),
                "Cash session closing difference");
          }
          Sql.update(
              c,
              "UPDATE cash_sessions SET"
                  + " closed_at=CURRENT_TIMESTAMP,expected_cash=?,counted_cash=?,difference=?,notes=?"
                  + " WHERE id=?",
              expected,
              actual,
              difference,
              reason,
              id);
          Audit.log(
              c,
              u,
              "CASH_SESSION_CLOSED",
              "SESSION",
              id,
              "Difference " + difference + ". " + reason);
          return null;
        });
  }
}
