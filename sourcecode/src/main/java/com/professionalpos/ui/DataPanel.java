package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import com.professionalpos.service.QueryService;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class DataPanel extends JPanel {
  private final MainFrame frame;
  private final App app;
  public final QueryService.Page page;
  public final JTable table = Ui.table();
  private final JTextField search = new JTextField(18),
      from = new JTextField(10),
      to = new JTextField(10);
  private int offset;
  private final JLabel status = new JLabel();
  private Map<String, String> advanced = Map.of();

  public DataPanel(MainFrame frame, QueryService.Page page) {
    super(new BorderLayout(8, 8));
    this.frame = frame;
    this.app = frame.app;
    this.page = page;
    setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
    LocalDate date = app.today();
    from.setText(date.withDayOfMonth(1).toString());
    to.setText(date.toString());
    JPanel north = new JPanel(new BorderLayout());
    JLabel title = Ui.label(page.name());
    title.setFont(new Font("SansSerif", Font.BOLD, 25));
    north.add(title, BorderLayout.NORTH);
    JButton find =
        Ui.button(
            "search",
            () -> {
              offset = 0;
              reload();
            });
    search.addActionListener(
        e -> {
          offset = 0;
          reload();
        });
    JComboBox<String> range =
        new JComboBox<>(
            new String[] {
              I18n.t("this_month"),
              I18n.t("today"),
              I18n.t("yesterday"),
              I18n.t("last_7_days"),
              I18n.t("last_month"),
              I18n.t("this_year"),
              I18n.t("custom")
            });
    range.addActionListener(
        e -> {
          LocalDate now = app.today(), start = now, end = now;
          switch (range.getSelectedIndex()) {
            case 0 -> start = now.withDayOfMonth(1);
            case 2 -> {
              start = now.minusDays(1);
              end = start;
            }
            case 3 -> start = now.minusDays(6);
            case 4 -> {
              start = now.minusMonths(1).withDayOfMonth(1);
              end = now.withDayOfMonth(1).minusDays(1);
            }
            case 5 -> start = now.withDayOfYear(1);
            case 6 -> {
              return;
            }
          }
          from.setText(start.toString());
          to.setText(end.toString());
          offset = 0;
          reload();
        });
    JPanel filters = Ui.bar(search, find, range, from, Ui.label("to"), to);
    if (page == QueryService.Page.SALES || page == QueryService.Page.PURCHASES)
      filters.add(Ui.button("filters", this::filters));
    north.add(filters, BorderLayout.CENTER);
    north.add(frame.actions.toolbar(this), BorderLayout.SOUTH);
    add(north, BorderLayout.NORTH);
    add(new JScrollPane(table), BorderLayout.CENTER);
    add(
        Ui.bar(
            Ui.button(
                "previous",
                () -> {
                  offset = Math.max(0, offset - 100);
                  reload();
                }),
            status,
            Ui.button(
                "next",
                () -> {
                  offset += 100;
                  reload();
                }),
            Ui.button("export_csv", () -> frame.actions.export(table, page.name())),
            Ui.button(
                "print",
                () ->
                    frame.actions.print(
                        table, page.name() + " " + from.getText() + " — " + to.getText()))),
        BorderLayout.SOUTH);
    table.addMouseListener(
        new java.awt.event.MouseAdapter() {
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) frame.actions.details(DataPanel.this);
          }
        });
    getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke("control F"), "find");
    getActionMap()
        .put(
            "find",
            new AbstractAction() {
              public void actionPerformed(java.awt.event.ActionEvent e) {
                search.requestFocusInWindow();
              }
            });
  }

  public Row selected() {
    return Ui.selected(table);
  }

  public void reload() {
    try {
      QueryService.Filter filter =
          new QueryService.Filter(
              search.getText(),
              LocalDate.parse(from.getText()),
              LocalDate.parse(to.getText()),
              offset,
              advanced);
      Ui.work(
          this,
          () -> app.queries.list(app.user, page, filter),
          rows -> {
            Ui.rows(table, rows);
            status.setText((offset + 1) + "–" + (offset + rows.size()));
          });
    } catch (Exception e) {
      Ui.error(this, e);
    }
  }

  public void search(String text) {
    search.setText(text);
    offset = 0;
    reload();
  }

  private void filters() {
    Form f = new Form();
    for (String key :
        List.of(
            "cashier",
            "customer",
            "product",
            "category",
            "barcode",
            "payment",
            "status",
            "currency",
            "min_amount",
            "max_amount")) f.text(key, advanced.getOrDefault(key, ""));
    JPanel p = new JPanel(new BorderLayout());
    p.add(new JScrollPane(f));
    JDialog d = Ui.dialog(this, "filters", p, 520, 640);
    p.add(
        Ui.bar(
            Ui.button(
                "clear",
                () -> {
                  advanced = Map.of();
                  d.dispose();
                  offset = 0;
                  reload();
                }),
            Ui.button(
                "apply",
                () -> {
                  advanced = f.strings();
                  d.dispose();
                  offset = 0;
                  reload();
                })),
        BorderLayout.SOUTH);
    d.setVisible(true);
  }
}
