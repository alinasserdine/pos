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

public final class DebtService {
  private final Database db;

  public DebtService(Database db) {
    this.db = db;
  }

  public static BigDecimal balance(Connection c, long party) throws SQLException {
    return Sql.one(
            c,
            "SELECT COALESCE(SUM(open_base),0) AS balance FROM party_entries WHERE party_id=?",
            party)
        .money("balance");
  }

  public static long entry(
      Connection c,
      long party,
      long doc,
      String currency,
      BigDecimal amount,
      BigDecimal base,
      LocalDate due)
      throws SQLException {
    return Sql.insert(
        c,
        "party_entries",
        Sql.values(
            "party_id",
            party,
            "document_id",
            doc,
            "currency_code",
            currency,
            "amount",
            amount,
            "base_amount",
            base,
            "open_amount",
            amount,
            "open_base",
            base,
            "due_date",
            due,
            "reminder_date",
            due));
  }

  /**
   * Allocate only within the same original currency. Historical carrying values are consumed
   * proportionally.
   */
  public static void allocate(
      Connection c,
      User u,
      long party,
      long postingDoc,
      String currency,
      Posting p,
      List<Long> targets)
      throws SQLException {
    boolean customer =
        Sql.one(c, "SELECT * FROM parties WHERE id=?", party).text("kind").equals("CUSTOMER");
    int scale = SettingsService.baseScale(c);
    BigDecimal totalFx = ZERO;
    List<Row> credits =
        Sql.rows(
            c,
            "SELECT * FROM party_entries WHERE party_id=? AND currency_code=? AND open_amount<0"
                + " ORDER BY id FOR UPDATE",
            party,
            currency);
    for (Row credit : credits) {
      BigDecimal left = credit.money("open_amount").negate(),
          leftBase = credit.money("open_base").negate();
      List<Row> charges =
          Sql.rows(
              c,
              "SELECT * FROM party_entries WHERE party_id=? AND currency_code=? AND open_amount>0"
                  + " ORDER BY due_date NULLS LAST,id FOR UPDATE",
              party,
              currency);
      if (!targets.isEmpty())
        charges.sort(
            Comparator.comparingInt(
                r -> {
                  int i = targets.indexOf(r.id());
                  return i < 0 ? Integer.MAX_VALUE : i;
                }));
      for (Row charge : charges) {
        if (left.signum() == 0) break;
        if (!targets.isEmpty() && !targets.contains(charge.id())) continue;
        BigDecimal take = left.min(charge.money("open_amount"));
        BigDecimal historic =
            take.compareTo(charge.money("open_amount")) == 0
                ? charge.money("open_base")
                : round(
                    divide(charge.money("open_base").multiply(take), charge.money("open_amount")),
                    scale);
        BigDecimal settlement =
            take.compareTo(left) == 0
                ? leftBase
                : round(divide(leftBase.multiply(take), left), scale);
        BigDecimal fx = settlement.subtract(historic);
        Sql.update(
            c,
            "UPDATE party_entries SET open_amount=open_amount-?,open_base=open_base-? WHERE id=?",
            take,
            historic,
            charge.id());
        Sql.insert(
            c,
            "allocations",
            Sql.values(
                "settlement_entry_id",
                credit.id(),
                "invoice_entry_id",
                charge.id(),
                "amount",
                take,
                "historical_base",
                historic,
                "settlement_base",
                settlement,
                "fx_difference",
                fx));
        left = left.subtract(take);
        leftBase = leftBase.subtract(settlement);
        totalFx = totalFx.add(fx);
      }
      Sql.update(
          c,
          "UPDATE party_entries SET open_amount=?,open_base=? WHERE id=?",
          left.negate(),
          leftBase.negate(),
          credit.id());
    }
    if (totalFx.signum() != 0) {
      p.mapped(c, customer ? "ar" : "ap", customer ? totalFx : totalFx.negate());
      BigDecimal profit = customer ? totalFx : totalFx.negate();
      p.mapped(c, profit.signum() > 0 ? "fx_gain" : "fx_loss", profit.negate());
      Sql.insert(
          c,
          "party_entries",
          Sql.values(
              "party_id",
              party,
              "document_id",
              postingDoc,
              "currency_code",
              currency,
              "amount",
              ZERO,
              "base_amount",
              totalFx,
              "open_amount",
              ZERO,
              "open_base",
              ZERO));
      Audit.log(
          c,
          u,
          "FX_REALIZED",
          "DOCUMENT",
          postingDoc,
          "Settlement less carrying value: " + totalFx);
    }
  }

  public long payment(
      User u,
      long party,
      LocalDate date,
      String currency,
      BigDecimal amount,
      String method,
      String reference,
      List<Long> allocations,
      String requestKey) {
    positive(amount, "Payment");
    return db.tx(
        c -> {
          Row partyRow = Sql.one(c, "SELECT * FROM parties WHERE id=? AND active=TRUE", party);
          boolean customer = partyRow.text("kind").equals("CUSTOMER");
          u.require(customer ? Permission.DEBT_RECEIVE : Permission.DEBT_PAY);
          Row prior = Sql.optional(c, "SELECT * FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          BigDecimal rate = CurrencyService.rate(c, currency, date),
              rounded = round(amount, SettingsService.scale(c, currency)),
              base = round(rounded.multiply(rate), SettingsService.baseScale(c));
          positive(rounded, "Payment");
          for (long target : allocations)
            check(
                Sql.count(
                        c,
                        "SELECT COUNT(*) FROM party_entries WHERE id=? AND party_id=? AND"
                            + " currency_code=? AND open_amount>0",
                        target,
                        party,
                        currency)
                    == 1,
                "Allocation must refer to an outstanding invoice in this currency.");
          Long session = CashService.session(c, u);
          long doc =
              Documents.create(
                  c,
                  u,
                  customer ? "CUSTOMER_PAYMENT" : "SUPPLIER_PAYMENT",
                  date,
                  currency,
                  requestKey,
                  party,
                  Sql.values(
                      "total",
                      rounded,
                      "base_total",
                      base,
                      "reference",
                      reference,
                      "cash_session_id",
                      session));
          CashService.payment(
              c,
              u,
              doc,
              currency,
              rate,
              new Trade.Payment(method, rounded, rounded, reference),
              customer ? 1 : -1,
              base,
              session);
          entry(c, party, doc, currency, rounded.negate(), base.negate(), null);
          Posting p =
              new Posting()
                  .add(
                      Sql.one(c, "SELECT * FROM payment_methods WHERE code=?", method)
                          .text("account_code"),
                      customer ? base : base.negate())
                  .mapped(c, customer ? "ar" : "ap", customer ? base.negate() : base);
          allocate(c, u, party, doc, currency, p, allocations);
          AccountingService.post(c, u, doc, p, customer ? "Customer payment" : "Supplier payment");
          Audit.log(c, u, "PARTY_PAYMENT", "DOCUMENT", doc, reference);
          return doc;
        });
  }

  /** Settle an unapplied credit by paying the customer or receiving a supplier refund. */
  public long refundCredit(
      User u,
      long party,
      LocalDate date,
      String currency,
      BigDecimal amount,
      String method,
      String reference,
      String requestKey) {
    positive(amount, "Refund");
    return db.tx(
        c -> {
          Row partyRow = Sql.one(c, "SELECT * FROM parties WHERE id=? AND active=TRUE", party);
          boolean customer = partyRow.text("kind").equals("CUSTOMER");
          u.require(customer ? Permission.DEBT_RECEIVE : Permission.DEBT_PAY);
          if (customer && method.equals("CASH")) u.require(Permission.REFUND_CASH);
          Row prior = Sql.optional(c, "SELECT * FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          BigDecimal available =
              Sql.one(
                      c,
                      "SELECT COALESCE(SUM(-open_amount),0) AS amount FROM party_entries WHERE"
                          + " party_id=? AND currency_code=? AND open_amount<0",
                      party,
                      currency)
                  .money("amount");
          BigDecimal rounded = round(amount, SettingsService.scale(c, currency));
          check(
              rounded.compareTo(available) <= 0,
              "Refund exceeds the available unapplied credit.");
          BigDecimal rate = CurrencyService.rate(c, currency, date),
              base = round(rounded.multiply(rate), SettingsService.baseScale(c));
          Long session = CashService.session(c, u);
          long doc =
              Documents.create(
                  c,
                  u,
                  customer ? "CASH_OUT" : "CASH_IN",
                  date,
                  currency,
                  requestKey,
                  party,
                  Sql.values(
                      "total",
                      rounded,
                      "base_total",
                      base,
                      "reference",
                      reference,
                      "reason",
                      customer ? "Customer credit refund" : "Supplier credit refund",
                      "cash_session_id",
                      session));
          int direction = customer ? -1 : 1;
          CashService.payment(
              c,
              u,
              doc,
              currency,
              rate,
              new Trade.Payment(method, rounded, rounded, reference),
              direction,
              base,
              session);
          long settlementEntry = entry(c, party, doc, currency, rounded, base, date);
          Posting p =
              new Posting()
                  .add(
                      Sql.one(c, "SELECT * FROM payment_methods WHERE code=?", method)
                          .text("account_code"),
                      customer ? base.negate() : base)
                  .mapped(c, customer ? "ar" : "ap", customer ? base : base.negate());
          allocate(c, u, party, doc, currency, p, List.of(settlementEntry));
          check(
              Sql.one(c, "SELECT open_amount FROM party_entries WHERE id=?", settlementEntry)
                      .money("open_amount")
                      .signum()
                  == 0,
              "The credit refund could not be fully allocated.");
          AccountingService.post(
              c, u, doc, p, customer ? "Customer credit refund" : "Supplier credit refund");
          Audit.log(
              c,
              u,
              customer ? "CUSTOMER_CREDIT_REFUNDED" : "SUPPLIER_CREDIT_REFUNDED",
              "DOCUMENT",
              doc,
              reference);
          return doc;
        });
  }

  public void reminder(User u, long entryId, LocalDate due, LocalDate remind) {
    u.require(Permission.ACCOUNTING_POST);
    db.tx(
        c -> {
          Sql.update(
              c,
              "UPDATE party_entries SET due_date=?,reminder_date=? WHERE id=?",
              due,
              remind,
              entryId);
          Audit.log(
              c,
              u,
              "DEBT_DUE_DATE_CHANGED",
              "PARTY_ENTRY",
              entryId,
              "Due " + due + "; remind " + remind);
          return null;
        });
  }
}
