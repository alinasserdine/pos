package com.professionalpos.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

public final class Theme {
  public static final Color NAV = new Color(20, 34, 52),
      ACCENT = new Color(0, 119, 116),
      BG = new Color(242, 245, 248),
      TEXT = new Color(27, 42, 58),
      MUTED = new Color(94, 110, 124);

  private Theme() {}

  public static void install() {
    try {
      UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
    } catch (Exception ignored) {
    }
    Font font = new Font("SansSerif", Font.PLAIN, 14);
    for (Object key : UIManager.getDefaults().keySet())
      if (key.toString().endsWith(".font")) UIManager.put(key, font);
    UIManager.put("Panel.background", BG);
    UIManager.put("Label.foreground", TEXT);
    UIManager.put("Button.margin", new Insets(9, 15, 9, 15));
    UIManager.put("TextField.margin", new Insets(7, 8, 7, 8));
    UIManager.put("Table.rowHeight", 32);
    UIManager.put("Table.selectionBackground", new Color(216, 239, 235));
    UIManager.put("Table.selectionForeground", TEXT);
    UIManager.put("TabbedPane.contentBorderInsets", new Insets(10, 10, 10, 10));
  }

  public static void primary(JButton b) {
    b.setBackground(ACCENT);
    b.setForeground(Color.WHITE);
    b.setFocusPainted(true);
    b.setBorder(new CompoundBorder(new LineBorder(ACCENT), new EmptyBorder(10, 20, 10, 20)));
  }
}
