package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public final class CurrencyService {
  private final Database db;

  public CurrencyService(Database db) {
    this.db = db;
  }

  /** Converts a display amount through the configured base currency using dated rates. */
  public BigDecimal convert(User user, BigDecimal amount, String from, String to, LocalDate date) {
    user.require(Permission.POS_ACCESS);
    return db.read(
        c ->
            round(
                divide(amount.multiply(rate(c, from, date)), rate(c, to, date)),
                SettingsService.scale(c, to)));
  }

  public static BigDecimal rate(Connection c, String code, LocalDate date) throws SQLException {
    Sql.one(c, "SELECT * FROM currencies WHERE code=? AND active=TRUE", code);
    if (SettingsService.get(c, "base_currency").equals(code)) return BigDecimal.ONE;
    Row r =
        Sql.optional(
            c,
            "SELECT rate_to_base FROM exchange_rates WHERE currency_code=? AND effective_date<=?"
                + " ORDER BY effective_date DESC FETCH FIRST 1 ROW ONLY",
            code,
            date);
    check(r != null, "Enter an exchange rate for " + code + " first.");
    return r.money("rate_to_base");
  }

  public void save(User u, String code, String name, String symbol, int decimals, boolean before) {
    u.require(Permission.SETTINGS_CURRENCY);
    check(code.matches("[A-Z]{3,10}"), "Use 3 to 10 uppercase letters for the currency code.");
    check(decimals >= 0 && decimals <= 6, "Invalid currency decimals.");
    db.tx(
        c -> {
          Row old = Sql.optional(c, "SELECT * FROM currencies WHERE code=?", code);
          if (old != null) {
            check(
                old.number("decimal_places") == decimals
                    || Sql.count(c, "SELECT COUNT(*) FROM documents WHERE currency_code=?", code)
                        == 0,
                "Decimals cannot change after transactions exist.");
            Sql.update(
                c,
                "UPDATE currencies SET name=?,symbol=?,decimal_places=?,symbol_before=? WHERE"
                    + " code=?",
                required(name, "Name"),
                required(symbol, "Symbol"),
                decimals,
                before,
                code);
          } else
            Sql.insert(
                c,
                "currencies",
                Sql.values(
                    "code",
                    code,
                    "name",
                    required(name, "Name"),
                    "symbol",
                    required(symbol, "Symbol"),
                    "decimal_places",
                    decimals,
                    "symbol_before",
                    before));
          Audit.log(c, u, "CURRENCY_CHANGED", "CURRENCY", 0, code);
          return null;
        });
  }

  public void addRate(User u, String code, LocalDate date, BigDecimal rate) {
    u.require(Permission.SETTINGS_CURRENCY);
    positive(rate, "Exchange rate");
    db.tx(
        c -> {
          check(
              !SettingsService.get(c, "base_currency").equals(code),
              "The base currency rate is always one.");
          Sql.insert(
              c,
              "exchange_rates",
              Sql.values(
                  "currency_code",
                  code,
                  "effective_date",
                  date,
                  "rate_to_base",
                  rate,
                  "created_by",
                  u.id()));
          Audit.log(c, u, "EXCHANGE_RATE_CHANGED", "CURRENCY", 0, code + " = " + rate + " base");
          return null;
        });
  }

  /** Remeasures every open monetary party balance in one currency at the period closing rate. */
  public long revalueOpenBalances(User u, String currency, LocalDate date, String requestKey) {
    u.require(Permission.ACCOUNTING_POST);
    return db.tx(
        c -> {
          check(
              !SettingsService.get(c, "base_currency").equals(currency),
              "The base currency does not require revaluation.");
          Row prior = Sql.optional(c, "SELECT id FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          check(
              Sql.count(
                      c,
                      "SELECT COUNT(*) FROM fx_revaluations WHERE valuation_date=? AND"
                          + " currency_code=?",
                      date,
                      currency)
                  == 0,
              "This currency has already been revalued for that date.");
          BigDecimal closingRate = rate(c, currency, date);
          int scale = SettingsService.baseScale(c);
          List<Row> balances =
              Sql.rows(
                  c,
                  "SELECT e.*,p.kind FROM party_entries e JOIN parties p ON p.id=e.party_id"
                      + " WHERE e.currency_code=? AND e.open_amount<>0 ORDER BY e.id FOR UPDATE",
                  currency);
          check(!balances.isEmpty(), "There are no open balances in this currency.");
          long doc =
              Documents.create(
                  c,
                  u,
                  "MANUAL",
                  date,
                  SettingsService.get(c, "base_currency"),
                  requestKey,
                  null,
                  Sql.values("reason", "Closing FX revaluation: " + currency));
          long revaluation =
              Sql.insert(
                  c,
                  "fx_revaluations",
                  Sql.values(
                      "valuation_date",
                      date,
                      "currency_code",
                      currency,
                      "document_id",
                      doc,
                      "status",
                      "POSTED",
                      "created_by",
                      u.id()));
          Posting posting = new Posting();
          BigDecimal netControl = ZERO;
          for (Row entry : balances) {
            BigDecimal previous = entry.money("open_base"),
                closing = round(entry.money("open_amount").multiply(closingRate), scale),
                difference = closing.subtract(previous);
            if (difference.signum() == 0) continue;
            boolean customer = entry.text("kind").equals("CUSTOMER");
            BigDecimal control = customer ? difference : difference.negate();
            posting.mapped(c, customer ? "ar" : "ap", control);
            netControl = netControl.add(control);
            Sql.insert(
                c,
                "fx_revaluation_lines",
                Sql.values(
                    "revaluation_id",
                    revaluation,
                    "party_entry_id",
                    entry.id(),
                    "party_id",
                    entry.number("party_id"),
                    "open_amount",
                    entry.money("open_amount"),
                    "previous_open_base",
                    previous,
                    "closing_open_base",
                    closing,
                    "difference",
                    difference));
            Sql.update(c, "UPDATE party_entries SET open_base=? WHERE id=?", closing, entry.id());
          }
          check(!posting.isEmpty(), "The closing rate creates no revaluation difference.");
          posting.mapped(
              c, netControl.signum() > 0 ? "fx_unrealized_gain" : "fx_unrealized_loss", netControl.negate());
          AccountingService.post(c, u, doc, posting, "Closing FX revaluation: " + currency);
          Audit.log(c, u, "FX_REVALUED", "FX_REVALUATION", revaluation, currency + " @ " + closingRate);
          return doc;
        });
  }

  /** Reverses a revaluation only while the affected open items remain unchanged. */
  public long reverseRevaluation(User u, long revaluationId, LocalDate date, String reason) {
    u.require(Permission.ACCOUNTING_POST);
    required(reason, "Reason");
    return db.tx(
        c -> {
          Row header =
              Sql.one(c, "SELECT * FROM fx_revaluations WHERE id=? FOR UPDATE", revaluationId);
          check(header.text("status").equals("POSTED"), "This revaluation is already reversed.");
          List<Row> lines =
              Sql.rows(
                  c,
                  "SELECT l.*,p.kind,e.open_amount AS current_open_amount,e.open_base AS"
                      + " current_open_base FROM fx_revaluation_lines l JOIN party_entries e ON"
                      + " e.id=l.party_entry_id JOIN parties p ON p.id=l.party_id WHERE"
                      + " l.revaluation_id=? ORDER BY l.id FOR UPDATE",
                  revaluationId);
          for (Row line : lines) {
            check(
                line.money("current_open_amount").compareTo(line.money("open_amount")) == 0
                    && line.money("current_open_base").compareTo(line.money("closing_open_base"))
                        == 0,
                "An affected balance changed after revaluation; post a new closing revaluation instead.");
            Sql.update(
                c,
                "UPDATE party_entries SET open_base=? WHERE id=?",
                line.money("previous_open_base"),
                line.number("party_entry_id"));
          }
          Posting posting = new Posting();
          Row originalJournal =
              Sql.one(
                  c,
                  "SELECT id FROM journal_entries WHERE document_id=?",
                  header.number("document_id"));
          for (Row journalLine :
              Sql.rows(
                  c,
                  "SELECT a.code,l.debit,l.credit FROM journal_lines l JOIN accounts a ON"
                      + " a.id=l.account_id WHERE l.entry_id=?",
                  originalJournal.id()))
            posting.add(
                journalLine.text("code"),
                journalLine.money("credit").subtract(journalLine.money("debit")));
          long doc =
              Documents.create(
                  c,
                  u,
                  "REVERSAL",
                  date,
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("original_id", header.number("document_id"), "reason", reason));
          AccountingService.post(c, u, doc, posting, reason);
          Sql.update(
              c,
              "UPDATE fx_revaluations SET status='REVERSED',reversed_by_document_id=? WHERE id=?",
              doc,
              revaluationId);
          Audit.log(c, u, "FX_REVALUATION_REVERSED", "FX_REVALUATION", revaluationId, reason);
          return doc;
        });
  }
}
