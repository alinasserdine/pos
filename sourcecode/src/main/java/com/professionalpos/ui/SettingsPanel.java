package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.i18n.I18n;
import com.professionalpos.reporting.ReportService.Report;
import com.professionalpos.service.QueryService.Page;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class SettingsPanel extends JPanel {
  private final Map<String, Form> forms = new LinkedHashMap<>();

  public SettingsPanel(MainFrame frame) {
    super(new BorderLayout(12, 12));
    App a = frame.app;
    setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
    JLabel title = Ui.label("settings");
    title.setFont(new Font("SansSerif", Font.BOLD, 25));
    add(title, BorderLayout.NORTH);
    JTabbedPane tabs = new JTabbedPane();
    Map<String, List<String>> sections = new LinkedHashMap<>();
    sections.put(
        "general", List.of("language", "time_zone", "base_currency", "secondary_currency"));
    sections.put(
        "checkout",
        List.of(
            "allow_credit",
            "enable_hold",
            "enable_split",
            "auto_print",
            "price_override",
            "max_cashier_discount",
            "refund_approval",
            "cash_tolerance"));
    sections.put("inventory", List.of("costing", "allow_negative"));
    sections.put(
        "tax_printing", List.of("tax_mode", "receipt_width", "receipt_language", "invoice_prefix"));
    sections.put(
        "security_backup",
        List.of("session_minutes", "due_soon_days", "backup_exit", "backup_daily", "backup_keep"));
    sections.put(
        "account_mapping",
        a.preferences.keySet().stream().filter(k -> k.startsWith("map.")).sorted().toList());
    for (var section : sections.entrySet()) {
      Form f = new Form();
      for (String key : section.getValue()) {
        String value = a.preferences.get(key);
        if (value.equals("true") || value.equals("false"))
          f.choices(key, new String[] {"false", "true"}, value);
        else
          switch (key) {
            case "language" -> f.choices(key, new String[] {"en", "ar"}, value);
            case "costing" -> f.choices(key, new String[] {"WAC", "FIFO"}, value);
            case "tax_mode" ->
                f.choices(key, new String[] {"NONE", "EXCLUSIVE", "INCLUSIVE"}, value);
            case "price_override" ->
                f.choices(key, new String[] {"NEVER", "PERMISSION", "APPROVAL", "ALWAYS"}, value);
            case "receipt_width" -> f.choices(key, new String[] {"58", "80", "A4"}, value);
            case "receipt_language" -> f.choices(key, new String[] {"en", "ar", "both"}, value);
            default -> f.text(key, value);
          }
      }
      forms.put(section.getKey(), f);
      JScrollPane pane = new JScrollPane(f);
      pane.putClientProperty("tabKey", section.getKey());
      tabs.addTab(I18n.t(section.getKey()), pane);
    }
    JPanel maintenance = new JPanel(new GridLayout(0, 3, 12, 12));
    maintenance.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    maintenance.add(Ui.button("business_information", frame.actions::store));
    maintenance.add(Ui.button("currencies", () -> frame.actions.referencePage(Page.CURRENCIES)));
    maintenance.add(Ui.button("exchange_rates", () -> frame.actions.referencePage(Page.RATES)));
    maintenance.add(Ui.button("tax_rates", () -> frame.actions.referencePage(Page.TAXES)));
    maintenance.add(Ui.button("categories", () -> frame.actions.referencePage(Page.CATEGORIES)));
    maintenance.add(Ui.button("units", () -> frame.actions.referencePage(Page.UNITS)));
    maintenance.add(Ui.button("backup_now", frame.actions::backup));
    maintenance.add(Ui.button("restore_backup", frame.actions::restore));
    maintenance.add(Ui.button("integrity_check", () -> frame.showReport(Report.INTEGRITY)));
    maintenance.add(Ui.button("period_locks", frame.actions::periods));
    maintenance.add(
        Ui.button(
            "open_data_folder",
            () -> {
              try {
                Desktop.getDesktop().open(a.db.home().toFile());
              } catch (Exception e) {
                Ui.error(frame, e);
              }
            }));
    maintenance.add(
        Ui.button(
            "about",
            () ->
                Ui.info(
                    frame,
                    "Professional POS 1.0.0\nJava "
                        + System.getProperty("java.version")
                        + "\nH2 2.3.232\n"
                        + a.db.home())));
    maintenance.putClientProperty("tabKey", "manage_data");
    tabs.addTab(I18n.t("manage_data"), maintenance);
    add(tabs);
    JButton save =
        Ui.button(
            "save_settings",
            () -> {
              Map<String, String> settings = new LinkedHashMap<>();
              forms.values().forEach(f -> settings.putAll(f.strings()));
              Ui.work(
                  this,
                  () -> {
                    a.settings.save(a.user, settings);
                    a.reload();
                    return null;
                  },
                  v -> {
                    I18n.language(a.preferences.get("language"));
                    frame.refreshLanguage();
                    Ui.info(frame, I18n.t("settings_saved"));
                  });
            });
    Theme.primary(save);
    add(Ui.bar(save), BorderLayout.SOUTH);
  }
}
