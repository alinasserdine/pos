package com.professionalpos.ui;

import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class ChartPanel extends JPanel {
  private List<Row> rows = List.of();
  private List<String> values = List.of();
  private String label = "", title = "";
  private boolean bars;
  private Runnable drill;
  private static final Color[] COLORS = {
    Theme.ACCENT, new Color(64, 115, 174), new Color(217, 129, 51)
  };

  public ChartPanel() {
    setBackground(Color.WHITE);
    setPreferredSize(new Dimension(640, 280));
    setToolTipText("");
    addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            if (drill != null) drill.run();
          }
        });
  }

  public void data(
      String title,
      List<Row> data,
      String label,
      List<String> values,
      boolean bars,
      Runnable drill) {
    this.title = title;
    this.rows = List.copyOf(data);
    this.label = label;
    this.values = List.copyOf(values);
    this.bars = bars;
    this.drill = drill;
    repaint();
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    if (rows.isEmpty()) return I18n.t("no_data");
    int index =
        Math.max(
            0,
            Math.min(
                rows.size() - 1, (e.getX() - 65) * rows.size() / Math.max(1, getWidth() - 90)));
    Row row = rows.get(index);
    StringBuilder s = new StringBuilder(row.text(label));
    for (String value : values)
      s.append(" · ")
          .append(I18n.t(value))
          .append(": ")
          .append(row.money(value).stripTrailingZeros());
    return s.toString();
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setFont(new Font("SansSerif", Font.BOLD, 16));
      g.setColor(Theme.TEXT);
      g.drawString(I18n.t(title), 20, 27);
      if (rows.isEmpty() || values.isEmpty()) {
        g.setFont(getFont());
        g.drawString(I18n.t("no_data"), 30, 90);
        return;
      }
      int x0 = 72, y0 = 60, w = getWidth() - 102, h = getHeight() - 115;
      if (w <= 0 || h <= 0) return;
      double max = 0, min = 0;
      for (Row row : rows)
        for (String key : values) {
          double v = row.money(key).doubleValue();
          max = Math.max(max, v);
          min = Math.min(min, v);
        }
      if (max == min) max = min + 1;
      double span = max - min;
      g.setFont(new Font("SansSerif", Font.PLAIN, 11));
      for (int i = 0; i <= 4; i++) {
        double v = min + span * i / 4;
        int y = y0 + h - h * i / 4;
        g.setColor(new Color(228, 233, 239));
        g.drawLine(x0, y, x0 + w, y);
        g.setColor(Theme.MUTED);
        g.drawString(
            java.text.NumberFormat.getCompactNumberInstance(
                    Locale.ENGLISH, java.text.NumberFormat.Style.SHORT)
                .format(v),
            8,
            y + 4);
      }
      for (int s = 0; s < values.size(); s++) {
        g.setColor(COLORS[s % COLORS.length]);
        g.setStroke(new BasicStroke(2.5f));
        int px = 0, py = 0;
        for (int i = 0; i < rows.size(); i++) {
          int x = x0 + (int) ((i + .5) * w / rows.size());
          int y =
              y0 + h - (int) ((rows.get(i).money(values.get(s)).doubleValue() - min) * h / span);
          if (bars) {
            int zero = y0 + h - (int) ((0 - min) * h / span),
                group = Math.max(values.size(), (int) (0.72 * w / rows.size())),
                bw = Math.max(1, group / values.size());
            g.fillRoundRect(
                x - group / 2 + s * bw,
                Math.min(y, zero),
                Math.max(1, bw - 3),
                Math.max(1, Math.abs(zero - y)),
                4,
                4);
          } else {
            if (i > 0) g.drawLine(px, py, x, y);
            g.fillOval(x - 3, y - 3, 6, 6);
          }
          px = x;
          py = y;
        }
        g.fillRect(x0 + s * 170, getHeight() - 18, 9, 9);
        g.drawString(I18n.t(values.get(s)), x0 + 15 + s * 170, getHeight() - 9);
      }
      g.setColor(Theme.MUTED);
      for (int i = 0; i < rows.size(); i += Math.max(1, rows.size() / 6)) {
        String text = rows.get(i).text(label);
        if (text.length() > 13) text = text.substring(0, 12) + "…";
        int x = x0 + (int) ((i + .5) * w / rows.size());
        g.drawString(text, x - 25, y0 + h + 20);
      }
    } finally {
      g.dispose();
    }
  }
}
