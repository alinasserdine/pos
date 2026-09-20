package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public final class Documents {
  private Documents() {}

  public static String next(Connection c, String kind) throws SQLException {
    Row r = Sql.one(c, "SELECT * FROM document_sequences WHERE sequence_code=? FOR UPDATE", kind);
    long n = r.number("next_value");
    Sql.update(c, "UPDATE document_sequences SET next_value=? WHERE sequence_code=?", n + 1, kind);
    String prefix =
        switch (kind) {
          case "SALE" -> SettingsService.get(c, "invoice_prefix");
          case "SALE_RETURN" -> "RET";
          case "PURCHASE" -> "PUR";
          case "PURCHASE_RETURN" -> "PRT";
          case "CUSTOMER_PAYMENT" -> "RCV";
          case "SUPPLIER_PAYMENT" -> "PAY";
          case "JOURNAL" -> "JRN";
          default -> kind;
        };
    return prefix + "-" + String.format(Locale.ROOT, "%06d", n);
  }

  public static long create(
      Connection c,
      User u,
      String kind,
      LocalDate date,
      String currency,
      String key,
      Long partyId,
      Map<String, Object> more)
      throws SQLException {
    check(date != null, "Date is required.");
    check(
        Sql.count(
                c,
                "SELECT COUNT(*) FROM period_locks WHERE active=TRUE AND start_date<=? AND"
                    + " end_date>=?",
                date,
                date)
            == 0,
        "This accounting period is locked.");
    Row store = Sql.one(c, "SELECT * FROM business_profile");
    Map<String, Object> d =
        Sql.values(
            "kind",
            kind,
            "number",
            next(c, kind),
            "request_key",
            required(key, "Request key"),
            "document_date",
            date,
            "currency_code",
            currency,
            "rate_to_base",
            CurrencyService.rate(c, currency, date),
            "party_id",
            partyId,
            "created_by",
            u.id(),
            "store_name",
            store.text("store_name"),
            "store_address",
            store.text("address"),
            "store_phone",
            store.text("phone"),
            "store_tax_number",
            store.text("tax_number"),
            "logo_path",
            store.text("logo_path"),
            "footer",
            store.text("footer"));
    if (partyId != null) {
      Row p = Sql.one(c, "SELECT * FROM parties WHERE id=? AND active=TRUE", partyId);
      d.put("party_name", p.text("name"));
      d.put("party_address", p.text("address"));
      d.put("party_tax_number", p.text("tax_number"));
    } else d.put("party_name", "Walk-in Customer");
    d.putAll(more);
    return Sql.insert(c, "documents", d);
  }

  public static String key() {
    return UUID.randomUUID().toString();
  }
}
