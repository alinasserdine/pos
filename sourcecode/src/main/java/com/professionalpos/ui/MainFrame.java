package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.Main;
import com.professionalpos.i18n.I18n;
import com.professionalpos.reporting.ReportService;
import com.professionalpos.security.Permission;
import com.professionalpos.service.QueryService;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;

public final class MainFrame extends JFrame {
  public final App app;
  public final Workflows actions;
  private final JPanel content = new JPanel(new CardLayout());
  private final JPanel nav = new JPanel();
  private final Map<String, JComponent> pages = new LinkedHashMap<>();
  private String current = "";
  public PosPanel pos;
  private long lastActivity = System.currentTimeMillis();
  private final javax.swing.Timer idleTimer;
  private final AWTEventListener activity;

  public MainFrame(App app) {
    super("Professional POS");
    this.app = app;
    actions = new Workflows(this);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    setSize(Math.min(1320, screen.width - 40), Math.min(800, screen.height - 60));
    setMinimumSize(new Dimension(1060, 660));
    setLocationRelativeTo(null);
    JPanel root = new JPanel(new BorderLayout());
    nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
    nav.setBackground(Theme.NAV);
    nav.setBorder(BorderFactory.createEmptyBorder(20, 12, 20, 12));
    JLabel brand = new JLabel("PROFESSIONAL POS");
    brand.setForeground(Color.WHITE);
    brand.setFont(new Font("SansSerif", Font.BOLD, 14));
    nav.add(brand);
    nav.add(Box.createVerticalStrut(20));
    if (app.user.can(Permission.REPORT_PROFIT)) nav("dashboard");
    if (app.user.can(Permission.POS_ACCESS)) nav("pos");
    for (String key :
        new String[] {
          "sales",
          "products",
          "inventory",
          "customers",
          "suppliers",
          "purchases",
          "orders",
          "expenses",
          "debts",
          "held",
          "cash",
          "accounts",
          "journals",
          "reports",
          "users",
          "settings",
          "notifications",
          "audit"
        }) {
      if (key.equals("reports")) {
        if (app.user.can(Permission.REPORT_SALES)) nav(key);
      } else if (key.equals("settings")) {
        if (app.user.can(Permission.SETTINGS_GENERAL)) nav(key);
      } else if (app.user.can(
          QueryService.permission(QueryService.Page.valueOf(key.toUpperCase(Locale.ROOT)))))
        nav(key);
    }
    JScrollPane side = new JScrollPane(nav);
    side.setBorder(null);
    side.setPreferredSize(new Dimension(205, 0));
    side.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    root.add(side, BorderLayout.LINE_START);
    JPanel top =
        Ui.bar(
            new JLabel(app.user.name() + " · " + app.user.role()),
            new JLabel(app.base()),
            Ui.button("language", this::language),
            Ui.button("change_password", () -> actions.changePassword(false)),
            Ui.button("lock", this::lock),
            Ui.button("logout", this::logout));
    root.add(top, BorderLayout.NORTH);
    root.add(content);
    setContentPane(root);
    applyComponentOrientation(I18n.orientation());
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            shutdown();
          }
        });
    activity =
        e -> {
          if (e instanceof InputEvent) lastActivity = System.currentTimeMillis();
        };
    Toolkit.getDefaultToolkit()
        .addAWTEventListener(activity, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
    idleTimer =
        new javax.swing.Timer(
            30_000,
            e -> {
              long minutes = Long.parseLong(app.preferences.getOrDefault("session_minutes", "15"));
              if (isVisible()
                  && !Ui.busy(this)
                  && System.currentTimeMillis() - lastActivity > minutes * 60_000) lock();
            });
    idleTimer.start();
    showPage(app.user.can(Permission.REPORT_PROFIT) ? "dashboard" : "pos");
    setVisible(true);
    if (app.user.mustChange()) SwingUtilities.invokeLater(() -> actions.changePassword(true));
  }

  private void nav(String key) {
    JButton b = Ui.button(key, () -> showPage(key));
    b.setAlignmentX(Component.LEFT_ALIGNMENT);
    b.setHorizontalAlignment(SwingConstants.LEADING);
    b.setMaximumSize(new Dimension(180, 42));
    b.setBackground(Theme.NAV);
    b.setForeground(Color.WHITE);
    b.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    nav.add(b);
    nav.add(Box.createVerticalStrut(3));
  }

  public void showPage(String key) {
    if (Ui.busy(this)) return;
    try {
      JComponent panel = pages.get(key);
      if (panel == null) {
        panel =
            switch (key) {
              case "dashboard" -> new DashboardPanel(this);
              case "pos" -> {
                pos = new PosPanel(this, false);
                yield pos;
              }
              case "reports" -> new ReportPanel(this);
              case "settings" -> new SettingsPanel(this);
              default ->
                  new DataPanel(this, QueryService.Page.valueOf(key.toUpperCase(Locale.ROOT)));
            };
        pages.put(key, panel);
        content.add(panel, key);
      }
      ((CardLayout) content.getLayout()).show(content, key);
      current = key;
      I18n.refresh(panel);
      refresh();
    } catch (Exception e) {
      Ui.error(this, e);
    }
  }

  public void refresh() {
    JComponent p = pages.get(current);
    if (p instanceof DataPanel d) d.reload();
    else if (p instanceof DashboardPanel d) d.reload();
    else if (p instanceof ReportPanel r) r.reload();
    else if (p instanceof PosPanel pos) pos.activate();
  }

  public void showReport(ReportService.Report report) {
    showPage("reports");
    Ui.whenIdle(
        this,
        () -> {
          if (pages.get("reports") instanceof ReportPanel r) r.select(report);
        });
  }

  public void showPurchase(Long order) {
    PosPanel panel = new PosPanel(this, true);
    JDialog d = Ui.dialog(this, "new_purchase", panel, 1160, 730);
    Runnable close =
        () -> {
          if (!Ui.busy(d) && (!panel.hasCart() || Ui.confirm(d, "discard_cart"))) d.dispose();
        };
    d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    d.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            close.run();
          }
        });
    d.getRootPane()
        .registerKeyboardAction(
            e -> close.run(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    panel.setHost(d);
    panel.activate();
    if (order != null) panel.loadOrder(order);
    d.setVisible(true);
  }

  public void restoreHeld(long id) {
    showPage("pos");
    if (pos != null) pos.loadHeld(id);
  }

  private void language() {
    I18n.language(I18n.arabic() ? "en" : "ar");
    refreshLanguage();
  }

  public void refreshLanguage() {
    I18n.refresh(this);
    for (JComponent p : pages.values()) {
      if (p instanceof PosPanel pos) pos.refreshLanguage();
      if (p instanceof ReportPanel report) report.refreshLanguage();
    }
    if (pages.get(current) instanceof DashboardPanel dashboard) dashboard.reload();
    for (Component p : pages.values())
      if (p instanceof DataPanel d && d.table.getModel().getRowCount() > 0) {
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<com.professionalpos.db.Row>) d.table.getClientProperty("rows");
        Ui.rows(d.table, rows);
      }
    revalidate();
    repaint();
  }

  private void lock() {
    if (Ui.busy(this)) return;
    idleTimer.stop();
    lastActivity = System.currentTimeMillis();
    setVisible(false);
    var prior = app.user;
    while (true) {
      var signed = LoginDialog.show(null, app, null);
      if (signed != null && signed.id() == prior.id()) {
        app.user = signed;
        break;
      }
      if (signed == null) {
        if (Ui.confirm(null, "exit_application")) {
          Main.shutdown(app, false);
          return;
        }
      } else Ui.info(null, I18n.t("unlock_same_user"));
    }
    setVisible(true);
    lastActivity = System.currentTimeMillis();
    idleTimer.start();
  }

  private void logout() {
    if (Ui.busy(this)) return;
    if (pos != null && pos.hasCart() && !Ui.confirm(this, "discard_cart")) return;
    Ui.work(
        this,
        () -> {
          app.auth.logout(app.user);
          return null;
        },
        v -> {
          dispose();
          Main.login(app);
        });
  }

  public void shutdown() {
    if (Ui.busy(this)) return;
    if ((pos != null && pos.hasCart()) && !Ui.confirm(this, "discard_cart")) return;
    Ui.work(
        this,
        () -> app.user.can(Permission.CASH_SESSION) ? app.cash.current(app.user) : null,
        session -> {
          if (session != null && !Ui.confirm(this, "open_session_exit")) return;
          Ui.work(
              this,
              () -> {
                Main.shutdown(app, true);
                return null;
              },
              v -> dispose());
        });
  }

  @Override
  public void dispose() {
    if (idleTimer != null) idleTimer.stop();
    if (activity != null) Toolkit.getDefaultToolkit().removeAWTEventListener(activity);
    super.dispose();
  }
}
