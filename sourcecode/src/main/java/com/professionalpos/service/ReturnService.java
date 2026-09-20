package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.model.*;
import com.professionalpos.security.Permission;
import java.math.*;
import java.sql.*;
import java.util.*;

public final class ReturnService {
  private final Database db;

  public ReturnService(Database db) {
    this.db = db;
  }

  public Trade.Result post(User u, Trade.ReturnRequest r) {
    return db.tx(c -> post(c, u, r, false));
  }

  public BigDecimal quote(User u, Trade.ReturnRequest r) {
    u.require(Permission.RETURN_CREATE);
    return db.read(
        c -> {
          Row original = Sql.one(c, "SELECT * FROM documents WHERE id=?", r.originalId());
          int scale = SettingsService.scale(c, original.text("currency_code"));
          BigDecimal total = ZERO;
          for (Trade.ReturnLine chosen : r.lines()) {
            Row line =
                Sql.one(
                    c,
                    "SELECT * FROM document_lines WHERE id=? AND document_id=?",
                    chosen.originalLineId(),
                    r.originalId());
            positive(chosen.quantity(), "Return quantity");
            BigDecimal remaining = line.money("quantity").subtract(line.money("returned_qty"));
            check(
                chosen.quantity().compareTo(remaining) <= 0,
                "Return quantity exceeds the eligible quantity.");
            for (String key : List.of("net", "tax")) {
              BigDecimal previous =
                  Sql.one(
                          c,
                          "SELECT COALESCE(SUM("
                              + key
                              + "),0) AS amount FROM document_lines WHERE original_line_id=?",
                          line.id())
                      .money("amount");
              BigDecimal left = line.money(key).subtract(previous);
              BigDecimal value =
                  chosen.quantity().compareTo(remaining) == 0
                      ? left
                      : round(
                              divide(
                                  line.money(key).multiply(chosen.quantity()),
                                  line.money("quantity")),
                              scale)
                          .min(left);
              total = total.add(value.max(ZERO));
            }
          }
          return total;
        });
  }

  private Trade.Result post(Connection c, User u, Trade.ReturnRequest r, boolean exchanging)
      throws SQLException {
    Row old = Sql.one(c, "SELECT * FROM documents WHERE id=? FOR UPDATE", r.originalId());
    boolean sale = old.text("kind").equals("SALE");
    check(
        sale || old.text("kind").equals("PURCHASE"),
        "Return an original sale or purchase invoice.");
    u.require(sale ? Permission.RETURN_CREATE : Permission.PURCHASE_CREATE);
    Row prior = Sql.optional(c, "SELECT * FROM documents WHERE request_key=?", r.requestKey());
    if (prior != null) return TradeService.result(c, prior.id());
    check(!old.text("status").equals("VOIDED"), "This sale is already voided.");
    check(
        !r.date().isBefore(old.date("document_date")),
        "A return cannot precede the original invoice.");
    required(r.reason(), "Return reason");
    check(!r.lines().isEmpty(), "Choose at least one item.");
    check(
        new HashSet<>(r.lines().stream().map(Trade.ReturnLine::originalLineId).toList()).size()
            == r.lines().size(),
        "Select each invoice line only once.");
    check(
        !r.method().equals("EXCHANGE") || exchanging,
        "Exchange credit is only available in the exchange workflow.");
    if (r.voidSale()) {
      check(sale, "Only a sale can be voided.");
      u.require(Permission.SALE_VOID);
      check(
          Sql.count(
                  c,
                  "SELECT COUNT(*) FROM document_lines WHERE document_id=? AND returned_qty>0",
                  old.id())
              == 0,
          "This sale already has returns. Return the remaining items instead.");
    }
    if (sale && SettingsService.yes(c, "refund_approval") && !u.can(Permission.SETTINGS_USERS)) {
      check(r.approver() != null, "Manager approval is required for this return.");
      r.approver().require(Permission.REFUND_CASH);
    }
    if (sale && r.method().equals("CASH") && !u.can(Permission.REFUND_CASH)) {
      check(r.approver() != null, "Cash refund approval is required.");
      r.approver().require(Permission.REFUND_CASH);
    }
    String currency = old.text("currency_code");
    int scale = SettingsService.scale(c, currency), bs = SettingsService.baseScale(c);
    BigDecimal net = ZERO,
        tax = ZERO,
        total = ZERO,
        bnet = ZERO,
        btax = ZERO,
        btotal = ZERO,
        cost = ZERO;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Trade.ReturnLine chosen : r.lines()) {
      positive(chosen.quantity(), "Return quantity");
      check(
          Set.of("SELLABLE", "DAMAGED", "DISCARD").contains(chosen.disposition()),
          "Choose an inventory disposition.");
      Row original =
          Sql.one(
              c,
              "SELECT * FROM document_lines WHERE id=? AND document_id=?",
              chosen.originalLineId(),
              old.id());
      BigDecimal left = original.money("quantity").subtract(original.money("returned_qty"));
      check(
          chosen.quantity().compareTo(left) <= 0, "Return quantity exceeds the eligible quantity.");
      Map<String, Object> d =
          Sql.values(
              "original_line_id",
              original.id(),
              "product_id",
              original.nullableId("product_id"),
              "product_name",
              original.text("product_name"),
              "product_name_ar",
              original.text("product_name_ar"),
              "sku",
              original.text("sku"),
              "barcode",
              original.text("barcode"),
              "category_name",
              original.text("category_name"),
              "unit_name",
              original.text("unit_name"),
              "quantity",
              chosen.quantity(),
              "unit_price",
              original.money("unit_price"),
              "tax_name",
              original.text("tax_name"),
              "tax_rate",
              original.money("tax_rate"),
              "disposition",
              chosen.disposition());
      for (String k :
          List.of(
              "discount",
              "net",
              "tax",
              "total",
              "base_net",
              "base_tax",
              "base_total",
              "cost_total",
              "cost_basis")) {
        BigDecimal already =
            Sql.one(
                    c,
                    "SELECT COALESCE(SUM("
                        + k
                        + "),0) AS amount FROM document_lines WHERE original_line_id=?",
                    original.id())
                .money("amount");
        BigDecimal amount =
            chosen.quantity().compareTo(left) == 0
                ? original.money(k).subtract(already)
                : round(
                    divide(
                        original.money(k).multiply(chosen.quantity()), original.money("quantity")),
                    k.startsWith("base_") || k.startsWith("cost_") ? bs : scale);
        d.put(k, amount.min(original.money(k).subtract(already)).max(ZERO));
      }
      // Total is built from the rounded net and tax so every partial return stays arithmetically
      // exact.
      d.put("total", ((BigDecimal) d.get("net")).add((BigDecimal) d.get("tax")));
      d.put("base_total", ((BigDecimal) d.get("base_net")).add((BigDecimal) d.get("base_tax")));
      net = net.add((BigDecimal) d.get("net"));
      tax = tax.add((BigDecimal) d.get("tax"));
      total = total.add((BigDecimal) d.get("total"));
      bnet = bnet.add((BigDecimal) d.get("base_net"));
      btax = btax.add((BigDecimal) d.get("base_tax"));
      btotal = btotal.add((BigDecimal) d.get("base_total"));
      rows.add(d);
    }
    Long party = old.nullableId("party_id"), session = CashService.session(c, u);
    BigDecimal accountAmount = ZERO, accountBase = ZERO;
    if (r.method().equals("ACCOUNT")) {
      check(party != null, "An account credit requires a named contact.");
      accountAmount = total;
      accountBase = btotal;
    } else if (party != null) {
      BigDecimal outstanding =
          Sql.one(
                  c,
                  "SELECT COALESCE(SUM(open_amount),0) AS amount FROM party_entries WHERE"
                      + " document_id=? AND open_amount>0",
                  old.id())
              .money("amount");
      accountAmount = total.min(outstanding);
      accountBase =
          accountAmount.compareTo(total) == 0
              ? btotal
              : round(divide(btotal.multiply(accountAmount), total), bs);
    }
    long doc =
        Documents.create(
            c,
            u,
            sale ? "SALE_RETURN" : "PURCHASE_RETURN",
            r.date(),
            currency,
            r.requestKey(),
            party,
            Sql.values(
                "original_id",
                old.id(),
                "rate_to_base",
                old.money("rate_to_base"),
                "net",
                net,
                "tax",
                tax,
                "total",
                total,
                "base_net",
                bnet,
                "base_tax",
                btax,
                "base_total",
                btotal,
                "reason",
                r.reason(),
                "cash_session_id",
                session,
                "approved_by",
                r.approver() == null ? null : r.approver().id()));
    Posting posting = new Posting();
    if (sale) posting.mapped(c, "returns", bnet).mapped(c, "tax", btax);
    else posting.mapped(c, "input_tax", btax.negate());
    for (Map<String, Object> d : rows) {
      d.put("document_id", doc);
      long line = Sql.insert(c, "document_lines", d);
      BigDecimal qty = (BigDecimal) d.get("quantity"),
          historical = (BigDecimal) d.get("cost_basis"),
          lineNet = (BigDecimal) d.get("base_net");
      Long product = (Long) d.get("product_id");
      boolean tracked =
          product != null
              && Sql.one(c, "SELECT * FROM products WHERE id=?", product).flag("track_stock");
      if (sale) {
        String disposition = (String) d.get("disposition");
        BigDecimal reverseCost = ZERO;
        if (tracked && disposition.equals("SELLABLE")) {
          BigDecimal restored =
              InventoryService.move(
                  c, u, product, doc, line, qty, historical, "SALE_RETURN", r.reason());
          posting
              .mapped(c, "inventory", restored)
              .mapped(c, "inventory_loss", historical.subtract(restored))
              .mapped(c, "cogs", historical.negate());
          reverseCost = historical;
        } else if (tracked && disposition.equals("DAMAGED")) {
          // Until inspected, a damaged return has a conservative carrying value of zero. The
          // separate damaged subledger preserves quantity and historical cost; a controlled NRV
          // assessment can subsequently recognize recoverable value, capped at historical cost.
          Sql.insert(
              c,
              "damaged_inventory",
              Sql.values(
                  "product_id",
                  product,
                  "source_document_line_id",
                  line,
                  "quantity",
                  qty,
                  "remaining_quantity",
                  qty,
                  "historical_cost",
                  historical,
                  "carrying_value",
                  ZERO,
                  "status",
                  "PENDING"));
        }
        Sql.update(c, "UPDATE document_lines SET cost_total=? WHERE id=?", reverseCost, line);
        cost = cost.add(reverseCost);
      } else if (tracked) {
        BigDecimal released =
            InventoryService.move(
                    c, u, product, doc, line, qty.negate(), ZERO, "PURCHASE_RETURN", r.reason())
                .negate();
        posting
            .mapped(c, "inventory", released.negate())
            .mapped(c, "inventory_loss", released.subtract(lineNet));
        Sql.update(c, "UPDATE document_lines SET cost_total=? WHERE id=?", released, line);
        cost = cost.add(released);
      } else posting.mapped(c, "expense", lineNet.negate());
      Sql.update(
          c,
          "UPDATE document_lines SET returned_qty=returned_qty+? WHERE id=?",
          qty,
          d.get("original_line_id"));
    }
    BigDecimal refund = total.subtract(accountAmount), refundBase = btotal.subtract(accountBase);
    if (accountAmount.signum() > 0) {
      DebtService.entry(
          c, party, doc, currency, accountAmount.negate(), accountBase.negate(), null);
      posting.mapped(c, sale ? "ar" : "ap", sale ? accountBase.negate() : accountBase);
      List<Long> originalEntries =
          Sql.rows(
                  c, "SELECT id FROM party_entries WHERE document_id=? AND open_amount>0", old.id())
              .stream()
              .map(Row::id)
              .toList();
      DebtService.allocate(c, u, party, doc, currency, posting, originalEntries);
    }
    if (refund.signum() > 0) {
      CashService.payment(
          c,
          u,
          doc,
          currency,
          old.money("rate_to_base"),
          new Trade.Payment(r.method(), refund),
          sale ? -1 : 1,
          refundBase,
          session);
      String account =
          Sql.one(c, "SELECT * FROM payment_methods WHERE code=?", r.method()).text("account_code");
      posting.add(account, sale ? refundBase.negate() : refundBase);
    }
    Sql.update(c, "UPDATE documents SET cogs=? WHERE id=?", cost, doc);
    long remaining =
        Sql.count(
            c,
            "SELECT COUNT(*) FROM document_lines WHERE document_id=? AND returned_qty<quantity",
            old.id());
    if (r.voidSale()) check(remaining == 0, "A void must include every sold item.");
    Sql.update(
        c,
        "UPDATE documents SET status=? WHERE id=?",
        r.voidSale() ? "VOIDED" : remaining == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED",
        old.id());
    AccountingService.post(
        c, u, doc, posting, (r.voidSale() ? "Void" : "Return") + " " + old.text("number"));
    Audit.log(
        c,
        u,
        r.voidSale() ? "SALE_VOIDED" : "RETURN_CREATED",
        "DOCUMENT",
        doc,
        null,
        "Original " + old.text("number"),
        r.reason(),
        r.approver() == null ? null : r.approver().id());
    return TradeService.result(c, doc);
  }

  /**
   * Linked exchange: return and replacement are one transaction, including net cash
   * collected/refunded.
   */
  public Trade.Result exchange(
      User u, Trade.ReturnRequest returned, Trade.Request replacement, String refundMethod) {
    return db.tx(
        c -> {
          Row prior =
              Sql.optional(
                  c, "SELECT * FROM documents WHERE request_key=?", replacement.requestKey());
          if (prior != null) return TradeService.result(c, prior.id());
          Row original = Sql.one(c, "SELECT * FROM documents WHERE id=?", returned.originalId());
          check(
              original.text("currency_code").equals(replacement.currency()),
              "An exchange uses the original currency.");
          check(
              Sql.one(
                          c,
                          "SELECT COALESCE(SUM(open_amount),0) AS amount FROM party_entries WHERE"
                              + " document_id=?",
                          original.id())
                      .money("amount")
                      .signum()
                  == 0,
              "Settle this invoice or use an account return before exchanging an unpaid sale.");
          Trade.ReturnRequest rr =
              new Trade.ReturnRequest(
                  returned.originalId(),
                  returned.lines(),
                  "EXCHANGE",
                  returned.reason(),
                  returned.date(),
                  returned.requestKey(),
                  returned.approver(),
                  false);
          Trade.Result credit = post(c, u, rr, true);
          TradeService sales = new TradeService(db);
          Calculator.Total quoted = sales.quote(u, replacement);
          BigDecimal used = credit.total().min(quoted.total());
          List<Trade.Payment> pays = new ArrayList<>(replacement.payments());
          if (used.signum() > 0) pays.add(new Trade.Payment("EXCHANGE", used));
          Trade.Request adjusted =
              new Trade.Request(
                  "SALE",
                  replacement.partyId(),
                  replacement.date(),
                  replacement.due(),
                  replacement.currency(),
                  replacement.lines(),
                  replacement.discount(),
                  replacement.discountPercent(),
                  pays,
                  replacement.reference(),
                  replacement.notes(),
                  replacement.requestKey(),
                  replacement.heldId(),
                  null,
                  replacement.approver(),
                  replacement.reason());
          // Reuse this connection, including its uncommitted returned inventory.
          Trade.Result sold = sales.complete(c, u, adjusted, true);
          String link = Documents.key();
          Sql.update(
              c,
              "UPDATE documents SET exchange_id=? WHERE id IN (?,?)",
              link,
              credit.id(),
              sold.id());
          BigDecimal extra = credit.total().subtract(used);
          if (extra.signum() > 0) {
            int bs = SettingsService.baseScale(c);
            BigDecimal rate = original.money("rate_to_base"),
                base = round(extra.multiply(rate), bs);
            Long session = CashService.session(c, u);
            long doc =
                Documents.create(
                    c,
                    u,
                    "CASH_OUT",
                    replacement.date(),
                    replacement.currency(),
                    Documents.key(),
                    null,
                    Sql.values(
                        "exchange_id",
                        link,
                        "original_id",
                        credit.id(),
                        "total",
                        extra,
                        "base_total",
                        base,
                        "rate_to_base",
                        rate,
                        "reason",
                        "Exchange difference"));
            CashService.payment(
                c,
                u,
                doc,
                replacement.currency(),
                rate,
                new Trade.Payment(refundMethod, extra),
                -1,
                base,
                session);
            AccountingService.post(
                c,
                u,
                doc,
                new Posting()
                    .add("1130", base)
                    .add(
                        Sql.one(c, "SELECT * FROM payment_methods WHERE code=?", refundMethod)
                            .text("account_code"),
                        base.negate()),
                "Exchange difference refund");
          }
          // A rate change between the original sale and its replacement is realized on the clearing
          // account.
          BigDecimal clearing =
              Sql.one(
                      c,
                      "SELECT COALESCE(SUM(l.debit-l.credit),0) AS amount FROM journal_lines l JOIN"
                          + " accounts a ON a.id=l.account_id JOIN journal_entries j ON"
                          + " j.id=l.entry_id JOIN documents d ON d.id=j.document_id WHERE"
                          + " a.code='1130' AND d.exchange_id=?",
                      link)
                  .money("amount");
          if (clearing.signum() != 0) {
            long fxDoc =
                Documents.create(
                    c,
                    u,
                    "ADJUSTMENT",
                    replacement.date(),
                    SettingsService.get(c, "base_currency"),
                    Documents.key(),
                    null,
                    Sql.values("exchange_id", link, "reason", "Exchange currency settlement"));
            AccountingService.post(
                c,
                u,
                fxDoc,
                new Posting()
                    .add("1130", clearing.negate())
                    .mapped(c, clearing.signum() > 0 ? "fx_loss" : "fx_gain", clearing),
                "Exchange currency settlement");
          }
          Audit.log(c, u, "EXCHANGE_COMPLETED", "DOCUMENT", sold.id(), link);
          return sold;
        });
  }
}
