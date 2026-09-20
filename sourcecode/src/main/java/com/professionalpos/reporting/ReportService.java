package com.professionalpos.reporting;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import com.professionalpos.service.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class ReportService {
  public enum Report {
    SALES_SUMMARY,
    SALES_BY_DAY,
    SALES_BY_MONTH,
    SALES_BY_YEAR,
    SALES_BY_HOUR,
    SALES_BY_PRODUCT,
    SALES_BY_CATEGORY,
    SALES_BY_CUSTOMER,
    SALES_BY_CASHIER,
    PAYMENT_METHODS,
    RETURNS,
    DISCOUNTS,
    TAX,
    PROFIT_BY_DAY,
    PROFIT_BY_MONTH,
    PROFIT_BY_PRODUCT,
    PROFIT_BY_CATEGORY,
    MARGIN,
    PROFIT_LOSS,
    TRIAL_BALANCE,
    BALANCE_SHEET,
    GENERAL_LEDGER,
    JOURNALS,
    INVENTORY,
    INVENTORY_VALUE,
    LOW_STOCK,
    OUT_OF_STOCK,
    NEGATIVE_STOCK,
    STOCK_MOVEMENTS,
    STOCK_ADJUSTMENTS,
    REORDER,
    SLOW_MOVING,
    DEAD_STOCK,
    TOP_PRODUCTS,
    PURCHASES,
    PURCHASES_BY_SUPPLIER,
    PURCHASES_BY_PRODUCT,
    PURCHASE_RETURNS,
    OUTSTANDING_ORDERS,
    RECEIVABLE_AGING,
    PAYABLE_AGING,
    CUSTOMER_PAYMENTS,
    SUPPLIER_PAYMENTS,
    CUSTOMER_BALANCE,
    SUPPLIER_BALANCE,
    TOP_CUSTOMERS,
    INACTIVE_CUSTOMERS,
    EXPENSES_BY_DAY,
    EXPENSES_BY_MONTH,
    EXPENSES_BY_CATEGORY,
    CASH_SESSIONS,
    CASH_MOVEMENTS,
    CASH_DIFFERENCES,
    END_OF_DAY,
    GROWTH,
    INTEGRITY
  }

  private final Database db;

  public ReportService(Database db) {
    this.db = db;
  }

  public static Permission permission(Report r) {
    return switch (r) {
      case PROFIT_BY_DAY,
              PROFIT_BY_MONTH,
              PROFIT_BY_PRODUCT,
              PROFIT_BY_CATEGORY,
              MARGIN,
              PROFIT_LOSS,
              GROWTH ->
          Permission.REPORT_PROFIT;
      case TRIAL_BALANCE, BALANCE_SHEET, GENERAL_LEDGER, JOURNALS, INTEGRITY ->
          Permission.REPORT_ACCOUNTING;
      case INVENTORY,
              INVENTORY_VALUE,
              LOW_STOCK,
              OUT_OF_STOCK,
              NEGATIVE_STOCK,
              STOCK_MOVEMENTS,
              STOCK_ADJUSTMENTS,
              REORDER,
              SLOW_MOVING,
              DEAD_STOCK ->
          Permission.INVENTORY_VIEW;
      case PURCHASES,
              PURCHASES_BY_SUPPLIER,
              PURCHASES_BY_PRODUCT,
              PURCHASE_RETURNS,
              OUTSTANDING_ORDERS,
              SUPPLIER_PAYMENTS,
              SUPPLIER_BALANCE,
              PAYABLE_AGING ->
          Permission.PURCHASE_VIEW;
      case END_OF_DAY -> Permission.REPORT_PROFIT;
      default -> Permission.REPORT_SALES;
    };
  }

  public List<Row> report(User u, Report report, LocalDate from, LocalDate to) {
    u.require(permission(report));
    check(!to.isBefore(from), "Invalid date range.");
    return db.read(
        c -> {
          String sql;
          Object[] params = {from, to};
          String sales = " FROM v_sales_facts f WHERE f.document_date BETWEEN ? AND ? ";
          String measures =
              "SUM(f.quantity) AS quantity,SUM(f.revenue) AS net_sales,SUM(f.tax) AS tax";
          String profit =
              "SUM(f.revenue) AS net_sales,SUM(f.cogs) AS cogs,SUM(f.revenue-f.cogs) AS"
                  + " gross_profit";
          String inventory =
              "SELECT"
                  + " p.id,p.sku,p.name,p.on_hand,p.current_cost,p.stock_value,p.reorder_level,s.name"
                  + " AS preferred_supplier FROM products p LEFT JOIN parties s ON"
                  + " s.id=p.preferred_supplier_id WHERE p.track_stock=TRUE ";
          switch (report) {
            case SALES_SUMMARY ->
                sql =
                    "SELECT COUNT(DISTINCT f.document_id) AS documents,"
                        + measures
                        + ",SUM(f.discount) AS discounts"
                        + sales;
            case SALES_BY_DAY ->
                sql =
                    "SELECT f.document_date AS period,"
                        + measures
                        + sales
                        + "GROUP BY f.document_date ORDER BY period";
            case SALES_BY_MONTH ->
                sql =
                    "SELECT EXTRACT(YEAR FROM f.document_date) AS period_year,EXTRACT(MONTH FROM"
                        + " f.document_date) AS period_month,"
                        + measures
                        + sales
                        + "GROUP BY period_year,period_month ORDER BY period_year,period_month";
            case SALES_BY_YEAR ->
                sql =
                    "SELECT EXTRACT(YEAR FROM f.document_date) AS period_year,"
                        + measures
                        + sales
                        + "GROUP BY period_year ORDER BY period_year";
            case SALES_BY_HOUR ->
                sql =
                    "SELECT EXTRACT(HOUR FROM d.created_at) AS period_hour,SUM(d.base_net) AS"
                        + " net_sales,COUNT(*) AS invoices FROM documents d WHERE kind='SALE' AND"
                        + " document_date BETWEEN ? AND ? GROUP BY period_hour ORDER BY"
                        + " period_hour";
            case SALES_BY_PRODUCT, TOP_PRODUCTS ->
                sql =
                    "SELECT f.product_id,f.product_name,"
                        + measures
                        + sales
                        + "GROUP BY f.product_id,f.product_name ORDER BY net_sales DESC";
            case SALES_BY_CATEGORY ->
                sql =
                    "SELECT f.category_name,"
                        + measures
                        + sales
                        + "GROUP BY f.category_name ORDER BY net_sales DESC";
            case SALES_BY_CUSTOMER, TOP_CUSTOMERS ->
                sql =
                    "SELECT f.party_id,f.party_name,"
                        + measures
                        + sales
                        + "GROUP BY f.party_id,f.party_name ORDER BY net_sales DESC";
            case SALES_BY_CASHIER ->
                sql =
                    "SELECT u.full_name,COUNT(DISTINCT f.document_id) AS documents,"
                        + measures
                        + ",SUM(f.discount) AS discounts FROM v_sales_facts f JOIN users u ON"
                        + " u.id=f.created_by WHERE f.document_date BETWEEN ? AND ? GROUP BY"
                        + " u.full_name ORDER BY net_sales DESC";
            case PAYMENT_METHODS ->
                sql =
                    "SELECT p.method_code,SUM(p.base_amount*p.direction) AS amount FROM payments p"
                        + " JOIN documents d ON d.id=p.document_id WHERE d.document_date BETWEEN ?"
                        + " AND ? AND d.kind IN ('SALE','SALE_RETURN') GROUP BY p.method_code ORDER"
                        + " BY amount DESC";
            case RETURNS, PURCHASE_RETURNS -> {
              sql =
                  "SELECT id,number,document_date,party_name,base_net,base_tax,base_total,reason"
                      + " FROM documents WHERE kind=? AND document_date BETWEEN ? AND ? ORDER BY id"
                      + " DESC";
              params =
                  new Object[] {
                    report == Report.RETURNS ? "SALE_RETURN" : "PURCHASE_RETURN", from, to
                  };
            }
            case DISCOUNTS ->
                sql =
                    "SELECT"
                        + " d.number,d.document_date,l.product_name,l.discount,d.currency_code,l.discount*d.rate_to_base"
                        + " AS base_discount FROM documents d JOIN document_lines l ON"
                        + " l.document_id=d.id WHERE d.kind='SALE' AND d.document_date BETWEEN ?"
                        + " AND ? AND l.discount>0 ORDER BY d.id DESC";
            case TAX ->
                sql =
                    "SELECT l.tax_name,l.tax_rate,"
                        + "SUM(CASE WHEN d.kind='SALE' THEN l.base_net ELSE 0 END) AS taxable_sales,"
                        + "SUM(CASE WHEN d.kind='SALE' THEN l.base_tax ELSE 0 END) AS collected,"
                        + "SUM(CASE WHEN d.kind='SALE_RETURN' THEN l.base_tax ELSE 0 END) AS return_tax,"
                        + "SUM(CASE WHEN d.kind='SALE' THEN l.base_tax WHEN d.kind='SALE_RETURN' THEN -l.base_tax ELSE 0 END) AS net_tax,"
                        + "SUM(CASE WHEN d.kind='PURCHASE' THEN l.base_net ELSE 0 END) AS taxable_purchases,"
                        + "SUM(CASE WHEN d.kind='PURCHASE' THEN l.base_tax ELSE 0 END) AS input_tax,"
                        + "SUM(CASE WHEN d.kind='PURCHASE_RETURN' THEN l.base_tax ELSE 0 END) AS input_tax_reversed,"
                        + "SUM(CASE WHEN d.kind='PURCHASE' THEN l.base_tax WHEN d.kind='PURCHASE_RETURN' THEN -l.base_tax ELSE 0 END) AS net_input_tax,"
                        + "SUM(CASE WHEN d.kind='SALE' THEN l.base_tax WHEN d.kind='SALE_RETURN' THEN -l.base_tax WHEN d.kind='PURCHASE' THEN -l.base_tax WHEN d.kind='PURCHASE_RETURN' THEN l.base_tax ELSE 0 END) AS net_tax_payable "
                        + "FROM documents d JOIN document_lines l ON l.document_id=d.id WHERE d.kind IN"
                        + " ('SALE','SALE_RETURN','PURCHASE','PURCHASE_RETURN') AND d.document_date BETWEEN ? AND ? GROUP BY"
                        + " l.tax_name,l.tax_rate";
            case PROFIT_BY_DAY ->
                sql =
                    "SELECT f.document_date AS period,"
                        + profit
                        + sales
                        + "GROUP BY f.document_date ORDER BY period";
            case PROFIT_BY_MONTH ->
                sql =
                    "SELECT EXTRACT(YEAR FROM f.document_date) AS period_year,EXTRACT(MONTH FROM"
                        + " f.document_date) AS period_month,"
                        + profit
                        + sales
                        + "GROUP BY period_year,period_month ORDER BY period_year,period_month";
            case PROFIT_BY_PRODUCT, MARGIN ->
                sql =
                    "SELECT f.product_id,f.product_name,"
                        + profit
                        + ",CASE WHEN SUM(f.revenue)=0 THEN NULL ELSE"
                        + " 100*SUM(f.revenue-f.cogs)/SUM(f.revenue) END AS margin_percent"
                        + sales
                        + "GROUP BY f.product_id,f.product_name ORDER BY gross_profit DESC";
            case PROFIT_BY_CATEGORY ->
                sql =
                    "SELECT f.category_name,"
                        + profit
                        + sales
                        + "GROUP BY f.category_name ORDER BY gross_profit DESC";
            case PROFIT_LOSS -> {
              return profitLoss(c, from, to);
            }
            case TRIAL_BALANCE -> {
              sql =
                  "SELECT a.code,a.name,a.account_type,CASE WHEN"
                      + " COALESCE(SUM(l.debit-l.credit),0)>0 THEN SUM(l.debit-l.credit) ELSE 0 END"
                      + " AS debit,CASE WHEN COALESCE(SUM(l.credit-l.debit),0)>0 THEN"
                      + " SUM(l.credit-l.debit) ELSE 0 END AS credit FROM accounts a LEFT JOIN"
                      + " (journal_lines l JOIN journal_entries j ON j.id=l.entry_id AND"
                      + " j.journal_date<=?) ON l.account_id=a.id GROUP BY"
                      + " a.code,a.name,a.account_type ORDER BY a.code";
              params = new Object[] {to};
            }
            case BALANCE_SHEET -> {
              return balanceSheet(c, to);
            }
            case GENERAL_LEDGER -> {
              sql =
                  "SELECT * FROM (SELECT a.code,a.name,j.journal_date,j.number,d.number AS"
                      + " reference,j.description,l.debit,l.credit,SUM(l.debit-l.credit)"
                      + " OVER(PARTITION BY a.id ORDER BY j.journal_date,j.id,l.id) AS"
                      + " running_balance,l.id AS line_id FROM journal_lines l JOIN accounts a ON"
                      + " a.id=l.account_id JOIN journal_entries j ON j.id=l.entry_id JOIN"
                      + " documents d ON d.id=j.document_id WHERE j.journal_date<=?) ledger WHERE"
                      + " journal_date>=? ORDER BY code,journal_date,line_id";
              params = new Object[] {to, from};
            }
            case JOURNALS ->
                sql =
                    "SELECT j.id,j.number,j.journal_date,d.number AS"
                        + " source,j.description,SUM(l.debit) AS debit,SUM(l.credit) AS credit FROM"
                        + " journal_entries j JOIN documents d ON d.id=j.document_id LEFT JOIN"
                        + " journal_lines l ON l.entry_id=j.id WHERE j.journal_date BETWEEN ? AND ?"
                        + " GROUP BY j.id,j.number,j.journal_date,d.number,j.description ORDER BY"
                        + " j.id DESC";
            case INVENTORY, INVENTORY_VALUE -> {
              sql = inventory + "ORDER BY p.name";
              params = new Object[] {};
            }
            case LOW_STOCK, REORDER -> {
              sql =
                  inventory + "AND p.on_hand<=p.reorder_level AND p.active=TRUE ORDER BY p.on_hand";
              params = new Object[] {};
            }
            case OUT_OF_STOCK -> {
              sql = inventory + "AND p.on_hand=0 ORDER BY p.name";
              params = new Object[] {};
            }
            case NEGATIVE_STOCK -> {
              sql = inventory + "AND p.on_hand<0 ORDER BY p.name";
              params = new Object[] {};
            }
            case STOCK_MOVEMENTS, STOCK_ADJUSTMENTS ->
                sql =
                    "SELECT"
                        + " m.id,p.name,d.number,d.document_date,m.movement_type,m.before_qty,m.quantity,m.after_qty,m.cost_value,m.reason"
                        + " FROM stock_movements m JOIN products p ON p.id=m.product_id JOIN"
                        + " documents d ON d.id=m.document_id WHERE d.document_date BETWEEN ? AND ?"
                        + (report == Report.STOCK_ADJUSTMENTS
                            ? " AND m.movement_type IN"
                                + " ('ADJUSTMENT_IN','ADJUSTMENT_OUT','STOCK_COUNT')"
                            : "")
                        + " ORDER BY m.id DESC";
            case SLOW_MOVING, DEAD_STOCK ->
                sql =
                    "SELECT p.id,p.sku,p.name,p.on_hand,p.stock_value,COALESCE(SUM(f.quantity),0)"
                        + " AS sold FROM products p LEFT JOIN v_sales_facts f ON f.product_id=p.id"
                        + " AND f.document_date BETWEEN ? AND ? WHERE p.active=TRUE GROUP BY"
                        + " p.id,p.sku,p.name,p.on_hand,p.stock_value"
                        + (report == Report.DEAD_STOCK
                            ? " HAVING COALESCE(SUM(f.quantity),0)=0"
                            : "")
                        + " ORDER BY sold,p.name";
            case PURCHASES ->
                sql =
                    "SELECT id,number,document_date,party_name,currency_code,total,base_total FROM"
                        + " documents WHERE kind='PURCHASE' AND document_date BETWEEN ? AND ? ORDER"
                        + " BY id DESC";
            case PURCHASES_BY_SUPPLIER ->
                sql =
                    "SELECT party_id,party_name,SUM(base_net*CASE WHEN kind='PURCHASE' THEN 1 ELSE"
                        + " -1 END) AS net_purchases FROM documents WHERE kind IN"
                        + " ('PURCHASE','PURCHASE_RETURN') AND document_date BETWEEN ? AND ? GROUP"
                        + " BY party_id,party_name ORDER BY net_purchases DESC";
            case PURCHASES_BY_PRODUCT ->
                sql =
                    "SELECT l.product_id,l.product_name,SUM(l.quantity*CASE WHEN d.kind='PURCHASE'"
                        + " THEN 1 ELSE -1 END) AS quantity,SUM(l.base_net*CASE WHEN"
                        + " d.kind='PURCHASE' THEN 1 ELSE -1 END) AS net_purchases FROM"
                        + " document_lines l JOIN documents d ON d.id=l.document_id WHERE d.kind IN"
                        + " ('PURCHASE','PURCHASE_RETURN') AND d.document_date BETWEEN ? AND ?"
                        + " GROUP BY l.product_id,l.product_name ORDER BY net_purchases DESC";
            case OUTSTANDING_ORDERS -> {
              sql =
                  "SELECT"
                      + " o.id,o.number,o.ordered_on,o.expected_on,p.name,o.status,l.product_id,l.quantity,l.received_qty,l.quantity-l.received_qty"
                      + " AS remaining FROM purchase_orders o JOIN parties p ON p.id=o.supplier_id"
                      + " JOIN po_lines l ON l.order_id=o.id WHERE o.status IN"
                      + " ('DRAFT','SENT','PARTIALLY_RECEIVED') ORDER BY o.id DESC";
              params = new Object[] {};
            }
            case RECEIVABLE_AGING, PAYABLE_AGING -> {
              return aging(c, report == Report.RECEIVABLE_AGING ? "CUSTOMER" : "SUPPLIER", to);
            }
            case CUSTOMER_PAYMENTS, SUPPLIER_PAYMENTS -> {
              sql =
                  "SELECT"
                      + " d.number,d.document_date,d.party_name,d.currency_code,d.total,d.base_total,p.method_code,p.reference"
                      + " FROM documents d JOIN payments p ON p.document_id=d.id WHERE d.kind=? AND"
                      + " d.document_date BETWEEN ? AND ? ORDER BY d.id DESC";
              params =
                  new Object[] {
                    report == Report.CUSTOMER_PAYMENTS ? "CUSTOMER_PAYMENT" : "SUPPLIER_PAYMENT",
                    from,
                    to
                  };
            }
            case CUSTOMER_BALANCE, SUPPLIER_BALANCE -> {
              sql =
                  "SELECT p.id,p.code,p.name,COALESCE(SUM(e.open_base),0) AS balance FROM parties p"
                      + " LEFT JOIN party_entries e ON e.party_id=p.id WHERE p.kind=? GROUP BY"
                      + " p.id,p.code,p.name ORDER BY balance DESC";
              params = new Object[] {report == Report.CUSTOMER_BALANCE ? "CUSTOMER" : "SUPPLIER"};
            }
            case INACTIVE_CUSTOMERS ->
                sql =
                    "SELECT p.id,p.code,p.name,p.phone FROM parties p WHERE p.kind='CUSTOMER' AND"
                        + " NOT EXISTS(SELECT 1 FROM documents d WHERE d.party_id=p.id AND"
                        + " d.kind='SALE' AND d.document_date BETWEEN ? AND ?) ORDER BY p.name";
            case EXPENSES_BY_DAY -> {
              sql =
                  "SELECT j.journal_date AS document_date,SUM(l.debit-l.credit) AS amount FROM"
                      + " journal_lines l JOIN accounts a ON a.id=l.account_id JOIN journal_entries j"
                      + " ON j.id=l.entry_id WHERE j.journal_date BETWEEN ? AND ? AND"
                      + " a.account_type='EXPENSE' AND a.code NOT IN (?,?) GROUP BY j.journal_date"
                      + " ORDER BY j.journal_date";
              params =
                  new Object[] {
                    from,
                    to,
                    SettingsService.get(c, "map.cogs"),
                    SettingsService.get(c, "map.fx_loss")
                  };
            }
            case EXPENSES_BY_MONTH -> {
              sql =
                  "SELECT EXTRACT(YEAR FROM j.journal_date) AS period_year,EXTRACT(MONTH FROM"
                      + " j.journal_date) AS period_month,SUM(l.debit-l.credit) AS amount FROM"
                      + " journal_lines l JOIN accounts a ON a.id=l.account_id JOIN journal_entries j"
                      + " ON j.id=l.entry_id WHERE j.journal_date BETWEEN ? AND ? AND"
                      + " a.account_type='EXPENSE' AND a.code NOT IN (?,?) GROUP BY"
                      + " period_year,period_month ORDER BY period_year,period_month";
              params =
                  new Object[] {
                    from,
                    to,
                    SettingsService.get(c, "map.cogs"),
                    SettingsService.get(c, "map.fx_loss")
                  };
            }
            case EXPENSES_BY_CATEGORY -> {
              sql =
                  "SELECT a.code,a.name,SUM(l.debit-l.credit) AS amount FROM journal_lines l JOIN"
                      + " accounts a ON a.id=l.account_id JOIN journal_entries j ON"
                      + " j.id=l.entry_id WHERE j.journal_date BETWEEN ? AND ? AND"
                      + " a.account_type='EXPENSE' AND a.code NOT IN (?,?) GROUP BY a.code,a.name"
                      + " ORDER BY amount DESC";
              params =
                  new Object[] {
                    from,
                    to,
                    SettingsService.get(c, "map.cogs"),
                    SettingsService.get(c, "map.fx_loss")
                  };
            }
            case CASH_SESSIONS, CASH_DIFFERENCES ->
                sql =
                    "SELECT"
                        + " s.id,u.full_name,s.opened_at,s.closed_at,s.opening_cash,s.expected_cash,s.counted_cash,s.difference,s.notes"
                        + " FROM cash_sessions s JOIN users u ON u.id=s.user_id WHERE"
                        + " CAST(s.opened_at AS DATE) BETWEEN ? AND ?"
                        + (report == Report.CASH_DIFFERENCES ? " AND s.difference<>0" : "")
                        + " ORDER BY s.id DESC";
            case CASH_MOVEMENTS ->
                sql =
                    "SELECT"
                        + " m.id,m.session_id,m.created_at,m.movement_type,m.amount,m.reason,u.full_name,d.number"
                        + " FROM cash_movements m JOIN users u ON u.id=m.created_by LEFT JOIN"
                        + " documents d ON d.id=m.document_id WHERE CAST(m.created_at AS DATE)"
                        + " BETWEEN ? AND ? ORDER BY m.id DESC";
            case END_OF_DAY -> {
              return summary(c, from, to);
            }
            case GROWTH -> {
              List<Row> current = summary(c, from, to);
              long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
              List<Row> prior = summary(c, from.minusDays(days), from.minusDays(1));
              List<Row> result = new ArrayList<>();
              for (int i = 0; i < current.size(); i++) {
                Row currentRow = current.get(i);
                BigDecimal a = currentRow.money("amount"),
                    b =
                        prior.stream()
                            .filter(item -> item.text("metric").equals(currentRow.text("metric")))
                            .findFirst()
                            .map(item -> item.money("amount"))
                            .orElse(ZERO);
                Row row = new Row();
                row.put("metric", current.get(i).text("metric"));
                row.put("current", a);
                row.put("previous", b);
                row.put(
                    "growth_percent",
                    b.signum() == 0
                        ? null
                        : round(divide(a.subtract(b).multiply(HUNDRED), b.abs()), 2));
                result.add(row);
              }
              return result;
            }
            case INTEGRITY -> {
              return integrity(c);
            }
            default -> throw new IllegalArgumentException("Unknown report.");
          }
          List<Row> rows = Sql.rows(c, sql, params);
          if (report == Report.REORDER)
            for (Row r : rows)
              r.put(
                  "suggested_order",
                  r.money("reorder_level")
                      .multiply(new BigDecimal("2"))
                      .subtract(r.money("on_hand"))
                      .max(BigDecimal.ONE));
          if (report == Report.TRIAL_BALANCE) {
            BigDecimal dr = ZERO, cr = ZERO;
            for (Row r : rows) {
              dr = dr.add(r.money("debit"));
              cr = cr.add(r.money("credit"));
            }
            Row total = new Row();
            total.put("code", "TOTAL");
            total.put("name", "");
            total.put("account_type", "");
            total.put("debit", dr);
            total.put("credit", cr);
            rows.add(total);
          }
          if (!u.can(Permission.PRODUCT_COST_VIEW))
            for (Row row : rows) {
              row.remove("current_cost");
              row.remove("stock_value");
              row.remove("cost_value");
            }
          return rows;
        });
  }

  private static Row metric(String name, BigDecimal value) {
    Row r = new Row();
    r.put("metric", name);
    r.put("amount", value);
    return r;
  }

  public static List<Row> summary(java.sql.Connection c, LocalDate from, LocalDate to)
      throws Exception {
    List<Row> result = new ArrayList<>();
    Row sales =
        Sql.one(
            c,
            "SELECT COALESCE(SUM(CASE WHEN kind='SALE' THEN base_net ELSE 0 END),0) AS"
                + " sales,COALESCE(SUM(CASE WHEN kind='SALE_RETURN' THEN base_net ELSE 0 END),0) AS"
                + " returns,COALESCE(SUM(CASE WHEN kind='SALE' THEN 1 ELSE 0 END),0) AS"
                + " transactions,COALESCE(SUM(discount*rate_to_base),0) AS discounts FROM documents"
                + " WHERE kind IN ('SALE','SALE_RETURN') AND document_date BETWEEN ? AND ?",
            from,
            to);
    result.add(metric("Sales before returns", sales.money("sales")));
    result.add(metric("Returns", sales.money("returns")));
    BigDecimal net = sales.money("sales").subtract(sales.money("returns"));
    result.add(metric("Net sales", net));
    result.add(metric("Transactions", sales.money("transactions")));
    result.add(
        metric(
            "Average sale",
            sales.money("transactions").signum() == 0
                ? ZERO
                : round(
                    divide(sales.money("sales"), sales.money("transactions")),
                    SettingsService.baseScale(c))));
    result.addAll(profitLoss(c, from, to));
    Row receipts =
        Sql.one(
            c,
            "SELECT COALESCE(SUM(p.base_amount*p.direction),0) AS cash FROM payments p JOIN"
                + " payment_methods m ON m.code=p.method_code JOIN documents d ON"
                + " d.id=p.document_id WHERE m.is_cash=TRUE AND d.document_date BETWEEN ? AND ?",
            from,
            to);
    result.add(metric("Net cash payments", receipts.money("cash")));
    Row stock =
        Sql.one(
            c,
            "SELECT COALESCE(SUM(stock_value),0) AS inventory_total,COALESCE(SUM(CASE WHEN"
                + " active=TRUE AND on_hand<=reorder_level THEN 1 ELSE 0 END),0) AS low FROM"
                + " products WHERE track_stock=TRUE");
    result.add(metric("Inventory value", stock.money("inventory_total")));
    result.add(metric("Low stock products", stock.money("low")));
    for (String kind : List.of("CUSTOMER", "SUPPLIER")) {
      Row b =
          Sql.one(
              c,
              "SELECT COALESCE(SUM(e.open_base),0) AS amount FROM party_entries e JOIN parties p ON"
                  + " p.id=e.party_id WHERE p.kind=?",
              kind);
      result.add(metric(kind.equals("CUSTOMER") ? "Receivables" : "Payables", b.money("amount")));
    }
    Row tax =
        Sql.one(
            c,
            "SELECT COALESCE(SUM(tax),0) AS tax,COALESCE(SUM(discount),0) AS discounts FROM"
                + " v_sales_facts WHERE document_date BETWEEN ? AND ?",
            from,
            to);
    result.add(metric("Net tax collected", tax.money("tax")));
    result.add(metric("Net discounts", tax.money("discounts")));
    for (Row method :
        Sql.rows(
            c,
            "SELECT p.method_code,SUM(p.base_amount*p.direction) AS amount FROM payments p JOIN"
                + " documents d ON d.id=p.document_id WHERE d.kind IN ('SALE','SALE_RETURN') AND"
                + " d.document_date BETWEEN ? AND ? GROUP BY p.method_code",
            from,
            to))
      result.add(
          metric(method.text("method_code") + " sales less refunds", method.money("amount")));
    Row
        opening =
            Sql.optional(
                c,
                "SELECT opening_cash FROM cash_sessions WHERE CAST(opened_at AS DATE) BETWEEN ? AND"
                    + " ? ORDER BY opened_at FETCH FIRST 1 ROW ONLY",
                from,
                to),
        closing =
            Sql.optional(
                c,
                "SELECT counted_cash FROM cash_sessions WHERE CAST(closed_at AS DATE) BETWEEN ? AND"
                    + " ? ORDER BY closed_at DESC FETCH FIRST 1 ROW ONLY",
                from,
                to);
    result.add(
        metric("First opening cash", opening == null ? ZERO : opening.money("opening_cash")));
    result.add(metric("Last counted cash", closing == null ? ZERO : closing.money("counted_cash")));
    return result;
  }

  private static List<Row> profitLoss(java.sql.Connection c, LocalDate from, LocalDate to)
      throws Exception {
    String revenue = SettingsService.get(c, "map.revenue"),
        returns = SettingsService.get(c, "map.returns"),
        cogs = SettingsService.get(c, "map.cogs");
    BigDecimal net = ZERO, cost = ZERO, expenses = ZERO, other = ZERO;
    for (Row r :
        Sql.rows(
            c,
            "SELECT a.code,a.account_type,COALESCE(SUM(l.debit-l.credit),0) AS amount FROM"
                + " journal_lines l JOIN accounts a ON a.id=l.account_id JOIN journal_entries j ON"
                + " j.id=l.entry_id JOIN documents d ON d.id=j.document_id LEFT JOIN"
                + " period_closures pc ON pc.document_id=d.id OR pc.reversed_by_document_id=d.id"
                + " WHERE j.journal_date BETWEEN ? AND ?"
                + " AND pc.id IS NULL GROUP BY"
                + " a.code,a.account_type",
            from,
            to)) {
      String code = r.text("code");
      BigDecimal amount = r.money("amount");
      if (code.equals(revenue) || code.equals(returns)) net = net.subtract(amount);
      else if (code.equals(cogs)) cost = cost.add(amount);
      else if (code.equals(SettingsService.get(c, "map.fx_gain"))
          || code.equals(SettingsService.get(c, "map.fx_loss"))
          || r.text("account_type").equals("REVENUE")) other = other.subtract(amount);
      else if (r.text("account_type").equals("EXPENSE")) expenses = expenses.add(amount);
    }
    return List.of(
        metric("Net revenue", net),
        metric("COGS", cost),
        metric("Gross profit", net.subtract(cost)),
        metric("Operating expenses", expenses),
        metric("Other income and FX", other),
        metric("Net profit", net.subtract(cost).subtract(expenses).add(other)));
  }

  private static List<Row> balanceSheet(java.sql.Connection c, LocalDate to) throws Exception {
    List<Row> rows =
        Sql.rows(
            c,
            "SELECT a.code,a.name,a.account_type,COALESCE(SUM(l.debit-l.credit),0) AS signed FROM"
                + " accounts a LEFT JOIN (journal_lines l JOIN journal_entries j ON j.id=l.entry_id"
                + " AND j.journal_date<=?) ON l.account_id=a.id GROUP BY"
                + " a.code,a.name,a.account_type ORDER BY a.code",
            to);
    List<Row> result = new ArrayList<>();
    BigDecimal assets = ZERO, liabilities = ZERO, equity = ZERO, earnings = ZERO;
    BigDecimal customerAdvances =
            Sql.one(
                    c,
                    "SELECT COALESCE(SUM(-e.open_base),0) AS amount FROM party_entries e JOIN"
                        + " parties p ON p.id=e.party_id JOIN documents d ON d.id=e.document_id"
                        + " WHERE p.kind='CUSTOMER' AND e.open_base<0 AND d.document_date<=?",
                    to)
                .money("amount"),
        supplierAdvances =
            Sql.one(
                    c,
                    "SELECT COALESCE(SUM(-e.open_base),0) AS amount FROM party_entries e JOIN"
                        + " parties p ON p.id=e.party_id JOIN documents d ON d.id=e.document_id"
                        + " WHERE p.kind='SUPPLIER' AND e.open_base<0 AND d.document_date<=?",
                    to)
                .money("amount");
    String arCode = SettingsService.get(c, "map.ar"),
        apCode = SettingsService.get(c, "map.ap"),
        supplierAdvanceCode = SettingsService.get(c, "map.supplier_advances"),
        customerAdvanceCode = SettingsService.get(c, "map.customer_advances");
    for (Row r : rows) {
      BigDecimal value = r.money("signed");
      String type = r.text("account_type");
      String code = r.text("code");
      if (type.equals("REVENUE") || type.equals("EXPENSE")) {
        earnings = earnings.subtract(value);
        continue;
      }
      if (code.equals(supplierAdvanceCode) || code.equals(customerAdvanceCode)) continue;
      if (code.equals(arCode)) value = value.add(customerAdvances);
      if (code.equals(apCode)) value = value.subtract(supplierAdvances);
      Row row = new Row();
      row.put("type", type);
      row.put("code", code);
      row.put("name", r.text("name"));
      row.put("amount", type.equals("ASSET") ? value : value.negate());
      result.add(row);
      switch (type) {
        case "ASSET" -> assets = assets.add(value);
        case "LIABILITY" -> liabilities = liabilities.subtract(value);
        case "EQUITY" -> equity = equity.subtract(value);
      }
    }
    if (supplierAdvances.signum() != 0) {
      Row row = new Row();
      row.put("type", "ASSET");
      row.put("code", supplierAdvanceCode);
      row.put("name", "Supplier advances");
      row.put("amount", supplierAdvances);
      result.add(row);
      assets = assets.add(supplierAdvances);
    }
    if (customerAdvances.signum() != 0) {
      Row row = new Row();
      row.put("type", "LIABILITY");
      row.put("code", customerAdvanceCode);
      row.put("name", "Customer advances");
      row.put("amount", customerAdvances);
      result.add(row);
      liabilities = liabilities.add(customerAdvances);
    }
    for (var item :
        List.of(
            Map.entry("Unclosed earnings", earnings),
            Map.entry("Total assets", assets),
            Map.entry("Total liabilities and equity", liabilities.add(equity).add(earnings)),
            Map.entry(
                "Balance check (zero)",
                assets.subtract(liabilities).subtract(equity).subtract(earnings)))) {
      Row row = new Row();
      row.put("type", "TOTAL");
      row.put("code", "");
      row.put("name", item.getKey());
      row.put("amount", item.getValue());
      result.add(row);
    }
    return result;
  }

  private static List<Row> aging(java.sql.Connection c, String kind, LocalDate asOf)
      throws Exception {
    // Open amounts are current; selected date determines aging buckets, not historical
    // reconstruction.
    return Sql.rows(
        c,
        "SELECT p.id,p.name,SUM(e.open_base) AS outstanding,"
            + "SUM(CASE WHEN e.open_amount<0 THEN e.open_base ELSE 0 END) AS unapplied_credit,"
            + "SUM(CASE WHEN e.open_amount>0 AND (e.due_date IS NULL OR"
            + " e.due_date>=?) THEN e.open_base ELSE 0 END) AS current_bucket,SUM(CASE WHEN e.open_amount>0 AND"
            + " DATEDIFF('DAY',e.due_date,?) BETWEEN 1 AND 30 THEN e.open_base ELSE 0 END) AS"
            + " days_1_30,SUM(CASE WHEN e.open_amount>0 AND DATEDIFF('DAY',e.due_date,?) BETWEEN 31 AND 60 THEN"
            + " e.open_base ELSE 0 END) AS days_31_60,SUM(CASE WHEN e.open_amount>0 AND DATEDIFF('DAY',e.due_date,?)"
            + " BETWEEN 61 AND 90 THEN e.open_base ELSE 0 END) AS days_61_90,SUM(CASE WHEN e.open_amount>0 AND"
            + " DATEDIFF('DAY',e.due_date,?)>90 THEN e.open_base ELSE 0 END) AS"
            + " days_90_plus,MIN(e.due_date) AS oldest_due FROM party_entries e JOIN parties p ON"
            + " p.id=e.party_id WHERE p.kind=? AND e.open_amount<>0 GROUP BY p.id,p.name ORDER BY"
            + " outstanding DESC",
        asOf,
        asOf,
        asOf,
        asOf,
        asOf,
        kind);
  }

  public static List<Row> integrity(java.sql.Connection c) throws Exception {
    List<Row> r = new ArrayList<>();
    String[] tests = {
      "Unbalanced journals|SELECT COUNT(*) FROM (SELECT entry_id FROM journal_lines GROUP BY"
          + " entry_id HAVING SUM(debit)<>SUM(credit)) x",
      "Documents missing journals|SELECT COUNT(*) FROM documents d WHERE NOT EXISTS(SELECT 1 FROM"
          + " journal_entries j WHERE j.document_id=d.id)",
      "Stock quantity mismatch|SELECT COUNT(*) FROM products p WHERE p.on_hand<>COALESCE((SELECT"
          + " SUM(m.quantity) FROM stock_movements m WHERE m.product_id=p.id),0)",
      "Inventory value mismatch|SELECT COUNT(*) FROM products p WHERE"
          + " p.stock_value<>COALESCE((SELECT SUM(m.cost_value) FROM stock_movements m WHERE"
          + " m.product_id=p.id),0)",
      "Negative stock (review)|SELECT COUNT(*) FROM products WHERE on_hand<0",
      "Duplicate barcodes|SELECT COUNT(*) FROM (SELECT barcode FROM product_barcodes GROUP BY"
          + " barcode HAVING COUNT(*)>1) x",
      "Invalid exchange rates|SELECT COUNT(*) FROM documents WHERE rate_to_base<=0",
      "Invoice arithmetic mismatch|SELECT COUNT(*) FROM documents WHERE kind IN"
          + " ('SALE','PURCHASE','SALE_RETURN','PURCHASE_RETURN') AND (total<>net+tax OR"
          + " base_total<>base_net+base_tax)",
      "Orphaned cost layers|SELECT COUNT(*) FROM cost_layers WHERE remaining_qty=0 AND"
          + " remaining_value<>0"
    };
    for (String test : tests) {
      String[] a = test.split("\\|", 2);
      r.add(metric(a[0], BigDecimal.valueOf(Sql.count(c, a[1]))));
    }
    if (SettingsService.get(c, "costing").equals("FIFO"))
      r.add(
          metric(
              "FIFO layer mismatch",
              BigDecimal.valueOf(
                  Sql.count(
                      c,
                      "SELECT COUNT(*) FROM products p WHERE p.track_stock=TRUE AND"
                          + " (p.on_hand<>COALESCE((SELECT SUM(l.remaining_qty) FROM cost_layers l"
                          + " WHERE l.product_id=p.id),0) OR p.stock_value<>COALESCE((SELECT"
                          + " SUM(l.remaining_value) FROM cost_layers l WHERE"
                          + " l.product_id=p.id),0))"))));
    for (String code : List.of("ar", "ap", "inventory")) {
      BigDecimal gl =
          Sql.one(
                  c,
                  "SELECT COALESCE(SUM(l.debit-l.credit),0) AS amount FROM journal_lines l JOIN"
                      + " accounts a ON a.id=l.account_id WHERE a.code=?",
                  SettingsService.get(c, "map." + code))
              .money("amount");
      BigDecimal sub =
          code.equals("inventory")
              ? Sql.one(c, "SELECT COALESCE(SUM(stock_value),0) AS amount FROM products")
                  .money("amount")
              : Sql.one(
                      c,
                      "SELECT COALESCE(SUM(e.open_base),0) AS amount FROM party_entries e JOIN"
                          + " parties p ON p.id=e.party_id WHERE p.kind=?",
                      code.equals("ar") ? "CUSTOMER" : "SUPPLIER")
                  .money("amount");
      r.add(
          metric(
              code + " ledger difference (zero)",
              gl.subtract(code.equals("ap") ? sub.negate() : sub)));
    }
    BigDecimal
        sales =
            Sql.one(c, "SELECT COALESCE(SUM(revenue),0) AS amount FROM v_sales_facts")
                .money("amount"),
        ledger =
            Sql.one(
                    c,
                    "SELECT COALESCE(SUM(l.credit-l.debit),0) AS amount FROM journal_lines l JOIN"
                        + " accounts a ON a.id=l.account_id JOIN journal_entries j ON"
                        + " j.id=l.entry_id JOIN documents d ON d.id=j.document_id LEFT JOIN"
                        + " period_closures pc ON pc.document_id=d.id OR"
                        + " pc.reversed_by_document_id=d.id WHERE a.code IN (?,?) AND"
                        + " pc.id IS NULL",
                    SettingsService.get(c, "map.revenue"),
                    SettingsService.get(c, "map.returns"))
                .money("amount");
    r.add(metric("Sales / ledger difference (zero)", sales.subtract(ledger)));
    return r;
  }
}
