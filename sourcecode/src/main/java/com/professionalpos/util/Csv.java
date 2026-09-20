package com.professionalpos.util;

import com.professionalpos.db.Row;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** RFC 4180 quoting, including embedded commas, quotes and newlines. */
public final class Csv {
  private Csv() {}

  public static void write(Path file, List<String> headers, List<Row> rows) throws IOException {
    try (Writer out = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
      out.write('\uFEFF');
      line(out, headers);
      for (Row row : rows) {
        List<String> fields = new ArrayList<>();
        for (String h : headers) {
          Object value = row.get(h);
          String text = value == null ? "" : value.toString();
          if (!(value instanceof Number) && text.matches("(?s)^[=+@-].*")) text = "'" + text;
          fields.add(text);
        }
        line(out, fields);
      }
    }
  }

  private static void line(Writer out, List<String> fields) throws IOException {
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) out.write(',');
      out.write('"');
      out.write(fields.get(i).replace("\"", "\"\""));
      out.write('"');
    }
    out.write("\r\n");
  }

  public static List<List<String>> read(Path file) throws IOException {
    try (PushbackReader reader =
        new PushbackReader(Files.newBufferedReader(file, StandardCharsets.UTF_8), 2)) {
      List<List<String>> rows = new ArrayList<>();
      List<String> row = new ArrayList<>();
      StringBuilder field = new StringBuilder();
      boolean quoted = false, started = false;
      int value;
      long chars = 0;
      while ((value = reader.read()) != -1) {
        if (++chars > 50_000_000) throw new IOException("CSV is too large.");
        char c = (char) value;
        if (chars == 1 && c == '\uFEFF') continue;
        if (quoted) {
          if (c == '"') {
            int next = reader.read();
            if (next == '"') field.append('"');
            else {
              quoted = false;
              if (next != -1) reader.unread(next);
            }
          } else field.append(c);
        } else if (c == '"' && !started) {
          quoted = true;
          started = true;
        } else if (c == ',') {
          row.add(field.toString());
          field.setLength(0);
          started = false;
        } else if (c == '\n' || c == '\r') {
          if (c == '\r') {
            int next = reader.read();
            if (next != '\n' && next != -1) reader.unread(next);
          }
          row.add(field.toString());
          rows.add(List.copyOf(row));
          row.clear();
          field.setLength(0);
          started = false;
        } else {
          field.append(c);
          started = true;
        }
      }
      if (quoted) throw new IOException("Unclosed quoted CSV field.");
      if (!row.isEmpty() || field.length() > 0) {
        row.add(field.toString());
        rows.add(List.copyOf(row));
      }
      return rows;
    }
  }
}
