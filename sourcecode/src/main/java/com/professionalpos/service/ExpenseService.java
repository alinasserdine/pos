package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.model.*;
import com.professionalpos.security.Permission;
import java.math.*;
import java.time.*;

public final class ExpenseService {
  private final Database db;

  public ExpenseService(Database db) {
    this.db = db;
  }

  public long record(
      User u,
      LocalDate date,
      String category,
      String description,
      String currency,
      BigDecimal amount,
      String method,
      Long supplier,
      LocalDate due,
      String reference,
      String key) {
    return record(
        u,
        date,
        category,
        description,
        currency,
        amount,
        ZERO,
        false,
        method,
        supplier,
        due,
        reference,
        key);
  }

  /** Records a tax-aware expense. Amount is gross when inclusive, otherwise it is net. */
  public long record(
      User u,
      LocalDate date,
      String category,
      String description,
      String currency,
      BigDecimal amount,
      BigDecimal taxRate,
      boolean taxInclusive,
      String method,
      Long supplier,
      LocalDate due,
      String reference,
      String key) {
    u.require(Permission.EXPENSE_CREATE);
    positive(amount, "Amount");
    nonnegative(taxRate, "Tax rate");
    check(taxRate.compareTo(new BigDecimal("100")) <= 0, "Tax rate cannot exceed 100%.");
    required(description, "Description");
    return db.tx(
        c -> {
          Row prior = Sql.optional(c, "SELECT * FROM documents WHERE request_key=?", key);
          if (prior != null) return prior.id();
          Row a = Sql.one(c, "SELECT * FROM accounts WHERE code=? AND active=TRUE", category);
          check(a.text("account_type").equals("EXPENSE"), "Choose an expense account.");
          if (method.equals("ACCOUNT")) {
            check(
                supplier != null && due != null,
                "Choose a supplier and due date for unpaid expenses.");
            check(
                Sql.one(c, "SELECT * FROM parties WHERE id=?", supplier)
                    .text("kind")
                    .equals("SUPPLIER"),
                "Choose a supplier.");
          }
          check(!method.equals("EXCHANGE"), "Exchange credit cannot pay expenses.");
          int currencyScale = SettingsService.scale(c, currency),
              baseScale = SettingsService.baseScale(c);
          BigDecimal rate = CurrencyService.rate(c, currency, date),
              entered = round(amount, currencyScale),
              factor = BigDecimal.ONE.add(taxRate.movePointLeft(2)),
              net =
                  taxInclusive && taxRate.signum() > 0
                      ? round(divide(entered, factor), currencyScale)
                      : entered,
              tax =
                  taxInclusive
                      ? entered.subtract(net)
                      : round(net.multiply(taxRate).movePointLeft(2), currencyScale),
              total = net.add(tax),
              baseNet = round(net.multiply(rate), baseScale),
              baseTotal = round(total.multiply(rate), baseScale),
              baseTax = baseTotal.subtract(baseNet);
          Long session = CashService.session(c, u);
          long doc =
              Documents.create(
                  c,
                  u,
                  "EXPENSE",
                  date,
                  currency,
                  key,
                  supplier,
                  Sql.values(
                      "due_date",
                      due,
                      "subtotal",
                      net,
                      "net",
                      net,
                      "tax",
                      tax,
                      "total",
                      total,
                      "base_net",
                      baseNet,
                      "base_tax",
                      baseTax,
                      "base_total",
                      baseTotal,
                      "notes",
                      description,
                      "reference",
                      reference,
                      "cash_session_id",
                      session));
          Posting p = new Posting().add(category, baseNet);
          if (baseTax.signum() != 0) p.mapped(c, "input_tax", baseTax);
          if (method.equals("ACCOUNT")) {
            DebtService.entry(c, supplier, doc, currency, total, baseTotal, due);
            p.mapped(c, "ap", baseTotal.negate());
            DebtService.allocate(c, u, supplier, doc, currency, p, java.util.List.of());
          } else {
            CashService.payment(
                c,
                u,
                doc,
                currency,
                rate,
                new Trade.Payment(method, total, total, reference),
                -1,
                baseTotal,
                session);
            p.add(
                Sql.one(c, "SELECT * FROM payment_methods WHERE code=?", method)
                    .text("account_code"),
                baseTotal.negate());
          }
          AccountingService.post(c, u, doc, p, description);
          Audit.log(c, u, "EXPENSE_CREATED", "DOCUMENT", doc, description);
          return doc;
        });
  }
}
