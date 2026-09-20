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

public final class TradeService {
  private final Database db;

  public TradeService(Database db) {
    this.db = db;
  }

  public Trade.Result complete(User u, Trade.Request request) {
    return db.tx(c -> complete(c, u, request));
  }

  private record Prepared(
      List<Row> products,
      List<BigDecimal> prices,
      Calculator.Total total,
      BigDecimal rate,
      int scale,
      int baseScale) {}

  private Prepared prepare(Connection c, User u, Trade.Request r) throws SQLException {
    boolean sale = r.kind().equals("SALE");
    check(sale || r.kind().equals("PURCHASE"), "Invalid transaction kind.");
    u.require(sale ? Permission.SALE_CREATE : Permission.PURCHASE_CREATE);
    check(r.lines().size() <= 1000, "Use at most 1,000 lines per invoice.");
    BigDecimal rate = CurrencyService.rate(c, r.currency(), r.date());
    int scale = SettingsService.scale(c, r.currency());
    List<Row> products = new ArrayList<>();
    List<BigDecimal> prices = new ArrayList<>();
    List<Calculator.Input> inputs = new ArrayList<>();
    BigDecimal customerDiscount = ZERO;
    if (r.partyId() != null) {
      Row party = Sql.one(c, "SELECT * FROM parties WHERE id=? AND active=TRUE", r.partyId());
      check(
          party.text("kind").equals(sale ? "CUSTOMER" : "SUPPLIER"),
          "Choose a matching customer or supplier.");
      if (sale) customerDiscount = party.money("discount_pct");
    }
    if (!sale) {
      check(r.partyId() != null, "Choose a supplier.");
      if (r.reference() != null && !r.reference().isBlank())
        check(
            Sql.count(
                    c,
                    "SELECT COUNT(*) FROM documents WHERE kind='PURCHASE' AND party_id=? AND"
                        + " reference=?",
                    r.partyId(),
                    r.reference())
                == 0,
            "This supplier reference already exists. Check for a duplicate invoice.");
    }
    for (Trade.Line l : r.lines()) {
      Row p =
          Sql.one(
              c,
              "SELECT p.*,t.name AS tax_name,t.rate AS tax_rate,un.name AS unit_name,ca.name AS"
                  + " category_name,(SELECT b.barcode FROM product_barcodes b WHERE"
                  + " b.product_id=p.id AND b.is_primary=TRUE FETCH FIRST 1 ROW ONLY) AS barcode"
                  + " FROM products p JOIN tax_rates t ON t.id=p.tax_id JOIN units un ON"
                  + " un.id=p.unit_id LEFT JOIN categories ca ON ca.id=p.category_id WHERE p.id=?"
                  + " AND p.active=TRUE",
              l.productId());
      if (!sale && l.poLineId() != null)
        p.put(
            "tax_rate",
            Sql.one(c, "SELECT tax_rate FROM po_lines WHERE id=?", l.poLineId()).money("tax_rate"));
      check(l.quantity().scale() <= 6, "Quantity supports at most 6 decimals.");
      if (!p.flag("fractional"))
        check(
            l.quantity().stripTrailingZeros().scale() <= 0,
            "This product requires whole-number quantities.");
      BigDecimal regular = round(divide(p.money(sale ? "price" : "last_cost"), rate), scale),
          price = l.unitPrice() == null ? regular : l.unitPrice();
      check(price.scale() <= 6, "Unit price supports at most 6 decimals.");
      if (sale && regular.compareTo(price) != 0) {
        String mode = SettingsService.get(c, "price_override");
        check(!mode.equals("NEVER"), "Price overrides are disabled.");
        if (mode.equals("PERMISSION")) u.require(Permission.PRICE_OVERRIDE);
        if (mode.equals("APPROVAL")) {
          check(r.approver() != null, "Manager approval is required.");
          r.approver().require(Permission.PRICE_OVERRIDE);
        }
        required(r.reason(), "Price override reason");
      }
      BigDecimal discount = l.discount();
      boolean percent = l.percent();
      if (sale && discount.signum() == 0 && customerDiscount.signum() > 0) {
        discount = customerDiscount;
        percent = true;
      }
      inputs.add(new Calculator.Input(l.quantity(), price, discount, percent, p.money("tax_rate")));
      products.add(p);
      prices.add(price);
    }
    Calculator.Total total =
        Calculator.calculate(
            inputs, r.discount(), r.discountPercent(), SettingsService.get(c, "tax_mode"), scale);
    if (sale && total.discount().signum() > 0) {
      u.require(Permission.SALE_DISCOUNT);
      BigDecimal pct =
          total.gross().signum() == 0
              ? ZERO
              : divide(total.discount().multiply(HUNDRED), total.gross());
      BigDecimal limit = of(SettingsService.get(c, "max_cashier_discount"));
      if (pct.compareTo(limit) > 0 && !u.can(Permission.SALE_LARGE_DISCOUNT)) {
        check(r.approver() != null, "Manager approval is required for this discount.");
        r.approver().require(Permission.SALE_LARGE_DISCOUNT);
        required(r.reason(), "Discount approval reason");
      }
    }
    return new Prepared(products, prices, total, rate, scale, SettingsService.baseScale(c));
  }

  public Calculator.Total quote(User u, Trade.Request r) {
    return db.read(c -> prepare(c, u, r).total());
  }

  Trade.Result complete(Connection c, User u, Trade.Request r) throws SQLException {
    return complete(c, u, r, false);
  }

  Trade.Result complete(Connection c, User u, Trade.Request r, boolean exchange)
      throws SQLException {
    u.require(r.kind().equals("SALE") ? Permission.SALE_CREATE : Permission.PURCHASE_CREATE);
    Row prior = Sql.optional(c, "SELECT * FROM documents WHERE request_key=?", r.requestKey());
    if (prior != null) {
      check(prior.text("kind").equals(r.kind()), "This request belongs to a different operation.");
      return result(c, prior.id());
    }
    Prepared prep = prepare(c, u, r);
    boolean sale = r.kind().equals("SALE");
    Calculator.Total t = prep.total();
    check(
        exchange || r.payments().size() <= 1 || SettingsService.yes(c, "enable_split"),
        "Split payment is disabled.");
    BigDecimal paid = ZERO, paidBase = ZERO, change = ZERO;
    for (Trade.Payment p : r.payments()) {
      check(
          exchange || !p.method().equals("EXCHANGE"),
          "Use the exchange workflow for exchange credit.");
      positive(p.amount(), "Payment");
      check(
          round(p.amount(), prep.scale()).compareTo(p.amount()) == 0,
          "Payment has too many decimal places.");
      paid = paid.add(p.amount());
      paidBase = paidBase.add(round(p.amount().multiply(prep.rate()), prep.baseScale()));
      change = change.add(p.tendered().subtract(p.amount()));
    }
    check(
        paid.compareTo(t.total()) <= 0,
        "Payment exceeds the amount due. Put cash tendered in the received field.");
    BigDecimal credit = t.total().subtract(paid);
    if (credit.signum() > 0) {
      check(r.partyId() != null, "Choose a customer or supplier for an unpaid balance.");
      if (sale) {
        u.require(Permission.CUSTOMER_CREDIT);
        check(SettingsService.yes(c, "allow_credit"), "Customer credit is disabled.");
      }
      check(r.due() != null, "A due date is required for credit.");
      check(!r.due().isBefore(r.date()), "Due date cannot precede the invoice date.");
    }
    BigDecimal baseNet = ZERO, baseTax = ZERO, baseTotal = ZERO;
    for (Calculator.Line line : t.lines()) {
      BigDecimal bn = round(line.net().multiply(prep.rate()), prep.baseScale()),
          bt = round(line.total().multiply(prep.rate()), prep.baseScale());
      baseNet = baseNet.add(bn);
      baseTax = baseTax.add(bt.subtract(bn));
      baseTotal = baseTotal.add(bt);
    }
    BigDecimal debtBase = credit.signum() > 0 ? baseTotal.subtract(paidBase).max(ZERO) : ZERO;
    if (sale && credit.signum() > 0) {
      BigDecimal balance = DebtService.balance(c, r.partyId());
      Row party = Sql.one(c, "SELECT * FROM parties WHERE id=?", r.partyId());
      check(
          balance.add(debtBase).compareTo(party.money("credit_limit")) <= 0,
          "Customer credit limit exceeded.");
    }
    Long session = CashService.session(c, u);
    Map<String, Object> fields =
        Sql.values(
            "rate_to_base",
            prep.rate(),
            "due_date",
            r.due(),
            "subtotal",
            t.gross(),
            "discount",
            t.discount(),
            "net",
            t.net(),
            "tax",
            t.tax(),
            "total",
            t.total(),
            "base_net",
            baseNet,
            "base_tax",
            baseTax,
            "base_total",
            baseTotal,
            "reference",
            r.reference(),
            "notes",
            r.notes(),
            "reason",
            r.reason(),
            "cash_session_id",
            session,
            "approved_by",
            r.approver() == null ? null : r.approver().id(),
            "order_id",
            r.orderId());
    long doc =
        Documents.create(
            c, u, r.kind(), r.date(), r.currency(), r.requestKey(), r.partyId(), fields);
    BigDecimal cogs = ZERO;
    Posting posting = new Posting();
    for (int i = 0; i < r.lines().size(); i++) {
      Trade.Line input = r.lines().get(i);
      Calculator.Line line = t.lines().get(i);
      Row p = prep.products().get(i);
      BigDecimal bn = round(line.net().multiply(prep.rate()), prep.baseScale()),
          bt = round(line.total().multiply(prep.rate()), prep.baseScale());
      long lineId =
          Sql.insert(
              c,
              "document_lines",
              Sql.values(
                  "document_id",
                  doc,
                  "po_line_id",
                  input.poLineId(),
                  "product_id",
                  p.id(),
                  "product_name",
                  p.text("name"),
                  "product_name_ar",
                  p.text("name_ar"),
                  "sku",
                  p.text("sku"),
                  "barcode",
                  p.text("barcode"),
                  "category_name",
                  p.text("category_name"),
                  "unit_name",
                  p.text("unit_name"),
                  "quantity",
                  input.quantity(),
                  "unit_price",
                  prep.prices().get(i),
                  "discount",
                  line.discount(),
                  "tax_name",
                  p.text("tax_name"),
                  "tax_rate",
                  SettingsService.get(c, "tax_mode").equals("NONE") ? ZERO : p.money("tax_rate"),
                  "net",
                  line.net(),
                  "tax",
                  line.tax(),
                  "total",
                  line.total(),
                  "base_net",
                  bn,
                  "base_tax",
                  bt.subtract(bn),
                  "base_total",
                  bt,
                  "note",
                  input.note()));
      BigDecimal moved =
          InventoryService.move(
              c,
              u,
              p.id(),
              doc,
              lineId,
              sale ? input.quantity().negate() : input.quantity(),
              bn,
              sale ? "SALE" : "PURCHASE",
              r.notes());
      BigDecimal cost = sale ? moved.negate() : bn;
      if (!sale && !p.flag("track_stock")) posting.mapped(c, "expense", bn);
      else if (!sale)
        posting.mapped(c, "inventory", moved).mapped(c, "inventory_loss", bn.subtract(moved));
      cogs = cogs.add(cost);
      Sql.update(
          c, "UPDATE document_lines SET cost_total=?,cost_basis=? WHERE id=?", cost, cost, lineId);
      if (input.poLineId() != null) {
        check(!sale && r.orderId() != null, "Purchase order link is invalid.");
        Row pol =
            Sql.one(
                c,
                "SELECT l.*,o.supplier_id,o.currency_code,o.status FROM po_lines l JOIN"
                    + " purchase_orders o ON o.id=l.order_id WHERE l.id=?",
                input.poLineId());
        check(
            pol.number("order_id") == r.orderId()
                && pol.number("product_id") == p.id()
                && pol.number("supplier_id") == r.partyId()
                && pol.text("currency_code").equals(r.currency())
                && !Set.of("CANCELLED", "RECEIVED").contains(pol.text("status")),
            "Purchase order line does not match this receipt.");
        check(
            pol.money("received_qty").add(input.quantity()).compareTo(pol.money("quantity")) <= 0,
            "Received quantity exceeds the purchase order remainder.");
        Sql.update(
            c,
            "UPDATE po_lines SET received_qty=received_qty+? WHERE id=?",
            input.quantity(),
            input.poLineId());
      }
      if (sale
          && input.unitPrice() != null
          && round(divide(p.money("price"), prep.rate()), prep.scale()).compareTo(input.unitPrice())
              != 0)
        Audit.log(
            c,
            u,
            "PRICE_OVERRIDE",
            "DOCUMENT_LINE",
            lineId,
            p.money("price").toPlainString(),
            input.unitPrice().toPlainString(),
            r.reason(),
            r.approver() == null ? null : r.approver().id());
    }
    Sql.update(c, "UPDATE documents SET cogs=? WHERE id=?", cogs, doc);
    if (sale)
      posting
          .mapped(c, "revenue", baseNet.negate())
          .mapped(c, "tax", baseTax.negate())
          .mapped(c, "cogs", cogs)
          .mapped(c, "inventory", cogs.negate());
    else posting.mapped(c, "input_tax", baseTax);
    for (Trade.Payment payment : r.payments()) {
      BigDecimal base = round(payment.amount().multiply(prep.rate()), prep.baseScale());
      CashService.payment(
          c, u, doc, r.currency(), prep.rate(), payment, sale ? 1 : -1, base, session);
      posting.add(
          Sql.one(c, "SELECT * FROM payment_methods WHERE code=?", payment.method())
              .text("account_code"),
          sale ? base : base.negate());
    }
    if (credit.signum() > 0) {
      DebtService.entry(c, r.partyId(), doc, r.currency(), credit, debtBase, r.due());
      posting.mapped(c, sale ? "ar" : "ap", sale ? debtBase : debtBase.negate());
      DebtService.allocate(c, u, r.partyId(), doc, r.currency(), posting, List.of());
    }
    {
      BigDecimal rounding = baseTotal.subtract(paidBase).subtract(debtBase);
      if (rounding.signum() != 0)
        posting.mapped(c, "cash_difference", sale ? rounding : rounding.negate());
    }
    AccountingService.post(
        c,
        u,
        doc,
        posting,
        r.kind() + " " + Sql.one(c, "SELECT number FROM documents WHERE id=?", doc).text("number"));
    if (r.heldId() != null) {
      Row held = Sql.one(c, "SELECT * FROM held_sales WHERE id=?", r.heldId());
      check(
          held.number("user_id") == u.id() || u.can(Permission.SETTINGS_USERS),
          "This held sale belongs to another cashier.");
      Sql.update(c, "DELETE FROM held_lines WHERE held_id=?", r.heldId());
      Sql.update(c, "DELETE FROM held_sales WHERE id=?", r.heldId());
    }
    if (r.orderId() != null) {
      long pending =
          Sql.count(
              c,
              "SELECT COUNT(*) FROM po_lines WHERE order_id=? AND received_qty<quantity",
              r.orderId());
      Sql.update(
          c,
          "UPDATE purchase_orders SET status=? WHERE id=?",
          pending == 0 ? "RECEIVED" : "PARTIALLY_RECEIVED",
          r.orderId());
    }
    Audit.log(
        c,
        u,
        r.kind() + "_COMPLETED",
        "DOCUMENT",
        doc,
        null,
        "Total " + t.total(),
        r.reason(),
        r.approver() == null ? null : r.approver().id());
    return result(c, doc);
  }

  public static Trade.Result result(Connection c, long id) throws SQLException {
    Row d = Sql.one(c, "SELECT * FROM documents WHERE id=?", id);
    Row pay =
        Sql.one(
            c,
            "SELECT COALESCE(SUM(amount),0) AS paid,COALESCE(SUM(change_amount),0) AS change FROM"
                + " payments WHERE document_id=?",
            id);
    return new Trade.Result(
        id,
        d.text("number"),
        d.money("total"),
        pay.money("paid"),
        d.money("total").subtract(pay.money("paid")),
        pay.money("change"),
        d.money("cogs"));
  }

  public long hold(User u, Trade.Request r) {
    u.require(Permission.SALE_CREATE);
    return db.tx(
        c -> {
          check(SettingsService.yes(c, "enable_hold"), "Held sales are disabled.");
          prepare(c, u, r);
          long id;
          if (r.heldId() != null) {
            Row old = Sql.one(c, "SELECT * FROM held_sales WHERE id=?", r.heldId());
            check(
                old.number("user_id") == u.id() || u.can(Permission.SETTINGS_USERS),
                "This cart belongs to another cashier.");
            id = r.heldId();
            Sql.update(c, "DELETE FROM held_lines WHERE held_id=?", id);
            Sql.edit(
                c,
                "held_sales",
                id,
                Sql.values(
                    "party_id",
                    r.partyId(),
                    "currency_code",
                    r.currency(),
                    "discount_value",
                    r.discount(),
                    "discount_percent",
                    r.discountPercent(),
                    "note",
                    r.notes()));
          } else
            id =
                Sql.insert(
                    c,
                    "held_sales",
                    Sql.values(
                        "number",
                        Documents.next(c, "HELD"),
                        "party_id",
                        r.partyId(),
                        "user_id",
                        u.id(),
                        "currency_code",
                        r.currency(),
                        "discount_value",
                        r.discount(),
                        "discount_percent",
                        r.discountPercent(),
                        "note",
                        r.notes()));
          Prepared p = prepare(c, u, r);
          for (int i = 0; i < r.lines().size(); i++) {
            Trade.Line l = r.lines().get(i);
            Sql.insert(
                c,
                "held_lines",
                Sql.values(
                    "held_id",
                    id,
                    "product_id",
                    l.productId(),
                    "quantity",
                    l.quantity(),
                    "price",
                    p.prices().get(i),
                    "discount_value",
                    l.discount(),
                    "discount_percent",
                    l.percent(),
                    "note",
                    l.note()));
          }
          Audit.log(c, u, "SALE_HELD", "HELD", id, "");
          return id;
        });
  }

  public void deleteHeld(User u, long id) {
    u.require(Permission.SALE_CREATE);
    db.tx(
        c -> {
          Row h = Sql.one(c, "SELECT * FROM held_sales WHERE id=?", id);
          check(
              h.number("user_id") == u.id() || u.can(Permission.SETTINGS_USERS),
              "This cart belongs to another cashier.");
          Sql.update(c, "DELETE FROM held_lines WHERE held_id=?", id);
          Sql.update(c, "DELETE FROM held_sales WHERE id=?", id);
          Audit.log(c, u, "HELD_DELETED", "HELD", id, "");
          return null;
        });
  }
}
