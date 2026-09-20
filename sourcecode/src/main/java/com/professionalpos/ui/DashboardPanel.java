package com.professionalpos.ui;

import com.professionalpos.App;
import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import com.professionalpos.reporting.ReportService.Report;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class DashboardPanel extends JPanel {
  private final MainFrame frame;
  private final JPanel cards = new JPanel(new GridLayout(0, 4, 12, 12));
  private final JTabbedPane charts = new JTabbedPane();
  private final JComboBox<String> period = new JComboBox<>(new String[] {"7", "30", "365"});
  private final JTextField from = new JTextField(10), to = new JTextField(10);

  public DashboardPanel(MainFrame frame) {
    super(new BorderLayout(12, 16));
    this.frame = frame;
    setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    JLabel title = Ui.label("dashboard");
    title.setFont(new Font("SansSerif", Font.BOLD, 27));
    from.setText(frame.app.today().minusDays(6).toString());
    to.setText(frame.app.today().toString());
    JPanel head = new JPanel(new BorderLayout());
    head.add(title, BorderLayout.NORTH);
    head.add(
        Ui.bar(
            Ui.button("new_sale", () -> frame.showPage("pos")),
            Ui.button("add_product", () -> frame.actions.product(null, "")),
            Ui.button("record_expense", () -> frame.actions.expense()),
            Ui.button("new_purchase", () -> frame.showPurchase(null)),
            period,
            from,
            Ui.label("to"),
            to,
            Ui.button("refresh", this::reload)),
        BorderLayout.SOUTH);
    add(head, BorderLayout.NORTH);
    JPanel body = new JPanel(new BorderLayout(16, 16));
    body.add(cards, BorderLayout.NORTH);
    body.add(charts, BorderLayout.CENTER);
    add(new JScrollPane(body));
    period.addActionListener(
        e -> {
          from.setText(
              frame
                  .app
                  .today()
                  .minusDays(Integer.parseInt((String) period.getSelectedItem()) - 1)
                  .toString());
          reload();
        });
  }

  public void reload() {
    App a = frame.app;
    LocalDate start, end;
    try {
      start = LocalDate.parse(from.getText());
      end = LocalDate.parse(to.getText());
    } catch (Exception e) {
      Ui.error(this, e);
      return;
    }
    Ui.work(
        this,
        () -> {
          Map<String, List<Row>> data = new LinkedHashMap<>();
          data.put("today", a.reports.report(a.user, Report.END_OF_DAY, a.today(), a.today()));
          data.put(
              "month",
              a.reports.report(a.user, Report.END_OF_DAY, a.today().withDayOfMonth(1), a.today()));
          data.put("growth", a.reports.report(a.user, Report.GROWTH, start, end));
          for (Report r :
              List.of(
                  Report.SALES_BY_DAY,
                  Report.PROFIT_BY_DAY,
                  Report.SALES_BY_CATEGORY,
                  Report.TOP_PRODUCTS,
                  Report.PAYMENT_METHODS,
                  Report.EXPENSES_BY_DAY))
            data.put(r.name(), a.reports.report(a.user, r, start, end));
          return data;
        },
        data -> {
          cards.removeAll();
          charts.removeAll();
          Set<String> metrics =
              Set.of(
                  "Sales before returns",
                  "Net sales",
                  "Transactions",
                  "Average sale",
                  "Gross profit",
                  "Net profit",
                  "Operating expenses",
                  "Net cash payments",
                  "Receivables",
                  "Payables",
                  "Inventory value",
                  "Low stock products");
          for (Row r : data.get("today")) {
            String key = r.text("metric");
            if (!metrics.contains(key)) continue;
            JButton card =
                new JButton(
                    "<html><div style='padding:8px'>"
                        + I18n.t(key)
                        + "<br><span style='font-size:22px;font-weight:bold'>"
                        + I18n.number(r.money("amount"))
                        + "</span><br>"
                        + a.base()
                        + " · "
                        + I18n.t("today")
                        + "</div></html>");
            card.setHorizontalAlignment(SwingConstants.LEADING);
            card.setBackground(Color.WHITE);
            card.setPreferredSize(new Dimension(195, 90));
            card.addActionListener(
                e -> {
                  if (key.equals("Low stock products")) frame.showReport(Report.LOW_STOCK);
                  else if (key.equals("Receivables")) frame.showReport(Report.RECEIVABLE_AGING);
                  else if (key.equals("Payables")) frame.showReport(Report.PAYABLE_AGING);
                  else if (key.equals("Inventory value")) frame.showReport(Report.INVENTORY_VALUE);
                  else if (key.toLowerCase(Locale.ROOT).contains("profit")
                      || key.equals("Operating expenses")) frame.showReport(Report.PROFIT_LOSS);
                  else frame.showPage("sales");
                });
            cards.add(card);
          }
          addChart(
              "sales_trend",
              data.get(Report.SALES_BY_DAY.name()),
              "period",
              List.of("net_sales"),
              false,
              Report.SALES_BY_DAY);
          addChart(
              "profit_trend",
              data.get(Report.PROFIT_BY_DAY.name()),
              "period",
              List.of("net_sales", "gross_profit"),
              false,
              Report.PROFIT_BY_DAY);
          addChart(
              "sales_by_category",
              data.get(Report.SALES_BY_CATEGORY.name()),
              "category_name",
              List.of("net_sales"),
              true,
              Report.SALES_BY_CATEGORY);
          addChart(
              "top_products",
              data.get(Report.TOP_PRODUCTS.name()).stream().limit(8).toList(),
              "product_name",
              List.of("net_sales"),
              true,
              Report.TOP_PRODUCTS);
          addChart(
              "payment_methods",
              data.get(Report.PAYMENT_METHODS.name()),
              "method_code",
              List.of("amount"),
              true,
              Report.PAYMENT_METHODS);
          addChart(
              "expenses",
              data.get(Report.EXPENSES_BY_DAY.name()),
              "document_date",
              List.of("amount"),
              true,
              Report.EXPENSES_BY_DAY);
          addChart(
              "store_growth",
              data.get("growth").stream()
                  .filter(
                      r ->
                          Set.of("Net sales", "Gross profit", "Net profit")
                              .contains(r.text("metric")))
                  .toList(),
              "metric",
              List.of("current", "previous"),
              true,
              Report.GROWTH);
          JTable month = Ui.table();
          Ui.rows(month, data.get("month"));
          charts.addTab(I18n.t("this_month"), new JScrollPane(month));
          revalidate();
          repaint();
        });
  }

  private void addChart(
      String title,
      List<Row> data,
      String label,
      List<String> values,
      boolean bars,
      Report report) {
    ChartPanel chart = new ChartPanel();
    chart.data(title, data, label, values, bars, () -> frame.showReport(report));
    charts.addTab(I18n.t(title), chart);
  }
}
