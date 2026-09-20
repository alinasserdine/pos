package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import com.professionalpos.util.Csv;
import java.math.*;
import java.nio.file.*;
import java.util.*;

public final class ImportService {
  public record Preview(List<Map<String, String>> records, List<String> errors) {
    public Preview {
      records = List.copyOf(records);
      errors = List.copyOf(errors);
    }
  }

  private final Database db;

  public ImportService(Database db) {
    this.db = db;
  }

  public Preview preview(User u, Path file) throws Exception {
    u.require(Permission.PRODUCT_CREATE);
    List<List<String>> data = Csv.read(file);
    check(data.size() > 1, "CSV has no product rows.");
    List<String> headers =
        data.get(0).stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
    for (String k : List.of("sku", "barcode", "name", "cost", "price", "stock"))
      check(headers.contains(k), "CSV column missing: " + k);
    List<Map<String, String>> records = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    Set<String> skus = new HashSet<>(), barcodes = new HashSet<>();
    for (int i = 1; i < data.size(); i++) {
      int line = i + 1;
      try {
        check(data.get(i).size() == headers.size(), "Column count differs from header.");
        Map<String, String> row = new LinkedHashMap<>();
        for (int j = 0; j < headers.size(); j++) row.put(headers.get(j), data.get(i).get(j).trim());
        required(row.get("sku"), "SKU");
        required(row.get("name"), "Name");
        check(skus.add(row.get("sku")), "Duplicate SKU in CSV.");
        if (!row.get("barcode").isEmpty())
          check(barcodes.add(row.get("barcode")), "Duplicate barcode in CSV.");
        for (String k : List.of("cost", "price", "stock")) nonnegative(of(row.get(k)), k);
        db.read(
            c -> {
              check(
                  Sql.count(c, "SELECT COUNT(*) FROM products WHERE sku=?", row.get("sku")) == 0,
                  "SKU already exists.");
              if (!row.get("barcode").isEmpty())
                check(
                    Sql.count(
                            c,
                            "SELECT COUNT(*) FROM product_barcodes WHERE barcode=?",
                            row.get("barcode"))
                        == 0,
                    "Barcode already exists.");
              return null;
            });
        records.add(row);
      } catch (Exception e) {
        errors.add("Line " + line + ": " + e.getMessage());
      }
    }
    return new Preview(records, errors);
  }

  public int apply(User u, Preview preview) {
    u.require(Permission.PRODUCT_CREATE);
    check(preview.errors().isEmpty(), "Fix CSV errors before importing.");
    return db.tx(
        c -> {
          CatalogService catalog = new CatalogService(db);
          for (Map<String, String> r : preview.records()) {
            long
                unit =
                    Sql.one(c, "SELECT * FROM units WHERE name=?", r.getOrDefault("unit", "Piece"))
                        .id(),
                tax =
                    Sql.one(
                            c,
                            "SELECT * FROM tax_rates WHERE name=?",
                            r.getOrDefault("tax", "Exempt"))
                        .id(),
                category =
                    Sql.one(
                            c,
                            "SELECT * FROM categories WHERE name=?",
                            r.getOrDefault("category", "Other"))
                        .id();
            boolean fractional =
                Sql.one(c, "SELECT * FROM units WHERE id=?", unit).flag("fractional");
            catalog.product(
                c,
                u,
                null,
                Sql.values(
                    "sku",
                    r.get("sku"),
                    "name",
                    r.get("name"),
                    "name_ar",
                    r.getOrDefault("name_ar", ""),
                    "unit_id",
                    unit,
                    "tax_id",
                    tax,
                    "category_id",
                    category,
                    "price",
                    of(r.get("price")),
                    "track_stock",
                    true,
                    "fractional",
                    fractional,
                    "min_stock",
                    of(r.getOrDefault("min_stock", "0")),
                    "reorder_level",
                    of(r.getOrDefault("reorder_level", "0"))),
                List.of(r.get("barcode")),
                of(r.get("cost")),
                of(r.get("stock")),
                "CSV import");
          }
          Audit.log(c, u, "CATALOG_IMPORTED", "PRODUCT", 0, preview.records().size() + " products");
          return preview.records().size();
        });
  }
}
