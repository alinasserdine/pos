package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import com.professionalpos.reporting.ReportService;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class ReportPanel extends JPanel {
  private final MainFrame frame;
  private final App app;
  private final JComboBox<Form.Choice> report;
  private final JTextField from = new JTextField(10), to = new JTextField(10);
  private final JTable table = Ui.table();
  private final ChartPanel chart = new ChartPanel();
  private final JTextField filter = new JTextField(18);

  public ReportPanel(MainFrame frame) {
    super(new BorderLayout(10, 10));
    this.frame = frame;
    app = frame.app;
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    List<Form.Choice> choices =
        Arrays.stream(ReportService.Report.values())
            .filter(r -> app.user.can(ReportService.permission(r)))
            .map(r -> new Form.Choice(r, I18n.t(r.name())))
            .toList();
    report = new JComboBox<>(choices.toArray(Form.Choice[]::new));
    report.putClientProperty("i18nChoices", Boolean.TRUE);
    from.setText(app.today().withDayOfMonth(1).toString());
    to.setText(app.today().toString());
    JLabel title = Ui.label("reports");
    title.setFont(new Font("SansSerif", Font.BOLD, 25));
    JPanel north = new JPanel(new BorderLayout());
    north.add(title, BorderLayout.NORTH);
    north.add(
        Ui.bar(
            report,
            from,
            Ui.label("to"),
            to,
            Ui.button("refresh", this::reload),
            filter,
            Ui.button("filter_rows", this::filter)),
        BorderLayout.CENTER);
    add(north, BorderLayout.NORTH);
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), chart);
    split.setResizeWeight(.66);
    add(split);
    add(
        Ui.bar(
            Ui.button("export_csv", () -> frame.actions.export(table, current().name())),
            Ui.button(
                "print",
                () ->
                    frame.actions.print(
                        table, current().name() + " " + from.getText() + " — " + to.getText())),
            Ui.label("reports_current_balance_note")),
        BorderLayout.SOUTH);
    report.addActionListener(e -> reload());
    filter.addActionListener(e -> filter());
    table.addMouseListener(
        new java.awt.event.MouseAdapter() {
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) drill();
          }
        });
  }

  public void refreshLanguage() {
    I18n.refresh(this);
    chart.repaint();
  }

  public ReportService.Report current() {
    return (ReportService.Report) ((Form.Choice) report.getSelectedItem()).key();
  }

  public void select(ReportService.Report name) {
    for (int i = 0; i < report.getItemCount(); i++)
      if (report.getItemAt(i).key() == name) {
        report.setSelectedIndex(i);
        return;
      }
  }

  public void reload() {
    try {
      LocalDate f = LocalDate.parse(from.getText()), t = LocalDate.parse(to.getText());
      ReportService.Report selected = current();
      Ui.work(
          this,
          () -> app.reports.report(app.user, selected, f, t),
          rows -> {
            Ui.rows(table, rows);
            filter();
            if (rows.isEmpty()) {
              chart.data(selected.name(), List.of(), "", List.of(), false, null);
              return;
            }
            List<String> keys = new ArrayList<>(rows.get(0).keySet());
            String label =
                List.of(
                        "period",
                        "metric",
                        "product_name",
                        "category_name",
                        "party_name",
                        "name",
                        "method_code",
                        "document_date",
                        "number",
                        "period_year",
                        "period_month",
                        "period_hour")
                    .stream()
                    .filter(keys::contains)
                    .findFirst()
                    .orElse(keys.get(0));
            List<String> values =
                List.of(
                        "net_sales",
                        "gross_profit",
                        "net_profit",
                        "revenue",
                        "cogs",
                        "tax",
                        "discounts",
                        "amount",
                        "balance",
                        "stock_value",
                        "base_total",
                        "total",
                        "debit",
                        "credit",
                        "current",
                        "previous")
                    .stream()
                    .filter(k -> rows.get(0).get(k) instanceof Number)
                    .limit(3)
                    .toList();
            if (values.isEmpty())
              values =
                  keys.stream()
                      .filter(
                          k ->
                              rows.get(0).get(k) instanceof Number
                                  && !k.equals(label)
                                  && !k.endsWith("id")
                                  && !Set.of(
                                          "year",
                                          "month",
                                          "hour",
                                          "period_year",
                                          "period_month",
                                          "period_hour",
                                          "tax_rate")
                                      .contains(k))
                      .limit(3)
                      .toList();
            chart.data(
                selected.name(),
                rows.size() > 30 ? rows.subList(0, 30) : rows,
                label,
                values,
                !selected.name().contains("DAY") && !selected.name().contains("MONTH"),
                null);
          });
    } catch (Exception ex) {
      Ui.error(this, ex);
    }
  }

  @SuppressWarnings("unchecked")
  private void filter() {
    if (table.getRowSorter() instanceof javax.swing.table.TableRowSorter<?> sorter) {
      String s = filter.getText();
      sorter.setRowFilter(
          s.isBlank() ? null : RowFilter.regexFilter("(?iu)" + java.util.regex.Pattern.quote(s)));
    }
  }

  private void drill() {
    try {
      Row row = Ui.selected(table);
      if (Set.of(
              ReportService.Report.PURCHASES,
              ReportService.Report.RETURNS,
              ReportService.Report.PURCHASE_RETURNS)
          .contains(current())) frame.actions.receipt(row.id());
      else if (row.containsKey("product_id"))
        frame.actions.productDetails(row.number("product_id"));
      else if (row.containsKey("id")
          && Set.of(
                  ReportService.Report.INVENTORY,
                  ReportService.Report.INVENTORY_VALUE,
                  ReportService.Report.LOW_STOCK,
                  ReportService.Report.REORDER,
                  ReportService.Report.SLOW_MOVING,
                  ReportService.Report.DEAD_STOCK)
              .contains(current())) frame.actions.productDetails(row.id());
    } catch (Exception e) {
      Ui.error(this, e);
    }
  }
}
