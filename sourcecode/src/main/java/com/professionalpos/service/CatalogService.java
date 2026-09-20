package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.math.*;
import java.sql.*;
import java.util.*;

public final class CatalogService {
  private final Database db;

  public CatalogService(Database db) {
    this.db = db;
  }

  public List<Row> search(User u, String search, int offset) {
    u.require(Permission.POS_ACCESS);
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT"
                    + " p.id,p.sku,p.name,p.name_ar,p.price,p.on_hand,p.favorite,p.category_id,un.name"
                    + " AS unit_name FROM products p JOIN units un ON un.id=p.unit_id WHERE"
                    + " p.active=TRUE AND (LOWER(p.name) LIKE ? OR LOWER(p.name_ar) LIKE ? OR"
                    + " LOWER(p.sku) LIKE ? OR EXISTS(SELECT 1 FROM product_barcodes b WHERE"
                    + " b.product_id=p.id AND b.barcode LIKE ?)) ORDER BY p.favorite DESC,p.name"
                    + " OFFSET ? ROWS FETCH NEXT 50 ROWS ONLY",
                like(search),
                like(search),
                like(search),
                "%" + search + "%",
                Math.max(0, offset)));
  }

  public Row barcode(User u, String barcode) {
    u.require(Permission.POS_ACCESS);
    return db.read(
        c ->
            Sql.optional(
                c,
                "SELECT p.id,p.name,p.name_ar,p.sku,p.price,un.name AS unit_name FROM products p"
                    + " JOIN units un ON un.id=p.unit_id JOIN product_barcodes b ON"
                    + " b.product_id=p.id WHERE b.barcode=? AND p.active=TRUE",
                barcode.trim()));
  }

  public static String like(String s) {
    return "%" + s.toLowerCase(Locale.ROOT) + "%";
  }

  private static final Set<String> PRODUCT_FIELDS =
      Set.of(
          "sku",
          "name",
          "name_ar",
          "description",
          "brand",
          "category_id",
          "unit_id",
          "tax_id",
          "preferred_supplier_id",
          "parent_product_id",
          "variant_label",
          "price",
          "wholesale_price",
          "min_stock",
          "reorder_level",
          "max_stock",
          "track_stock",
          "allow_negative",
          "fractional",
          "favorite");

  public long product(
      User u,
      Long id,
      Map<String, Object> fields,
      List<String> barcodes,
      BigDecimal cost,
      BigDecimal opening,
      String reason) {
    u.require(id == null ? Permission.PRODUCT_CREATE : Permission.PRODUCT_EDIT);
    return db.tx(c -> product(c, u, id, fields, barcodes, cost, opening, reason));
  }

  long product(
      Connection c,
      User u,
      Long id,
      Map<String, Object> fields,
      List<String> barcodes,
      BigDecimal cost,
      BigDecimal opening,
      String reason)
      throws SQLException {
    check(PRODUCT_FIELDS.containsAll(fields.keySet()), "Invalid product field.");
    required(String.valueOf(fields.getOrDefault("sku", "")), "SKU");
    required(String.valueOf(fields.getOrDefault("name", "")), "Name");
    nonnegative((BigDecimal) fields.get("price"), "Selling price");
    nonnegative(cost, "Cost");
    nonnegative(opening, "Opening stock");
    for (String k : List.of("min_stock", "reorder_level"))
      if (fields.containsKey(k)) nonnegative((BigDecimal) fields.get(k), k);
    Map<String, Object> data = new LinkedHashMap<>(fields);
    Row old = id == null ? null : Sql.one(c, "SELECT * FROM products WHERE id=?", id);
    List<String> codes = barcodes.stream().map(String::trim).filter(v -> !v.isBlank()).toList();
    check(new HashSet<>(codes).size() == codes.size(), "Duplicate barcode in this form.");
    check(
        Sql.count(
                c,
                "SELECT COUNT(*) FROM products WHERE sku=? AND id<>?",
                fields.get("sku"),
                id == null ? 0 : id)
            == 0,
        "SKU already exists.");
    for (String code : codes)
      check(
          Sql.count(
                  c,
                  "SELECT COUNT(*) FROM product_barcodes WHERE barcode=? AND product_id<>?",
                  code,
                  id == null ? 0 : id)
              == 0,
          "Barcode already exists: " + code);
    if (old != null) {
      check(opening.signum() == 0, "Use stock adjustment to change existing quantities.");
      check(
          old.money("on_hand").signum() == 0 || old.money("current_cost").compareTo(cost) == 0,
          "Cost changes with stock require a purchase or inventory revaluation.");
      check(
          old.money("on_hand").signum() == 0
              || old.flag("track_stock") == Boolean.TRUE.equals(fields.get("track_stock")),
          "Clear existing stock before changing inventory tracking.");
      data.put("current_cost", cost);
      data.put("updated_at", java.time.LocalDateTime.now());
      Sql.edit(c, "products", id, data);
      Sql.insert(
          c,
          "price_history",
          Sql.values(
              "product_id",
              id,
              "old_price",
              old.money("price"),
              "new_price",
              fields.get("price"),
              "old_cost",
              old.money("current_cost"),
              "new_cost",
              cost,
              "user_id",
              u.id(),
              "reason",
              required(reason, "Reason")));
    } else {
      data.put("current_cost", cost);
      data.put("last_cost", cost);
      id = Sql.insert(c, "products", data);
    }
    Sql.update(c, "DELETE FROM product_barcodes WHERE product_id=?", id);
    for (int i = 0; i < codes.size(); i++)
      Sql.insert(
          c,
          "product_barcodes",
          Sql.values("product_id", id, "barcode", codes.get(i), "is_primary", i == 0));
    if (opening.signum() > 0) {
      check(
          Boolean.TRUE.equals(fields.get("track_stock")),
          "Opening stock requires inventory tracking.");
      long doc =
          Documents.create(
              c,
              u,
              "OPENING",
              SettingsService.today(c),
              SettingsService.get(c, "base_currency"),
              Documents.key(),
              null,
              Sql.values("reason", "Opening stock"));
      BigDecimal value =
          InventoryService.move(
              c,
              u,
              id,
              doc,
              null,
              opening,
              opening.multiply(cost),
              "OPENING_STOCK",
              "Opening stock");
      AccountingService.post(
          c,
          u,
          doc,
          new Posting().mapped(c, "inventory", value).mapped(c, "capital", value.negate()),
          "Opening stock");
    }
    Audit.log(c, u, old == null ? "PRODUCT_CREATED" : "PRODUCT_UPDATED", "PRODUCT", id, reason);
    return id;
  }

  public void archive(User u, long id, boolean stockAcknowledged) {
    u.require(Permission.PRODUCT_ARCHIVE);
    db.tx(
        c -> {
          Row p = Sql.one(c, "SELECT * FROM products WHERE id=?", id);
          check(
              p.money("on_hand").signum() == 0 || stockAcknowledged,
              "This product still has stock. Explicitly confirm to archive while retaining its"
                  + " inventory value.");
          Sql.update(c, "UPDATE products SET active=FALSE WHERE id=?", id);
          Audit.log(
              c, u, "PRODUCT_ARCHIVED", "PRODUCT", id, "Stock retained: " + p.money("on_hand"));
          return null;
        });
  }

  public void restoreProduct(User u, long id) {
    u.require(Permission.PRODUCT_EDIT);
    db.tx(
        c -> {
          Sql.update(c, "UPDATE products SET active=TRUE WHERE id=?", id);
          Audit.log(c, u, "PRODUCT_REACTIVATED", "PRODUCT", id, "");
          return null;
        });
  }

  public long party(User u, Long id, String kind, Map<String, Object> fields, BigDecimal opening) {
    u.require(kind.equals("CUSTOMER") ? Permission.CUSTOMER_EDIT : Permission.PURCHASE_CREATE);
    if (opening.signum() != 0) u.require(Permission.ACCOUNTING_POST);
    return db.tx(
        c -> {
          Set<String> allowed =
              Set.of(
                  "code",
                  "name",
                  "company",
                  "contact",
                  "phone",
                  "email",
                  "address",
                  "tax_number",
                  "currency_code",
                  "credit_limit",
                  "terms_days",
                  "discount_pct",
                  "notes",
                  "active");
          check(allowed.containsAll(fields.keySet()), "Invalid contact field.");
          Map<String, Object> d = new LinkedHashMap<>(fields);
          d.put("kind", kind);
          required(String.valueOf(d.getOrDefault("name", "")), "Name");
          required(String.valueOf(d.getOrDefault("code", "")), "Code");
          long saved;
          if (id == null) saved = Sql.insert(c, "parties", d);
          else {
            check(
                opening.signum() == 0,
                "Use a documented payment or adjustment for existing contacts.");
            Sql.edit(c, "parties", id, d);
            saved = id;
          }
          if (opening.signum() != 0) {
            String curr = String.valueOf(d.get("currency_code"));
            var date = SettingsService.today(c);
            BigDecimal base =
                round(
                    opening.multiply(CurrencyService.rate(c, curr, date)),
                    SettingsService.baseScale(c));
            long doc =
                Documents.create(
                    c,
                    u,
                    "OPENING",
                    date,
                    curr,
                    Documents.key(),
                    saved,
                    Sql.values(
                        "total", opening, "base_total", base, "notes", "Opening account balance"));
            Sql.insert(
                c,
                "party_entries",
                Sql.values(
                    "party_id",
                    saved,
                    "document_id",
                    doc,
                    "currency_code",
                    curr,
                    "amount",
                    opening,
                    "base_amount",
                    base,
                    "open_amount",
                    opening,
                    "open_base",
                    base,
                    "due_date",
                    date));
            Posting p = new Posting();
            if (kind.equals("CUSTOMER"))
              p.mapped(c, "ar", base).mapped(c, "capital", base.negate());
            else p.mapped(c, "ap", base.negate()).mapped(c, "capital", base);
            AccountingService.post(c, u, doc, p, "Opening account balance");
          }
          Audit.log(c, u, "PARTY_SAVED", "PARTY", saved, kind);
          return saved;
        });
  }

  public long reference(User u, String type, Map<String, Object> fields) {
    u.require(type.equals("tax_rates") ? Permission.SETTINGS_TAX : Permission.PRODUCT_EDIT);
    check(Set.of("categories", "units", "tax_rates").contains(type), "Invalid reference type.");
    return db.tx(
        c -> {
          Set<String> allowed =
              switch (type) {
                case "categories" -> Set.of("name", "name_ar", "tax_id");
                case "units" -> Set.of("name", "name_ar", "fractional");
                default -> Set.of("name", "rate");
              };
          check(allowed.containsAll(fields.keySet()), "Invalid reference field.");
          long id = Sql.insert(c, type, fields);
          Audit.log(c, u, "REFERENCE_CREATED", type, id, "");
          return id;
        });
  }
}
