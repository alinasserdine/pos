package com.professionalpos.db;

import java.sql.*;
import java.util.*;

/** No connection ownership here: every business operation supplies its transaction connection. */
public final class Sql {
  private Sql() {}

  public static void bind(PreparedStatement s, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
  }

  public static List<Row> rows(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, values);
      try (ResultSet r = s.executeQuery()) {
        List<Row> result = new ArrayList<>();
        ResultSetMetaData m = r.getMetaData();
        while (r.next()) {
          Row row = new Row();
          for (int i = 1; i <= m.getColumnCount(); i++)
            row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT), r.getObject(i));
          result.add(row);
        }
        return result;
      }
    }
  }

  public static Row one(Connection c, String sql, Object... values) throws SQLException {
    List<Row> r = rows(c, sql, values);
    if (r.isEmpty()) throw new IllegalArgumentException("Record not found.");
    return r.get(0);
  }

  public static Row optional(Connection c, String sql, Object... values) throws SQLException {
    List<Row> r = rows(c, sql, values);
    return r.isEmpty() ? null : r.get(0);
  }

  public static long count(Connection c, String sql, Object... values) throws SQLException {
    return one(c, sql, values).values().stream()
        .mapToLong(v -> ((Number) v).longValue())
        .findFirst()
        .orElse(0);
  }

  public static int update(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, values);
      return s.executeUpdate();
    }
  }

  public static Map<String, Object> values(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  private static String identifier(String text) {
    if (!text.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("Invalid internal identifier");
    return text;
  }

  public static long insert(Connection c, String table, Map<String, Object> data)
      throws SQLException {
    String columns = String.join(",", data.keySet().stream().map(Sql::identifier).toList());
    String marks = String.join(",", Collections.nCopies(data.size(), "?"));
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO " + identifier(table) + " (" + columns + ") VALUES (" + marks + ")",
            Statement.RETURN_GENERATED_KEYS)) {
      bind(s, data.values().toArray());
      s.executeUpdate();
      try (ResultSet r = s.getGeneratedKeys()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  public static void edit(Connection c, String table, long id, Map<String, Object> data)
      throws SQLException {
    List<Object> vals = new ArrayList<>(data.values());
    vals.add(id);
    String sets = String.join(",", data.keySet().stream().map(k -> identifier(k) + "=?").toList());
    if (update(c, "UPDATE " + identifier(table) + " SET " + sets + " WHERE id=?", vals.toArray())
        != 1) throw new IllegalArgumentException("Record not found.");
  }
}
