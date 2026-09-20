package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.*;
import com.professionalpos.security.Permission;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class PurchaseService {
  private final Database db;

  public PurchaseService(Database db) {
    this.db = db;
  }

  public record OrderLine(
      long product,
      BigDecimal quantity,
      BigDecimal cost,
      BigDecimal discountPct,
      BigDecimal taxRate) {}

  public long order(
      User u,
      Long id,
      long supplier,
      LocalDate date,
      LocalDate expected,
      String currency,
      String notes,
      List<OrderLine> lines) {
    u.require(Permission.PURCHASE_CREATE);
    return db.tx(
        c -> {
          check(!lines.isEmpty(), "Add at least one order item.");
          check(
              Sql.one(c, "SELECT * FROM parties WHERE id=? AND active=TRUE", supplier)
                  .text("kind")
                  .equals("SUPPLIER"),
              "Choose a supplier.");
          Map<String, Object> data =
              Sql.values(
                  "supplier_id",
                  supplier,
                  "ordered_on",
                  date,
                  "expected_on",
                  expected,
                  "currency_code",
                  currency,
                  "rate_to_base",
                  CurrencyService.rate(c, currency, date),
                  "notes",
                  notes);
          long saved;
          if (id == null) {
            data.put("number", Documents.next(c, "PO"));
            data.put("status", "DRAFT");
            data.put("created_by", u.id());
            saved = Sql.insert(c, "purchase_orders", data);
          } else {
            check(
                Sql.one(c, "SELECT * FROM purchase_orders WHERE id=?", id)
                    .text("status")
                    .equals("DRAFT"),
                "Only draft purchase orders can be edited.");
            check(
                Sql.count(c, "SELECT COUNT(*) FROM documents WHERE order_id=?", id) == 0,
                "This order already has receipts.");
            Sql.edit(c, "purchase_orders", id, data);
            Sql.update(c, "DELETE FROM po_lines WHERE order_id=?", id);
            saved = id;
          }
          for (OrderLine l : lines) {
            positive(l.quantity(), "Quantity");
            nonnegative(l.cost(), "Cost");
            BigDecimal tax =
                l.taxRate() == null
                    ? Sql.one(
                            c,
                            "SELECT t.rate FROM products p JOIN tax_rates t ON t.id=p.tax_id WHERE"
                                + " p.id=? AND p.active=TRUE",
                            l.product())
                        .money("rate")
                    : l.taxRate();
            check(
                l.discountPct().compareTo(ZERO) >= 0 && l.discountPct().compareTo(HUNDRED) <= 0,
                "Order discount must be between 0 and 100.");
            check(
                tax.compareTo(ZERO) >= 0 && tax.compareTo(HUNDRED) <= 0,
                "Tax rate must be between 0 and 100.");
            Sql.insert(
                c,
                "po_lines",
                Sql.values(
                    "order_id",
                    saved,
                    "product_id",
                    l.product(),
                    "quantity",
                    l.quantity(),
                    "unit_cost",
                    l.cost(),
                    "discount_pct",
                    l.discountPct(),
                    "tax_rate",
                    tax));
          }
          Audit.log(c, u, "PURCHASE_ORDER_SAVED", "PURCHASE_ORDER", saved, notes);
          return saved;
        });
  }

  public void status(User u, long id, String status) {
    u.require(Permission.PURCHASE_CREATE);
    check(Set.of("SENT", "CANCELLED").contains(status), "Invalid order action.");
    db.tx(
        c -> {
          Row old = Sql.one(c, "SELECT * FROM purchase_orders WHERE id=?", id);
          check(
              !Set.of("CANCELLED", "RECEIVED").contains(old.text("status")),
              "This order is already closed.");
          check(
              !status.equals("SENT") || old.text("status").equals("DRAFT"),
              "Only a draft order can be marked sent.");
          Sql.update(c, "UPDATE purchase_orders SET status=? WHERE id=?", status, id);
          Audit.log(c, u, "PO_STATUS_CHANGED", "PURCHASE_ORDER", id, status);
          return null;
        });
  }

  public void deleteDraft(User u, long id) {
    u.require(Permission.PURCHASE_CREATE);
    db.tx(
        c -> {
          check(
              Sql.one(c, "SELECT * FROM purchase_orders WHERE id=?", id)
                  .text("status")
                  .equals("DRAFT"),
              "Only drafts can be deleted.");
          check(
              Sql.count(c, "SELECT COUNT(*) FROM documents WHERE order_id=?", id) == 0,
              "This draft already has receipts.");
          Sql.update(c, "DELETE FROM po_lines WHERE order_id=?", id);
          Sql.update(c, "DELETE FROM purchase_orders WHERE id=?", id);
          Audit.log(c, u, "PO_DRAFT_DELETED", "PURCHASE_ORDER", id, "");
          return null;
        });
  }
}
