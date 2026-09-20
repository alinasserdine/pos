package com.professionalpos.i18n;

import java.awt.*;
import java.math.*;
import java.text.*;
import java.util.*;
import javax.swing.*;

public final class I18n {
  private static Locale locale = Locale.ENGLISH;
  private static ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);

  private I18n() {}

  public static void language(String code) {
    locale = Locale.forLanguageTag(code);
    bundle = ResourceBundle.getBundle("messages", locale);
  }

  public static String language() {
    return locale.getLanguage();
  }

  public static boolean arabic() {
    return language().equals("ar");
  }

  public static String t(String key) {
    String lookup =
        key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    return bundle.containsKey(lookup) ? bundle.getString(lookup) : human(key);
  }

  public static String human(String key) {
    String s = key.replace('_', ' ');
    return s.isEmpty()
        ? s
        : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
  }

  public static ComponentOrientation orientation() {
    return arabic() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT;
  }

  public static String error(Throwable e) {
    if (e instanceof com.professionalpos.db.Database.DatabaseException) return t("database_error");
    if (e instanceof SecurityException) return t("permission_denied");
    String msg = e.getMessage();
    if (msg == null) return t("unexpected_error");
    String key = msg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    return bundle.containsKey(key)
        ? bundle.getString(key)
        : arabic() ? t("validation_error") + "\n" + msg : msg;
  }

  public static String money(BigDecimal value, String symbol, int decimals, boolean before) {
    NumberFormat f = NumberFormat.getNumberInstance(locale);
    f.setMinimumFractionDigits(decimals);
    f.setMaximumFractionDigits(decimals);
    f.setRoundingMode(RoundingMode.HALF_UP);
    String n = f.format(value);
    return before ? symbol + " " + n : n + " " + symbol;
  }

  public static String number(BigDecimal value) {
    NumberFormat f = NumberFormat.getNumberInstance(locale);
    f.setMaximumFractionDigits(6);
    return f.format(value);
  }

  public static void refresh(Component c) {
    c.applyComponentOrientation(orientation());
    if (c instanceof JComponent j) {
      Object key = j.getClientProperty("i18n");
      if (key != null) {
        if (c instanceof AbstractButton b) b.setText(t(key.toString()));
        if (c instanceof JLabel l) l.setText(t(key.toString()));
        if (c instanceof JTextArea a) a.setText(t(key.toString()));
      }
    }
    if (c instanceof JTable table)
      for (int col = 0; col < table.getColumnCount(); col++)
        table.getColumnModel().getColumn(col).setHeaderValue(table.getModel().getColumnName(col));
    if (c instanceof JTabbedPane tabs)
      for (int i = 0; i < tabs.getTabCount(); i++)
        if (tabs.getComponentAt(i) instanceof JComponent pane
            && pane.getClientProperty("tabKey") != null)
          tabs.setTitleAt(i, t(pane.getClientProperty("tabKey").toString()));
    if (c instanceof JComboBox<?> box && Boolean.TRUE.equals(box.getClientProperty("i18nChoices")))
      box.setRenderer(
          new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
              if (value instanceof com.professionalpos.ui.Form.Choice choice)
                value = t(choice.key().toString());
              return super.getListCellRendererComponent(list, value, index, selected, focus);
            }
          });
    if (c instanceof Container ct) for (Component child : ct.getComponents()) refresh(child);
  }
}
