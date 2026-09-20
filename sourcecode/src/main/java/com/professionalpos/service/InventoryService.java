package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class InventoryService {
  private final Database db;

  public InventoryService(Database db) {
    this.db = db;
  }

  /**
   * Receipts carry their known historical cost. Issues calculate cost from the configured method.
   */
  public static BigDecimal move(
      Connection c,
      User u,
      long product,
      long doc,
      Long line,
      BigDecimal delta,
      BigDecimal incomingValue,
      String type,
      String reason)
      throws SQLException {
    Row p = Sql.one(c, "SELECT * FROM products WHERE id=? FOR UPDATE", product);
    if (!p.flag("track_stock")) return ZERO;
    check(delta.scale() <= 6, "Quantity supports at most 6 decimals.");
    if (!p.flag("fractional"))
      check(
          delta.stripTrailingZeros().scale() <= 0,
          "This product requires a whole-number quantity.");
    BigDecimal before = p.money("on_hand"), after = before.add(delta), value = ZERO;
    int scale = SettingsService.baseScale(c);
    boolean fifo = SettingsService.get(c, "costing").equals("FIFO");
    check(
        after.signum() >= 0
            || p.flag("allow_negative") && SettingsService.yes(c, "allow_negative") && !fifo,
        "Insufficient stock. FIFO does not permit negative stock.");
    BigDecimal receiptValue = ZERO;
    if (delta.signum() > 0) {
      receiptValue = round(nonnegative(incomingValue, "Inventory cost"), scale);
      value = receiptValue;
      if (!fifo && before.signum() < 0) {
        BigDecimal covered = delta.min(before.negate());
        BigDecimal receivedUnit = divide(value, delta);
        value =
            value.add(
                round(covered.multiply(p.money("current_cost").subtract(receivedUnit)), scale));
      }
    } else if (delta.signum() < 0) {
      BigDecimal qty = delta.negate();
      if (fifo) {
        for (Row layer :
            Sql.rows(
                c,
                "SELECT * FROM cost_layers WHERE product_id=? AND remaining_qty>0 ORDER BY id FOR"
                    + " UPDATE",
                product)) {
          if (qty.signum() == 0) break;
          BigDecimal take = qty.min(layer.money("remaining_qty"));
          BigDecimal used =
              take.compareTo(layer.money("remaining_qty")) == 0
                  ? layer.money("remaining_value")
                  : round(
                      divide(
                          layer.money("remaining_value").multiply(take),
                          layer.money("remaining_qty")),
                      scale);
          value = value.subtract(used);
          qty = qty.subtract(take);
        }
        check(
            qty.signum() == 0, "Inventory cost layers are inconsistent. Run the integrity report.");
      } else
        value =
            after.signum() == 0
                ? p.money("stock_value").negate()
                : round(qty.multiply(p.money("current_cost")), scale).negate();
    }
    long movement =
        Sql.insert(
            c,
            "stock_movements",
            Sql.values(
                "product_id",
                product,
                "document_id",
                doc,
                "document_line_id",
                line,
                "movement_type",
                type,
                "quantity",
                delta,
                "before_qty",
                before,
                "after_qty",
                after,
                "cost_value",
                value,
                "reason",
                reason,
                "user_id",
                u.id()));
    if (fifo && delta.signum() > 0)
      Sql.insert(
          c,
          "cost_layers",
          Sql.values(
              "product_id",
              product,
              "movement_id",
              movement,
              "original_quantity",
              delta,
              "remaining_qty",
              delta,
              "original_value",
              value,
              "remaining_value",
              value));
    if (fifo && delta.signum() < 0) {
      BigDecimal qty = delta.negate();
      for (Row layer :
          Sql.rows(
              c,
              "SELECT * FROM cost_layers WHERE product_id=? AND remaining_qty>0 ORDER BY id FOR"
                  + " UPDATE",
              product)) {
        if (qty.signum() == 0) break;
        BigDecimal take = qty.min(layer.money("remaining_qty")),
            used =
                take.compareTo(layer.money("remaining_qty")) == 0
                    ? layer.money("remaining_value")
                    : round(
                        divide(
                            layer.money("remaining_value").multiply(take),
                            layer.money("remaining_qty")),
                        scale);
        Sql.update(
            c,
            "UPDATE cost_layers SET remaining_qty=remaining_qty-?,remaining_value=remaining_value-?"
                + " WHERE id=?",
            take,
            used,
            layer.id());
        Sql.insert(
            c,
            "cost_consumptions",
            Sql.values(
                "movement_id",
                movement,
                "layer_id",
                layer.id(),
                "quantity",
                take,
                "cost_value",
                used));
        qty = qty.subtract(take);
      }
    }
    BigDecimal newValue = p.money("stock_value").add(value),
        avg = after.signum() > 0 ? round(divide(newValue, after), 6) : p.money("current_cost");
    check(
        avg.signum() >= 0, "Negative inventory valuation requires a controlled stock correction.");
    Sql.update(
        c,
        "UPDATE products SET on_hand=?,stock_value=?,current_cost=?,updated_at=CURRENT_TIMESTAMP"
            + " WHERE id=?",
        after,
        newValue,
        avg,
        product);
    if (delta.signum() > 0)
      // Keep the supplier's actual receipt cost. Inventory value may differ when a receipt covers
      // negative stock, because that variance is posted separately to inventory loss/revaluation.
      Sql.update(
          c,
          "UPDATE products SET last_cost=? WHERE id=?",
          round(divide(receiptValue, delta), 6),
          product);
    return value;
  }

  public long adjust(User u, long product, BigDecimal counted, String reason) {
    u.require(Permission.INVENTORY_ADJUST);
    required(reason, "Reason");
    nonnegative(counted, "Counted quantity");
    return db.tx(
        c -> {
          Row p = Sql.one(c, "SELECT * FROM products WHERE id=?", product);
          BigDecimal delta = counted.subtract(p.money("on_hand"));
          check(delta.signum() != 0, "Stock is already equal to that quantity.");
          long id =
              Documents.create(
                  c,
                  u,
                  "ADJUSTMENT",
                  SettingsService.today(c),
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("reason", reason));
          BigDecimal cost =
              move(
                  c,
                  u,
                  product,
                  id,
                  null,
                  delta,
                  delta.multiply(p.money("current_cost")).abs(),
                  delta.signum() > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT",
                  reason);
          Posting posting =
              new Posting().mapped(c, "inventory", cost).mapped(c, "inventory_loss", cost.negate());
          AccountingService.post(c, u, id, posting, reason);
          Audit.log(c, u, "INVENTORY_ADJUSTED", "PRODUCT", product, reason);
          return id;
        });
  }

  /** Changes inventory carrying value without changing quantity or its audit trail. */
  public long revalue(
      User u, long product, BigDecimal newValue, LocalDate date, String reason, String requestKey) {
    u.require(Permission.INVENTORY_ADJUST);
    nonnegative(newValue, "New inventory value");
    required(reason, "Reason");
    return db.tx(
        c -> {
          Row prior = Sql.optional(c, "SELECT id FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          Row p = Sql.one(c, "SELECT * FROM products WHERE id=? FOR UPDATE", product);
          check(p.flag("track_stock") && p.money("on_hand").signum() > 0, "Product has no stock to revalue.");
          int scale = SettingsService.baseScale(c);
          BigDecimal oldValue = p.money("stock_value"), target = round(newValue, scale);
          BigDecimal ceiling =
              Sql.one(
                      c,
                      "SELECT COALESCE(MAX(old_value),?) AS amount FROM inventory_revaluations"
                          + " WHERE product_id=?",
                      oldValue,
                      product)
                  .money("amount")
                  .max(oldValue);
          check(target.compareTo(ceiling) <= 0, "A write-down reversal cannot exceed original cost.");
          BigDecimal difference = target.subtract(oldValue);
          check(difference.signum() != 0, "Inventory is already recorded at that value.");
          long doc =
              Documents.create(
                  c,
                  u,
                  "ADJUSTMENT",
                  date,
                  SettingsService.get(c, "base_currency"),
                  requestKey,
                  null,
                  Sql.values("reason", reason));
          Sql.insert(
              c,
              "stock_movements",
              Sql.values(
                  "product_id",
                  product,
                  "document_id",
                  doc,
                  "movement_type",
                  "REVALUATION",
                  "quantity",
                  ZERO,
                  "before_qty",
                  p.money("on_hand"),
                  "after_qty",
                  p.money("on_hand"),
                  "cost_value",
                  difference,
                  "reason",
                  reason,
                  "user_id",
                  u.id()));
          if (SettingsService.get(c, "costing").equals("FIFO"))
            revalueFifoLayers(c, product, target, scale);
          Sql.update(
              c,
              "UPDATE products SET stock_value=?,current_cost=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
              target,
              round(divide(target, p.money("on_hand")), 6),
              product);
          Sql.insert(
              c,
              "inventory_revaluations",
              Sql.values(
                  "document_id",
                  doc,
                  "product_id",
                  product,
                  "old_value",
                  oldValue,
                  "new_value",
                  target,
                  "reason",
                  reason,
                  "created_by",
                  u.id()));
          AccountingService.post(
              c,
              u,
              doc,
              new Posting()
                  .mapped(c, "inventory", difference)
                  .mapped(c, "inventory_loss", difference.negate()),
              reason);
          Audit.log(c, u, "INVENTORY_REVALUED", "PRODUCT", product, oldValue + " -> " + target);
          return doc;
        });
  }

  private static void revalueFifoLayers(Connection c, long product, BigDecimal target, int scale)
      throws SQLException {
    List<Row> layers =
        Sql.rows(
            c,
            "SELECT * FROM cost_layers WHERE product_id=? AND remaining_qty>0 ORDER BY id FOR UPDATE",
            product);
    BigDecimal current =
        layers.stream().map(r -> r.money("remaining_value")).reduce(ZERO, BigDecimal::add);
    check(current.signum() > 0 || target.signum() == 0, "FIFO layers have no value to revalue.");
    BigDecimal allocated = ZERO;
    for (int i = 0; i < layers.size(); i++) {
      Row layer = layers.get(i);
      BigDecimal value =
          i == layers.size() - 1
              ? target.subtract(allocated)
              : round(divide(target.multiply(layer.money("remaining_value")), current), scale);
      Sql.update(c, "UPDATE cost_layers SET remaining_value=? WHERE id=?", value, layer.id());
      allocated = allocated.add(value);
    }
  }

  /** Values a damaged-return batch after inspection, capped at its historical cost. */
  public long valueDamaged(
      User u, long damagedId, BigDecimal newValue, LocalDate date, String reason, String requestKey) {
    u.require(Permission.INVENTORY_ADJUST);
    nonnegative(newValue, "Damaged inventory value");
    required(reason, "Reason");
    return db.tx(
        c -> {
          Row prior = Sql.optional(c, "SELECT id FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          Row item =
              Sql.one(c, "SELECT * FROM damaged_inventory WHERE id=? FOR UPDATE", damagedId);
          check(!item.text("status").equals("DISPOSED"), "Damaged inventory is already disposed.");
          int scale = SettingsService.baseScale(c);
          BigDecimal cap =
                  round(
                      divide(
                          item.money("historical_cost").multiply(item.money("remaining_quantity")),
                          item.money("quantity")),
                      scale),
              target = round(newValue, scale),
              difference = target.subtract(item.money("carrying_value"));
          check(target.compareTo(cap) <= 0, "Damaged inventory cannot exceed historical cost.");
          check(difference.signum() != 0, "Damaged inventory is already recorded at that value.");
          long doc =
              Documents.create(
                  c,
                  u,
                  "ADJUSTMENT",
                  date,
                  SettingsService.get(c, "base_currency"),
                  requestKey,
                  null,
                  Sql.values("reason", reason));
          AccountingService.post(
              c,
              u,
              doc,
              new Posting()
                  .mapped(c, "damaged", difference)
                  .mapped(c, "inventory_loss", difference.negate()),
              reason);
          Sql.update(
              c,
              "UPDATE damaged_inventory SET carrying_value=?,status='VALUED' WHERE id=?",
              target,
              damagedId);
          Sql.insert(
              c,
              "damaged_inventory_events",
              Sql.values(
                  "damaged_inventory_id",
                  damagedId,
                  "document_id",
                  doc,
                  "event_type",
                  "VALUATION",
                  "quantity",
                  ZERO,
                  "value_change",
                  difference,
                  "reason",
                  reason,
                  "created_by",
                  u.id()));
          Audit.log(c, u, "DAMAGED_INVENTORY_VALUED", "DAMAGED_INVENTORY", damagedId, reason);
          return doc;
        });
  }

  /** Removes damaged units and their carrying value from the damaged-inventory control account. */
  public long disposeDamaged(
      User u, long damagedId, BigDecimal quantity, LocalDate date, String reason, String requestKey) {
    u.require(Permission.INVENTORY_ADJUST);
    positive(quantity, "Quantity");
    required(reason, "Reason");
    return db.tx(
        c -> {
          Row prior = Sql.optional(c, "SELECT id FROM documents WHERE request_key=?", requestKey);
          if (prior != null) return prior.id();
          Row item =
              Sql.one(c, "SELECT * FROM damaged_inventory WHERE id=? FOR UPDATE", damagedId);
          BigDecimal remaining = item.money("remaining_quantity");
          check(quantity.compareTo(remaining) <= 0, "Disposal exceeds damaged quantity.");
          int scale = SettingsService.baseScale(c);
          BigDecimal released =
              quantity.compareTo(remaining) == 0
                  ? item.money("carrying_value")
                  : round(divide(item.money("carrying_value").multiply(quantity), remaining), scale);
          long doc =
              Documents.create(
                  c,
                  u,
                  "ADJUSTMENT",
                  date,
                  SettingsService.get(c, "base_currency"),
                  requestKey,
                  null,
                  Sql.values("reason", reason));
          if (released.signum() != 0)
            AccountingService.post(
                c,
                u,
                doc,
                new Posting()
                    .mapped(c, "inventory_loss", released)
                    .mapped(c, "damaged", released.negate()),
                reason);
          else
            AccountingService.post(
                c,
                u,
                doc,
                new Posting().mapped(c, "damaged", ZERO).mapped(c, "inventory_loss", ZERO),
                reason);
          BigDecimal left = remaining.subtract(quantity), valueLeft = item.money("carrying_value").subtract(released);
          Sql.update(
              c,
              "UPDATE damaged_inventory SET remaining_quantity=?,carrying_value=?,status=? WHERE id=?",
              left,
              valueLeft,
              left.signum() == 0 ? "DISPOSED" : item.text("status"),
              damagedId);
          Sql.insert(
              c,
              "damaged_inventory_events",
              Sql.values(
                  "damaged_inventory_id",
                  damagedId,
                  "document_id",
                  doc,
                  "event_type",
                  "DISPOSAL",
                  "quantity",
                  quantity,
                  "value_change",
                  released.negate(),
                  "reason",
                  reason,
                  "created_by",
                  u.id()));
          Audit.log(c, u, "DAMAGED_INVENTORY_DISPOSED", "DAMAGED_INVENTORY", damagedId, reason);
          return doc;
        });
  }

  public long startCount(User u) {
    u.require(Permission.INVENTORY_ADJUST);
    return db.tx(
        c ->
            Sql.insert(
                c,
                "stock_counts",
                Sql.values(
                    "number",
                    Documents.next(c, "COUNT"),
                    "status",
                    "DRAFT",
                    "created_by",
                    u.id())));
  }

  public void count(User u, long count, long product, BigDecimal quantity) {
    u.require(Permission.INVENTORY_ADJUST);
    nonnegative(quantity, "Counted quantity");
    db.tx(
        c -> {
          check(
              Sql.one(c, "SELECT * FROM stock_counts WHERE id=?", count)
                  .text("status")
                  .equals("DRAFT"),
              "This stock count is already posted.");
          Row p = Sql.one(c, "SELECT * FROM products WHERE id=? AND track_stock=TRUE", product);
          Row existing =
              Sql.optional(
                  c,
                  "SELECT * FROM stock_count_lines WHERE count_id=? AND product_id=?",
                  count,
                  product);
          if (existing == null)
            Sql.insert(
                c,
                "stock_count_lines",
                Sql.values(
                    "count_id",
                    count,
                    "product_id",
                    product,
                    "expected",
                    p.money("on_hand"),
                    "counted",
                    quantity));
          else
            Sql.update(
                c, "UPDATE stock_count_lines SET counted=? WHERE id=?", quantity, existing.id());
          return null;
        });
  }

  public long postCount(User u, long count) {
    u.require(Permission.INVENTORY_ADJUST);
    return db.tx(
        c -> {
          check(
              Sql.one(c, "SELECT * FROM stock_counts WHERE id=?", count)
                  .text("status")
                  .equals("DRAFT"),
              "This stock count is already posted.");
          List<Row> rows =
              Sql.rows(
                  c,
                  "SELECT l.*,p.on_hand,p.current_cost FROM stock_count_lines l JOIN products p ON"
                      + " p.id=l.product_id WHERE count_id=?",
                  count);
          check(!rows.isEmpty(), "Add counted products first.");
          for (Row r : rows)
            check(
                r.money("on_hand").compareTo(r.money("expected")) == 0,
                "Stock changed during the count. Recount the affected product in a new session.");
          long doc =
              Documents.create(
                  c,
                  u,
                  "ADJUSTMENT",
                  SettingsService.today(c),
                  SettingsService.get(c, "base_currency"),
                  Documents.key(),
                  null,
                  Sql.values("reason", "Stock count " + count));
          BigDecimal sum = ZERO;
          for (Row r : rows) {
            BigDecimal delta = r.money("counted").subtract(r.money("expected"));
            if (delta.signum() != 0)
              sum =
                  sum.add(
                      move(
                          c,
                          u,
                          r.number("product_id"),
                          doc,
                          null,
                          delta,
                          delta.multiply(r.money("current_cost")).abs(),
                          "STOCK_COUNT",
                          "Count " + count));
          }
          AccountingService.post(
              c,
              u,
              doc,
              new Posting().mapped(c, "inventory", sum).mapped(c, "inventory_loss", sum.negate()),
              "Stock count " + count);
          Sql.update(
              c,
              "UPDATE stock_counts SET status='POSTED',posted_document_id=? WHERE id=?",
              doc,
              count);
          Audit.log(c, u, "STOCK_COUNT_POSTED", "COUNT", count, "");
          return doc;
        });
  }
}
