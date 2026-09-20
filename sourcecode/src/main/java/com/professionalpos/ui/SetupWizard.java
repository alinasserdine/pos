package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.i18n.I18n;
import java.awt.*;
import java.util.*;
import javax.swing.*;

public final class SetupWizard extends JDialog {
  private final App app;
  private final CardLayout cards = new CardLayout();
  private final JPanel content = new JPanel(cards);
  private final Form[] forms = new Form[7];
  private final JLabel title = new JLabel();
  private int step;

  public SetupWizard(App app) {
    super((Frame) null, I18n.t("first_run_setup"), true);
    this.app = app;
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setSize(790, 650);
    setLocationRelativeTo(null);
    JPanel root = new JPanel(new BorderLayout(12, 12));
    root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
    title.setFont(new Font("SansSerif", Font.BOLD, 24));
    root.add(title, BorderLayout.NORTH);
    forms[0] = new Form().choices("language", new String[] {"en", "ar"}, I18n.language());
    ((JComboBox<?>) forms[0].field("language"))
        .addActionListener(
            e -> {
              I18n.language(forms[0].text("language"));
              I18n.refresh(root);
              showStep();
            });
    forms[1] =
        new Form()
            .text("store_name", "")
            .text("legal_name", "")
            .text("address", "")
            .text("phone", "")
            .text("email", "")
            .text("tax_number", "")
            .text("country", "Lebanon")
            .text("time_zone", "Asia/Beirut")
            .text("footer", I18n.t("thank_you"))
            .text("logo_path", "");
    forms[2] =
        new Form()
            .text("currency", "USD")
            .text("currency_name", "US Dollar")
            .text("symbol", "$")
            .text("decimals", "2");
    forms[3] =
        new Form()
            .choices("tax_mode", new String[] {"NONE", "EXCLUSIVE", "INCLUSIVE"}, "NONE")
            .text("tax_name", "VAT")
            .text("tax_rate", "0");
    forms[4] = new Form().choices("costing", new String[] {"WAC", "FIFO"}, "WAC");
    forms[5] =
        new Form()
            .text("full_name", "")
            .text("username", "")
            .password("password")
            .password("confirm_password");
    forms[6] = new Form();
    JTextArea finish = new JTextArea(I18n.t("setup_finish_note"));
    finish.putClientProperty("i18n", "setup_finish_note");
    finish.setLineWrap(true);
    finish.setWrapStyleWord(true);
    finish.setEditable(false);
    finish.setBackground(Theme.BG);
    finish.setMargin(new Insets(30, 20, 30, 20));
    forms[6].setLayout(new BorderLayout());
    forms[6].add(finish);
    for (int i = 0; i < 7; i++) content.add(new JScrollPane(forms[i]), String.valueOf(i));
    root.add(content, BorderLayout.CENTER);
    JButton back =
        Ui.button(
            "back",
            () -> {
              if (step > 0) {
                step--;
                showStep();
              }
            });
    JButton next =
        Ui.button(
            "next",
            () -> {
              if (step < 6) {
                step++;
                showStep();
              } else finish();
            });
    Theme.primary(next);
    Runnable exit =
        () -> {
          if (!Ui.busy(this) && Ui.confirm(this, "exit_application")) dispose();
        };
    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent event) {
            exit.run();
          }
        });
    root.add(Ui.bar(Ui.button("exit", exit), back, next), BorderLayout.SOUTH);
    setContentPane(root);
    showStep();
  }

  private void showStep() {
    String[] names = {
      "welcome",
      "business_information",
      "base_currency",
      "tax",
      "inventory_costing",
      "administrator",
      "finish"
    };
    title.setText((step + 1) + " / 7  ·  " + I18n.t(names[step]));
    cards.show(content, String.valueOf(step));
    I18n.refresh(getContentPane());
  }

  private void finish() {
    Map<String, String> data = new LinkedHashMap<>();
    for (Form f : forms) data.putAll(f.strings());
    char[] pw = forms[5].passwordValue("password"),
        confirm = forms[5].passwordValue("confirm_password");
    Ui.work(
        this,
        () -> {
          try {
            return app.auth.setup(data, pw, confirm);
          } finally {
            Arrays.fill(pw, '\0');
            Arrays.fill(confirm, '\0');
          }
        },
        u -> {
          app.user = u;
          dispose();
        });
  }
}
