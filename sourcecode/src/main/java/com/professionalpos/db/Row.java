package com.professionalpos.db;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** A JDBC projection; domain requests and results are typed records. */
public final class Row extends LinkedHashMap<String, Object> {
  public String text(String key) {
    Object v = get(key.toLowerCase(Locale.ROOT));
    return v == null ? "" : v.toString();
  }

  public long id() {
    return number("id");
  }

  public long number(String key) {
    Object v = get(key.toLowerCase(Locale.ROOT));
    return v == null ? 0 : ((Number) v).longValue();
  }

  public Long nullableId(String key) {
    return get(key) == null ? null : number(key);
  }

  public BigDecimal money(String key) {
    Object v = get(key.toLowerCase(Locale.ROOT));
    return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
  }

  public boolean flag(String key) {
    Object v = get(key);
    return Boolean.TRUE.equals(v) || "1".equals(String.valueOf(v));
  }

  public LocalDate date(String key) {
    Object v = get(key);
    return v == null
        ? null
        : v instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(v.toString());
  }
}
