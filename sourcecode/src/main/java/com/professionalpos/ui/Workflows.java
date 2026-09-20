package com.professionalpos.ui;

import static com.professionalpos.util.Money.*;

import com.professionalpos.*;
import com.professionalpos.accounting.AccountingService.Posting;
import com.professionalpos.db.*;
import com.professionalpos.i18n.I18n;
import com.professionalpos.model.*;
import com.professionalpos.printing.ReceiptPrinter;
import com.professionalpos.reporting.ReportService;
import com.professionalpos.security.Permission;
import com.professionalpos.service.*;
import com.professionalpos.util.*;
import java.awt.*;
import java.awt.print.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.swing.*;

public final class Workflows {
  private final MainFrame frame;
  private final App a;

  public Workflows(MainFrame frame) {
    this.frame = frame;
    a = frame.app;
  }

  private void add(JPanel p, String key, Permission permission, Runnable action) {
    if (a.user.can(permission)) p.add(Ui.button(key, action));
  }

  public JPanel toolbar(DataPanel p) {
    JPanel bar = Ui.bar(Ui.button("refresh", p::reload), Ui.button("details", () -> details(p)));
    switch (p.page) {
      case PRODUCTS -> {
        add(bar, "add", Permission.PRODUCT_CREATE, () -> product(null, ""));
        add(bar, "edit", Permission.PRODUCT_EDIT, () -> product(p.selected().id(), ""));
        add(bar, "archive", Permission.PRODUCT_ARCHIVE, () -> archive(p));
        add(
            bar,
            "reactivate",
            Permission.PRODUCT_EDIT,
            () ->
                Ui.work(
                    frame,
                    () -> {
                      a.catalog.restoreProduct(a.user, p.selected().id());
                      return null;
                    },
                    x -> frame.refresh()));
        add(bar, "import_csv", Permission.PRODUCT_CREATE, this::importCsv);
      }
      case INVENTORY -> {
        add(bar, "adjust_stock", Permission.INVENTORY_ADJUST, () -> adjust(p.selected()));
        add(bar, "revalue_inventory", Permission.INVENTORY_ADJUST, () -> revalueInventory(p.selected()));
        add(bar, "damaged_inventory", Permission.INVENTORY_ADJUST, this::damagedInventory);
        add(bar, "stock_counts", Permission.INVENTORY_ADJUST, this::counts);
        add(
            bar,
            "reorder",
            Permission.PURCHASE_CREATE,
            () -> frame.showReport(ReportService.Report.REORDER));
      }
      case CUSTOMERS -> {
        add(bar, "add", Permission.CUSTOMER_EDIT, () -> party(null, "CUSTOMER"));
        add(bar, "edit", Permission.CUSTOMER_EDIT, () -> party(p.selected().id(), "CUSTOMER"));
        add(
            bar,
            "receive_payment",
            Permission.DEBT_RECEIVE,
            () -> payment(p.selected().id(), null));
      }
      case SUPPLIERS -> {
        add(bar, "add", Permission.PURCHASE_CREATE, () -> party(null, "SUPPLIER"));
        add(bar, "edit", Permission.PURCHASE_CREATE, () -> party(p.selected().id(), "SUPPLIER"));
        add(bar, "pay_supplier", Permission.DEBT_PAY, () -> payment(p.selected().id(), null));
      }
      case SALES -> {
        add(
            bar,
            "return_items",
            Permission.RETURN_CREATE,
            () -> returns(p.selected().id(), false, false));
        add(
            bar,
            "exchange",
            Permission.RETURN_CREATE,
            () -> returns(p.selected().id(), false, true));
        add(bar, "void_sale", Permission.SALE_VOID, () -> returns(p.selected().id(), true, false));
      }
      case PURCHASES -> {
        add(bar, "new_purchase", Permission.PURCHASE_CREATE, () -> frame.showPurchase(null));
        add(
            bar,
            "return_items",
            Permission.PURCHASE_CREATE,
            () -> returns(p.selected().id(), false, false));
      }
      case ORDERS -> {
        add(bar, "new_order", Permission.PURCHASE_CREATE, () -> frame.showPurchase(null));
        add(
            bar,
            "receive_order",
            Permission.PURCHASE_CREATE,
            () -> frame.showPurchase(p.selected().id()));
        add(bar, "mark_sent", Permission.PURCHASE_CREATE, () -> orderStatus(p, "SENT"));
        add(bar, "cancel_order", Permission.PURCHASE_CREATE, () -> orderStatus(p, "CANCELLED"));
        add(
            bar,
            "delete_draft",
            Permission.PURCHASE_CREATE,
            () -> {
              long id = p.selected().id();
              if (Ui.confirm(frame, "delete_draft_confirm"))
                Ui.work(
                    frame,
                    () -> {
                      a.purchase.deleteDraft(a.user, id);
                      return null;
                    },
                    x -> frame.refresh());
            });
      }
      case EXPENSES -> add(bar, "record_expense", Permission.EXPENSE_CREATE, this::expense);
      case DEBTS -> {
        bar.add(
            Ui.button(
                "record_payment",
                () -> {
                  Row r = p.selected();
                  payment(r.number("party_id"), r.id(), r);
                }));
        add(bar, "due_date", Permission.ACCOUNTING_POST, () -> due(p.selected()));
        add(
            bar,
            "aging",
            Permission.REPORT_ACCOUNTING,
            () -> frame.showReport(ReportService.Report.RECEIVABLE_AGING));
      }
      case HELD -> {
        bar.add(Ui.button("resume", () -> frame.restoreHeld(p.selected().id())));
        bar.add(
            Ui.button(
                "delete_draft",
                () -> {
                  long id = p.selected().id();
                  if (Ui.confirm(frame, "delete_draft_confirm"))
                    Ui.work(
                        frame,
                        () -> {
                          a.trade.deleteHeld(a.user, id);
                          return null;
                        },
                        x -> frame.refresh());
                }));
      }
      case CASH -> {
        bar.add(Ui.button("open_shift", this::openCash));
        bar.add(
            Ui.button(
                "close_shift",
                () -> closeCash(p.table.getSelectedRow() < 0 ? null : p.selected().id())));
        add(bar, "cash_in", Permission.ACCOUNTING_POST, () -> cashMove(true));
        add(bar, "cash_out", Permission.ACCOUNTING_POST, () -> cashMove(false));
      }
      case USERS -> {
        bar.add(Ui.button("add", () -> user(null)));
        bar.add(Ui.button("edit_reset", () -> user(p.selected())));
        bar.add(Ui.button("permissions", this::permissions));
        bar.add(Ui.button("new_role", this::role));
      }
      case ACCOUNTS -> {
        add(bar, "add_account", Permission.ACCOUNTING_POST, this::account);
        add(bar, "manual_journal", Permission.ACCOUNTING_POST, this::journal);
        add(bar, "revalue_fx", Permission.ACCOUNTING_POST, this::revalueFx);
        add(bar, "card_settlement", Permission.ACCOUNTING_POST, this::cardSettlement);
        add(
            bar,
            "trial_balance",
            Permission.REPORT_ACCOUNTING,
            () -> frame.showReport(ReportService.Report.TRIAL_BALANCE));
        add(
            bar,
            "profit_loss",
            Permission.REPORT_PROFIT,
            () -> frame.showReport(ReportService.Report.PROFIT_LOSS));
        add(
            bar,
            "balance_sheet",
            Permission.REPORT_ACCOUNTING,
            () -> frame.showReport(ReportService.Report.BALANCE_SHEET));
      }
      case JOURNALS -> {
        add(bar, "manual_journal", Permission.ACCOUNTING_POST, this::journal);
        add(bar, "reverse", Permission.ACCOUNTING_POST, () -> reverse(p.selected().id()));
        add(bar, "period_locks", Permission.ACCOUNTING_POST, this::periods);
      }
      case NOTIFICATIONS -> {
        bar.add(
            Ui.button(
                "mark_read",
                () -> {
                  long id = p.selected().id();
                  Ui.work(
                      frame,
                      () -> {
                        a.notifications.read(a.user, id);
                        return null;
                      },
                      x -> frame.refresh());
                }));
        bar.add(
            Ui.button(
                "check_now",
                () ->
                    Ui.work(
                        frame,
                        () -> {
                          a.notifications.refresh();
                          return null;
                        },
                        x -> frame.refresh())));
      }
      case CURRENCIES -> bar.add(Ui.button("add", this::currency));
      case RATES -> bar.add(Ui.button("add", this::rate));
      case TAXES -> bar.add(Ui.button("add", () -> reference("tax_rates")));
      case CATEGORIES -> bar.add(Ui.button("add", () -> reference("categories")));
      case UNITS -> bar.add(Ui.button("add", () -> reference("units")));
      default -> {}
    }
    return bar;
  }

  public void details(DataPanel panel) {
    try {
      Row row = panel.selected();
      switch (panel.page) {
        case PRODUCTS, INVENTORY -> productDetails(row.id());
        case CUSTOMERS, SUPPLIERS -> partyDetails(row.id());
        case SALES, PURCHASES, EXPENSES -> receipt(row.id());
        case DEBTS -> partyDetails(row.number("party_id"));
        case ORDERS -> showChildren("po_lines", row.id(), "purchase_order");
        case HELD -> frame.restoreHeld(row.id());
        case JOURNALS -> showChildren("journal_lines", row.id(), "journal");
        case NOTIFICATIONS -> {
          switch (row.text("kind")) {
            case "STOCK" -> productDetails(row.number("entity_id"));
            case "DEBT" -> frame.showPage("debts");
            case "CASH" -> frame.showPage("cash");
            default -> frame.showPage("settings");
          }
        }
        default -> showRows(panel.page.name(), List.of(row));
      }
    } catch (Exception ex) {
      Ui.error(panel, ex);
    }
  }

  private void editor(String title, JComponent form, Supplier<Callable<?>> save) {
    JPanel root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    root.add(form instanceof JTabbedPane ? form : new JScrollPane(form));
    JDialog d = Ui.dialog(frame, title, root, 730, 650);
    JButton submit =
        Ui.button(
            "save",
            () -> {
              Callable<?> task = save.get();
              Ui.work(
                  d,
                  task,
                  v -> {
                    d.dispose();
                    frame.refresh();
                  });
            });
    Theme.primary(submit);
    Runnable cancel =
        () -> {
          boolean dirty = form instanceof Form f && f.changed();
          if (form instanceof JTabbedPane tabs)
            for (Component child : tabs.getComponents())
              if (child instanceof JScrollPane s && s.getViewport().getView() instanceof Form f)
                dirty |= f.changed();
          if (!Ui.busy(d) && (!dirty || Ui.confirm(d, "discard_changes"))) d.dispose();
        };
    d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    d.addWindowListener(
        new java.awt.event.WindowAdapter() {
          public void windowClosing(java.awt.event.WindowEvent e) {
            cancel.run();
          }
        });
    d.getRootPane()
        .registerKeyboardAction(
            e -> cancel.run(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    root.add(Ui.bar(Ui.button("cancel", cancel), submit), BorderLayout.SOUTH);
    d.setVisible(true);
  }

  private static JTabbedPane tabs(Object... pairs) {
    JTabbedPane tabs = new JTabbedPane();
    for (int i = 0; i < pairs.length; i += 2)
      tabs.addTab(I18n.t(pairs[i].toString()), new JScrollPane((Component) pairs[i + 1]));
    return tabs;
  }

  private Map<String, List<Row>> refs(String... types) {
    Map<String, List<Row>> result = new HashMap<>();
    for (String type : types) result.put(type, a.queries.choices(a.user, type));
    return result;
  }

  public void product(Long id, String scanned) {
    Ui.work(
        frame,
        () -> {
          Map<String, Object> data = new HashMap<>();
          data.put("refs", refs("categories", "units", "tax_rates", "suppliers"));
          data.put("product", id == null ? new Row() : a.queries.record(a.user, "products", id));
          data.put("barcodes", id == null ? List.of() : a.queries.child(a.user, "barcodes", id));
          return data;
        },
        data -> {
          @SuppressWarnings("unchecked")
          Map<String, List<Row>> refs = (Map<String, List<Row>>) data.get("refs");
          Row p = (Row) data.get("product");
          @SuppressWarnings("unchecked")
          List<Row> codes = (List<Row>) data.get("barcodes");
          String barcodes =
              id == null
                  ? scanned
                  : String.join(",", codes.stream().map(r -> r.text("barcode")).toList());
          Form basic =
              new Form()
                  .text("sku", p.text("sku"))
                  .text("barcodes", barcodes)
                  .text("name", p.text("name"))
                  .text("name_ar", p.text("name_ar"))
                  .text("price", p.money("price"))
                  .text("cost", p.money("current_cost"))
                  .text("opening_stock", "0")
                  .lookup("category", refs.get("categories"), p.get("category_id"), true)
                  .lookup("unit", refs.get("units"), id == null ? 1 : p.get("unit_id"), false)
                  .lookup("tax", refs.get("tax_rates"), id == null ? 1 : p.get("tax_id"), false);
          Form stock =
              new Form()
                  .check("track_stock", id == null || p.flag("track_stock"))
                  .check("fractional", p.flag("fractional"))
                  .check("allow_negative", p.flag("allow_negative"))
                  .text("min_stock", p.money("min_stock"))
                  .text("reorder_level", p.money("reorder_level"))
                  .lookup(
                      "preferred_supplier",
                      refs.get("suppliers"),
                      p.get("preferred_supplier_id"),
                      true)
                  .check("favorite", p.flag("favorite"));
          Form more =
              new Form()
                  .text("description", p.text("description"))
                  .text("brand", p.text("brand"))
                  .text("wholesale_price", p.get("wholesale_price"))
                  .text("variant_label", p.text("variant_label"))
                  .text("parent_product_id", p.get("parent_product_id"))
                  .text("reason", id == null ? "New product" : "");
          basic.clean();
          stock.clean();
          more.clean();
          editor(
              id == null ? "add_product" : "edit_product",
              tabs("basic", basic, "inventory", stock, "more_details", more),
              () -> {
                Map<String, Object> fields =
                    Sql.values(
                        "sku",
                        basic.text("sku"),
                        "name",
                        basic.text("name"),
                        "name_ar",
                        basic.text("name_ar"),
                        "price",
                        basic.decimal("price"),
                        "category_id",
                        basic.id("category"),
                        "unit_id",
                        basic.id("unit"),
                        "tax_id",
                        basic.id("tax"),
                        "track_stock",
                        stock.flag("track_stock"),
                        "fractional",
                        stock.flag("fractional"),
                        "allow_negative",
                        stock.flag("allow_negative"),
                        "min_stock",
                        stock.decimal("min_stock"),
                        "reorder_level",
                        stock.decimal("reorder_level"),
                        "preferred_supplier_id",
                        stock.id("preferred_supplier"),
                        "favorite",
                        stock.flag("favorite"),
                        "description",
                        more.text("description"),
                        "brand",
                        more.text("brand"),
                        "wholesale_price",
                        more.text("wholesale_price").isBlank()
                            ? null
                            : more.decimal("wholesale_price"),
                        "parent_product_id",
                        more.text("parent_product_id").isBlank()
                            ? null
                            : more.id("parent_product_id"),
                        "variant_label",
                        more.text("variant_label"));
                List<String> bc = Arrays.asList(basic.text("barcodes").split(","));
                BigDecimal cost = basic.decimal("cost"), opening = basic.decimal("opening_stock");
                String reason = more.text("reason");
                return () -> a.catalog.product(a.user, id, fields, bc, cost, opening, reason);
              });
        });
  }

  private void archive(DataPanel panel) {
    Row row = panel.selected();
    if (Ui.confirm(
        frame, row.money("on_hand").signum() != 0 ? "archive_with_stock" : "archive_confirm"))
      Ui.work(
          frame,
          () -> {
            a.catalog.archive(a.user, row.id(), true);
            return null;
          },
          v -> frame.refresh());
  }

  public void productDetails(long id) {
    Ui.work(
        frame,
        () -> {
          Map<String, List<Row>> sections = new LinkedHashMap<>();
          if (a.user.can(Permission.PRODUCT_EDIT))
            sections.put("overview", List.of(a.queries.record(a.user, "products", id)));
          for (String key :
              List.of(
                  "product_movements", "product_sales", "product_purchases", "product_history")) {
            Permission p =
                switch (key) {
                  case "product_history" -> Permission.PRODUCT_COST_VIEW;
                  case "product_movements" -> Permission.INVENTORY_VIEW;
                  case "product_purchases" -> Permission.PURCHASE_VIEW;
                  default -> Permission.REPORT_SALES;
                };
            if (a.user.can(p)) sections.put(key, a.queries.child(a.user, key, id));
          }
          return sections;
        },
        this::showSections);
  }

  public void party(Long id, String kind) {
    Ui.work(
        frame,
        () -> {
          Map<String, Object> d = new HashMap<>();
          d.put("row", id == null ? new Row() : a.queries.record(a.user, "parties", id));
          d.put("currencies", a.queries.choices(a.user, "currencies"));
          return d;
        },
        data -> {
          Row p = (Row) data.get("row");
          @SuppressWarnings("unchecked")
          List<Row> currencies = (List<Row>) data.get("currencies");
          Form basic =
              new Form()
                  .text("code", p.text("code"))
                  .text("name", p.text("name"))
                  .text("phone", p.text("phone"))
                  .text("email", p.text("email"))
                  .lookup(
                      "currency", currencies, id == null ? a.base() : p.get("currency_code"), false)
                  .text("credit_limit", p.money("credit_limit"))
                  .text("terms_days", p.number("terms_days"))
                  .text("discount_pct", p.money("discount_pct"));
          Form more =
              new Form()
                  .text("company", p.text("company"))
                  .text("contact", p.text("contact"))
                  .text("address", p.text("address"))
                  .text("tax_number", p.text("tax_number"))
                  .text("notes", p.text("notes"))
                  .text("opening_balance", "0")
                  .check("active", id == null || p.flag("active"));
          basic.clean();
          more.clean();
          editor(
              kind.equals("CUSTOMER") ? "customer" : "supplier",
              tabs("basic", basic, "more_details", more),
              () -> {
                Map<String, Object> fields =
                    Sql.values(
                        "code",
                        basic.text("code"),
                        "name",
                        basic.text("name"),
                        "phone",
                        basic.text("phone"),
                        "email",
                        basic.text("email"),
                        "currency_code",
                        basic.text("currency"),
                        "credit_limit",
                        basic.decimal("credit_limit"),
                        "terms_days",
                        Integer.parseInt(basic.text("terms_days")),
                        "discount_pct",
                        basic.decimal("discount_pct"),
                        "company",
                        more.text("company"),
                        "contact",
                        more.text("contact"),
                        "address",
                        more.text("address"),
                        "tax_number",
                        more.text("tax_number"),
                        "notes",
                        more.text("notes"),
                        "active",
                        more.flag("active"));
                BigDecimal opening = more.decimal("opening_balance");
                return () -> a.catalog.party(a.user, id, kind, fields, opening);
              });
        });
  }

  public void partyDetails(long id) {
    Ui.work(
        frame,
        () -> {
          Map<String, List<Row>> data = new LinkedHashMap<>();
          data.put("overview", List.of(a.queries.record(a.user, "parties", id)));
          data.put("account_statement", a.queries.child(a.user, "party_statement", id));
          data.put("history", a.queries.child(a.user, "party_history", id));
          return data;
        },
        this::showSections);
  }

  private void adjust(Row product) {
    Form f =
        new Form()
            .text("product", product.text("name"))
            .text("current_quantity", product.money("on_hand"))
            .text("counted_quantity", product.money("on_hand"))
            .text("reason", "");
    f.field("product").setEnabled(false);
    f.field("current_quantity").setEnabled(false);
    editor(
        "adjust_stock",
        f,
        () -> {
          BigDecimal quantity = f.decimal("counted_quantity");
          String reason = f.text("reason");
          return () -> a.inventory.adjust(a.user, product.id(), quantity, reason);
        });
  }

  private void revalueInventory(Row product) {
    Form f =
        new Form()
            .text("product", product.text("name"))
            .text("current_value", product.money("stock_value"))
            .text("new_value", product.money("stock_value"))
            .text("date", a.today())
            .text("reason", "");
    f.field("product").setEnabled(false);
    f.field("current_value").setEnabled(false);
    editor(
        "revalue_inventory",
        f,
        () -> {
          BigDecimal value = f.decimal("new_value");
          LocalDate date = f.date("date");
          String reason = f.text("reason"), key = Documents.key();
          return () -> a.inventory.revalue(a.user, product.id(), value, date, reason, key);
        });
  }

  private void damagedInventory() {
    Ui.work(
        frame,
        () ->
            a.db.read(
                c ->
                    Sql.rows(
                        c,
                        "SELECT d.id,p.name,d.quantity,d.remaining_quantity,d.historical_cost,"
                            + "d.carrying_value,d.status FROM damaged_inventory d JOIN products p"
                            + " ON p.id=d.product_id WHERE d.status<>'DISPOSED' ORDER BY d.id DESC")),
        rows -> {
          JTable table = Ui.table();
          Ui.rows(table, rows);
          Form f =
              new Form()
                  .text("new_value", "")
                  .text("quantity", "")
                  .text("date", a.today())
                  .text("reason", "");
          JPanel root = new JPanel(new BorderLayout());
          root.add(new JScrollPane(table));
          root.add(f, BorderLayout.NORTH);
          JDialog d = Ui.dialog(frame, "damaged_inventory", root, 920, 600);
          root.add(
              Ui.bar(
                  Ui.button(
                      "value_damaged",
                      () -> {
                        long id = Ui.selected(table).id();
                        Ui.work(
                            d,
                            () ->
                                a.inventory.valueDamaged(
                                    a.user,
                                    id,
                                    f.decimal("new_value"),
                                    f.date("date"),
                                    f.text("reason"),
                                    Documents.key()),
                            x -> d.dispose());
                      }),
                  Ui.button(
                      "dispose_damaged",
                      () -> {
                        long id = Ui.selected(table).id();
                        Ui.work(
                            d,
                            () ->
                                a.inventory.disposeDamaged(
                                    a.user,
                                    id,
                                    f.decimal("quantity"),
                                    f.date("date"),
                                    f.text("reason"),
                                    Documents.key()),
                            x -> d.dispose());
                      }),
                  Ui.button("close", d::dispose)),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  public void payment(long partyId, Long entryId) {
    payment(partyId, entryId, null);
  }

  private void payment(long partyId, Long entryId, Row selectedEntry) {
    Ui.work(
        frame,
        () -> {
          Map<String, Object> d = new HashMap<>();
          d.put("party", a.queries.record(a.user, "parties", partyId));
          d.put("refs", refs("currencies", "payment_methods"));
          return d;
        },
        data -> {
          Row p = (Row) data.get("party");
          @SuppressWarnings("unchecked")
          Map<String, List<Row>> r = (Map<String, List<Row>>) data.get("refs");
          boolean creditRefund =
              selectedEntry != null && selectedEntry.money("open_amount").signum() < 0;
          Form f =
              new Form()
                  .text("contact_name", p.text("name"))
                  .text("date", a.today())
                  .lookup(
                      "currency",
                      r.get("currencies"),
                      selectedEntry == null
                          ? p.get("currency_code")
                          : selectedEntry.get("currency_code"),
                      false)
                  .text(
                      "amount",
                      creditRefund ? selectedEntry.money("open_amount").abs().toPlainString() : "")
                  .lookup("method", r.get("payment_methods"), "CASH", false)
                  .text("reference", "")
                  .check("selected_invoice_only", entryId != null && !creditRefund);
          f.field("contact_name").setEnabled(false);
          editor(
              creditRefund
                  ? p.text("kind").equals("CUSTOMER")
                      ? "refund_customer_credit"
                      : "receive_supplier_refund"
                  : p.text("kind").equals("CUSTOMER") ? "receive_payment" : "pay_supplier",
              f,
              () -> {
                LocalDate date = f.date("date");
                String curr = f.text("currency"),
                    method = f.text("method"),
                    ref = f.text("reference"),
                    key = Documents.key();
                BigDecimal amount = f.decimal("amount");
                List<Long> alloc =
                    entryId != null && f.flag("selected_invoice_only")
                        ? List.of(entryId)
                        : List.of();
                return () ->
                    creditRefund
                        ? a.debt.refundCredit(
                            a.user, partyId, date, curr, amount, method, ref, key)
                        : a.debt.payment(
                            a.user, partyId, date, curr, amount, method, ref, alloc, key);
              });
        });
  }

  private void due(Row row) {
    Form f =
        new Form()
            .text("due_date", row.get("due_date"))
            .text("reminder_date", row.get("reminder_date"));
    editor(
        "due_date",
        f,
        () -> {
          LocalDate due = f.optionalDate("due_date"), remind = f.optionalDate("reminder_date");
          return () -> {
            a.debt.reminder(a.user, row.id(), due, remind);
            return null;
          };
        });
  }

  public void expense() {
    Ui.work(
        frame,
        () -> refs("accounts", "currencies", "payment_methods", "suppliers"),
        r -> {
          List<Row> accounts =
              r.get("accounts").stream()
                  .filter(
                      v ->
                          v.text("account_type").equals("EXPENSE")
                              && v.text("code").startsWith("6"))
                  .toList();
          Form f =
              new Form()
                  .text("date", a.today())
                  .lookup("expense_account", accounts, null, false)
                  .text("description", "")
                  .lookup("currency", r.get("currencies"), a.base(), false)
                  .text("amount", "")
                  .text("tax_rate", "0")
                  .check("tax_inclusive", false)
                  .choices(
                      "method", new String[] {"CASH", "CARD", "BANK", "OTHER", "ACCOUNT"}, "CASH")
                  .lookup("supplier", r.get("suppliers"), null, true)
                  .text("due_date", a.today().plusDays(30))
                  .text("reference", "");
          editor(
              "record_expense",
              f,
              () -> {
                long accountId = f.id("expense_account");
                String account =
                    accounts.stream()
                        .filter(x -> x.id() == accountId)
                        .findFirst()
                        .orElseThrow()
                        .text("code");
                LocalDate date = f.date("date"), due = f.optionalDate("due_date");
                String desc = f.text("description"),
                    curr = f.text("currency"),
                    method = f.text("method"),
                    ref = f.text("reference"),
                    key = Documents.key();
                BigDecimal amount = f.decimal("amount"), taxRate = f.decimal("tax_rate");
                boolean taxInclusive = f.flag("tax_inclusive");
                Long supplier = f.id("supplier");
                return () ->
                    a.expenses.record(
                        a.user,
                        date,
                        account,
                        desc,
                        curr,
                        amount,
                        taxRate,
                        taxInclusive,
                        method,
                        supplier,
                        due,
                        ref,
                        key);
              });
        });
  }

  public void returns(long originalId, boolean voidSale, boolean exchange) {
    Ui.work(
        frame,
        () -> a.queries.document(a.user, originalId),
        detail -> {
          Row original = detail.header();
          List<Row> lines = detail.lines();
          JTable table = Ui.table();
          javax.swing.table.DefaultTableModel model =
              new javax.swing.table.DefaultTableModel(
                  new String[] {
                    I18n.t("product"),
                    I18n.t("sold"),
                    I18n.t("previous_returns"),
                    I18n.t("return_quantity"),
                    I18n.t("disposition")
                  },
                  0) {
                public boolean isCellEditable(int row, int col) {
                  return col == 3 || col == 4;
                }
              };
          for (Row l : lines)
            model.addRow(
                new Object[] {
                  l.text("product_name"),
                  l.money("quantity"),
                  l.money("returned_qty"),
                  voidSale ? l.money("quantity").subtract(l.money("returned_qty")) : ZERO,
                  "SELLABLE"
                });
          table.setModel(model);
          table
              .getColumnModel()
              .getColumn(4)
              .setCellEditor(
                  new DefaultCellEditor(
                      new JComboBox<>(new String[] {"SELLABLE", "DAMAGED", "DISCARD"})));
          Form f =
              new Form()
                  .choices(
                      "refund_method",
                      new String[] {"CASH", "CARD", "BANK", "OTHER", "ACCOUNT"},
                      original.nullableId("party_id") == null
                              || original.money("account_outstanding").signum() == 0
                          ? "CASH"
                          : "ACCOUNT")
                  .text("reason", "")
                  .text("date", a.today());
          User[] approval = {null};
          JPanel root = new JPanel(new BorderLayout(8, 8));
          root.add(new JScrollPane(table));
          root.add(f, BorderLayout.SOUTH);
          JPanel container = new JPanel(new BorderLayout());
          container.add(root);
          JDialog d =
              Ui.dialog(
                  frame,
                  exchange ? "exchange" : voidSale ? "void_sale" : "return_items",
                  container,
                  950,
                  650);
          JButton commit =
              Ui.button(
                  exchange ? "choose_replacement" : "complete",
                  () -> {
                    if (table.isEditing()) table.getCellEditor().stopCellEditing();
                    List<Trade.ReturnLine> chosen = new ArrayList<>();
                    for (int i = 0; i < lines.size(); i++) {
                      BigDecimal qty = of(String.valueOf(model.getValueAt(i, 3)));
                      if (qty.signum() > 0)
                        chosen.add(
                            new Trade.ReturnLine(
                                lines.get(i).id(), qty, String.valueOf(model.getValueAt(i, 4))));
                    }
                    check(!chosen.isEmpty(), I18n.t("select_return_items"));
                    Trade.ReturnRequest request =
                        new Trade.ReturnRequest(
                            originalId,
                            chosen,
                            f.text("refund_method"),
                            required(f.text("reason"), "Reason"),
                            f.date("date"),
                            Documents.key(),
                            approval[0],
                            voidSale);
                    if (exchange) {
                      d.dispose();
                      frame.showPage("pos");
                      if (frame.pos != null) frame.pos.exchange(request, detail);
                      return;
                    }
                    if (!Ui.confirm(d, voidSale ? "void_confirm" : "return_confirm")) return;
                    Ui.work(
                        d,
                        () -> a.returns.post(a.user, request),
                        result -> {
                          d.dispose();
                          receipt(result.id());
                        });
                  });
          Theme.primary(commit);
          container.add(
              Ui.bar(
                  Ui.button("cancel", d::dispose),
                  Ui.button(
                      "manager_approval",
                      () -> {
                        User approved = LoginDialog.show(d, a, Permission.REFUND_CASH);
                        if (approved != null) {
                          approval[0] = approved;
                          Ui.info(d, I18n.t("approved"));
                        }
                      }),
                  commit),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  public void receipt(long id) {
    Ui.work(
        frame,
        () -> {
          QueryService.Detail detail = a.queries.document(a.user, id);
          ReceiptPrinter printer =
              new ReceiptPrinter(
                  detail,
                  a.preferences.getOrDefault("receipt_width", "80"),
                  a.preferences.getOrDefault("receipt_language", I18n.language()));
          Path file =
              a.db
                  .home()
                  .resolve("receipts")
                  .resolve(
                      detail.header().text("number").replaceAll("[^A-Za-z0-9_-]", "_") + ".txt");
          printer.save(file);
          return Map.entry(detail, printer);
        },
        data -> {
          ReceiptPrinter printer = data.getValue();
          JPanel root = new JPanel(new BorderLayout());
          JTabbedPane tabs = new JTabbedPane();
          tabs.addTab(I18n.t("receipt"), printer.preview());
          JTable itemTable = Ui.table();
          Ui.rows(itemTable, data.getKey().lines());
          tabs.addTab(I18n.t("items"), new JScrollPane(itemTable));
          JTable linked = Ui.table();
          Ui.rows(linked, data.getKey().linked());
          tabs.addTab(I18n.t("linked_transactions"), new JScrollPane(linked));
          root.add(tabs);
          JDialog d = Ui.dialog(frame, "receipt", root, 850, 730);
          root.add(
              Ui.bar(
                  Ui.button(
                      "print_receipt",
                      () -> {
                        try {
                          printer.printReceipt(d);
                        } catch (Exception ex) {
                          Ui.info(d, I18n.t("print_failed_sale_saved"));
                        }
                      }),
                  Ui.button(
                      "save_text", () -> Ui.info(d, a.db.home().resolve("receipts").toString())),
                  Ui.button("close", d::dispose)),
              BorderLayout.SOUTH);
          if (Boolean.parseBoolean(a.preferences.getOrDefault("auto_print", "false")))
            SwingUtilities.invokeLater(
                () -> {
                  try {
                    printer.printReceipt(d);
                  } catch (Exception ex) {
                    Ui.info(d, I18n.t("print_failed_sale_saved"));
                  }
                });
          d.setVisible(true);
        });
  }

  public void openCash() {
    Form f = new Form().text("opening_cash", "0").text("reason", "");
    editor(
        "open_shift",
        f,
        () -> {
          BigDecimal amount = f.decimal("opening_cash");
          String reason = f.text("reason");
          return () -> a.cash.open(a.user, amount, reason);
        });
  }

  public void closeCash() {
    closeCash(null);
  }

  public void closeCash(Long id) {
    Ui.work(
        frame,
        () -> id == null ? a.cash.current(a.user) : a.cash.getSession(a.user, id),
        session -> {
          check(session != null, I18n.t("no_open_session"));
          Form f =
              new Form()
                  .text("opening_cash", session.money("opening_cash"))
                  .text("expected_cash", session.money("expected"))
                  .text("counted_cash", "")
                  .text("reason", "");
          f.field("opening_cash").setEnabled(false);
          f.field("expected_cash").setEnabled(false);
          editor(
              "close_shift",
              f,
              () -> {
                BigDecimal count = f.decimal("counted_cash");
                String reason = f.text("reason");
                check(Ui.confirm(frame, "close_shift_confirm"), I18n.t("cancelled"));
                return () -> {
                  a.cash.close(a.user, session.id(), count, reason);
                  return null;
                };
              });
        });
  }

  public void cashMove(boolean in) {
    Form f =
        new Form()
            .text("amount", "")
            .choices(
                "offset_account",
                new String[] {"bank", "capital", "drawings", "cash_difference"},
                in ? "capital" : "bank")
            .text("reason", "");
    editor(
        in ? "cash_in" : "cash_out",
        f,
        () -> {
          BigDecimal amount = f.decimal("amount");
          String offset = f.text("offset_account"), reason = f.text("reason");
          return () -> a.cash.moveCash(a.user, in, amount, offset, reason);
        });
  }

  private void cardSettlement() {
    Form f =
        new Form()
            .text("date", a.today())
            .text("gross_amount", "")
            .text("fee_net", "0")
            .text("fee_tax", "0")
            .text("reference", "");
    editor(
        "card_settlement",
        f,
        () -> {
          LocalDate date = f.date("date");
          BigDecimal gross = f.decimal("gross_amount"),
              fee = f.decimal("fee_net"),
              tax = f.decimal("fee_tax");
          String reference = f.text("reference"), key = Documents.key();
          return () -> a.cash.settleCard(a.user, date, gross, fee, tax, reference, key);
        });
  }

  private void revalueFx() {
    Ui.work(
        frame,
        () -> a.queries.choices(a.user, "currencies"),
        currencies -> {
          Form f =
              new Form()
                  .lookup("currency", currencies, null, false)
                  .text("date", a.today());
          editor(
              "revalue_fx",
              f,
              () -> {
                String currency = f.text("currency"), key = Documents.key();
                LocalDate date = f.date("date");
                return () -> a.currencies.revalueOpenBalances(a.user, currency, date, key);
              });
        });
  }

  private void orderStatus(DataPanel panel, String status) {
    long id = panel.selected().id();
    if (status.equals("CANCELLED") && !Ui.confirm(frame, "cancel_order_confirm")) return;
    Ui.work(
        frame,
        () -> {
          a.purchase.status(a.user, id, status);
          return null;
        },
        x -> frame.refresh());
  }

  public void counts() {
    Ui.work(
        frame,
        () ->
            a.queries.list(
                a.user,
                QueryService.Page.COUNTS,
                new QueryService.Filter("", a.today(), a.today(), 0)),
        rows -> {
          JTable table = Ui.table();
          Ui.rows(table, rows);
          JPanel root = new JPanel(new BorderLayout());
          root.add(new JScrollPane(table));
          JDialog d = Ui.dialog(frame, "stock_counts", root, 850, 550);
          root.add(
              Ui.bar(
                  Ui.button(
                      "new_count",
                      () ->
                          Ui.work(
                              d,
                              () -> a.inventory.startCount(a.user),
                              id -> {
                                d.dispose();
                                countEditor(id);
                              })),
                  Ui.button(
                      "open",
                      () -> {
                        long id = Ui.selected(table).id();
                        d.dispose();
                        countEditor(id);
                      }),
                  Ui.button("close", d::dispose)),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  private void countEditor(long id) {
    Ui.work(
        frame,
        () ->
            Map.entry(
                a.queries.choices(a.user, "products"), a.queries.child(a.user, "count_lines", id)),
        data -> {
          JTable table = Ui.table();
          Ui.rows(table, data.getValue());
          Form f =
              new Form().lookup("product", data.getKey(), null, false).text("counted_quantity", "");
          JPanel root = new JPanel(new BorderLayout());
          root.add(new JScrollPane(table));
          root.add(f, BorderLayout.NORTH);
          JDialog d = Ui.dialog(frame, "stock_count", root, 900, 630);
          root.add(
              Ui.bar(
                  Ui.button(
                      "add_update_count",
                      () -> {
                        long product = f.id("product");
                        BigDecimal qty = f.decimal("counted_quantity");
                        Ui.work(
                            d,
                            () -> {
                              a.inventory.count(a.user, id, product, qty);
                              return a.queries.child(a.user, "count_lines", id);
                            },
                            r -> Ui.rows(table, r));
                      }),
                  Ui.button(
                      "post_count",
                      () -> {
                        if (Ui.confirm(d, "post_count_confirm"))
                          Ui.work(
                              d,
                              () -> a.inventory.postCount(a.user, id),
                              doc -> {
                                d.dispose();
                                frame.refresh();
                              });
                      }),
                  Ui.button("close", d::dispose)),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  public void user(Row existing) {
    Ui.work(
        frame,
        () -> a.queries.choices(a.user, "roles"),
        roles -> {
          Row old = existing == null ? new Row() : existing;
          Form f =
              new Form()
                  .text("username", old.text("username"))
                  .text("full_name", old.text("full_name"))
                  .lookup("role", roles, old.get("role_id"), false)
                  .check("active", existing == null || old.flag("active"))
                  .password("new_password");
          editor(
              existing == null ? "add_user" : "edit_reset",
              f,
              () -> {
                String username = f.text("username"), name = f.text("full_name");
                long role = f.id("role");
                boolean active = f.flag("active");
                char[] pw = f.passwordValue("new_password");
                return () -> {
                  try {
                    return a.auth.saveUser(
                        a.user,
                        existing == null ? null : old.id(),
                        username,
                        name,
                        role,
                        active,
                        pw);
                  } finally {
                    Arrays.fill(pw, '\0');
                  }
                };
              });
        });
  }

  public void role() {
    Form f = new Form().text("name", "");
    editor(
        "new_role",
        f,
        () -> {
          String name = f.text("name");
          return () -> a.auth.role(a.user, name);
        });
  }

  public void permissions() {
    Ui.work(
        frame,
        () -> a.queries.choices(a.user, "roles"),
        roles -> {
          Form choose = new Form().lookup("role", roles, null, false);
          JPanel root = new JPanel(new BorderLayout());
          root.add(choose, BorderLayout.NORTH);
          JPanel checks = new JPanel(new GridLayout(0, 2, 10, 8));
          Map<Permission, JCheckBox> permissions = new LinkedHashMap<>();
          for (Permission permission : Permission.values()) {
            JCheckBox box = new JCheckBox(I18n.t(permission.name()));
            permissions.put(permission, box);
            checks.add(box);
          }
          root.add(new JScrollPane(checks));
          JDialog d = Ui.dialog(frame, "permissions", root, 850, 680);
          Runnable load =
              () -> {
                long role = choose.id("role");
                Ui.work(
                    d,
                    () -> a.queries.child(a.user, "permissions", role),
                    rows -> {
                      Set<String> selected = new HashSet<>();
                      rows.forEach(r -> selected.add(r.text("permission_code")));
                      permissions.forEach((p, b) -> b.setSelected(selected.contains(p.name())));
                    });
              };
          root.add(
              Ui.bar(
                  Ui.button("load_permissions", load),
                  Ui.button(
                      "save",
                      () -> {
                        long role = choose.id("role");
                        Set<Permission> selected = EnumSet.noneOf(Permission.class);
                        permissions.forEach(
                            (p, b) -> {
                              if (b.isSelected()) selected.add(p);
                            });
                        Ui.work(
                            d,
                            () -> {
                              a.auth.permissions(a.user, role, selected);
                              return null;
                            },
                            v -> {
                              Ui.info(d, I18n.t("permissions_next_login"));
                              d.dispose();
                            });
                      }),
                  Ui.button("close", d::dispose)),
              BorderLayout.SOUTH);
          load.run();
          d.setVisible(true);
        });
  }

  public void changePassword(boolean required) {
    Form f =
        new Form()
            .password("current_password")
            .password("new_password")
            .password("confirm_password");
    JPanel root = new JPanel(new BorderLayout());
    root.add(f);
    JDialog d = Ui.dialog(frame, "change_password", root, 550, 350);
    d.setDefaultCloseOperation(
        required ? WindowConstants.DO_NOTHING_ON_CLOSE : WindowConstants.DISPOSE_ON_CLOSE);
    root.add(
        Ui.bar(
            Ui.button(
                "save",
                () -> {
                  char[] old = f.passwordValue("current_password"),
                      pw = f.passwordValue("new_password"),
                      confirm = f.passwordValue("confirm_password");
                  check(Arrays.equals(pw, confirm), I18n.t("passwords_do_not_match"));
                  Ui.work(
                      d,
                      () -> {
                        try {
                          a.auth.changePassword(a.user, old, pw);
                          return null;
                        } finally {
                          Arrays.fill(old, '\0');
                          Arrays.fill(pw, '\0');
                          Arrays.fill(confirm, '\0');
                        }
                      },
                      x -> d.dispose());
                })),
        BorderLayout.SOUTH);
    if (required)
      d.getRootPane()
          .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
          .put(KeyStroke.getKeyStroke("ESCAPE"), "none");
    d.setVisible(true);
  }

  public void account() {
    Form f =
        new Form()
            .text("code", "")
            .text("name", "")
            .text("name_ar", "")
            .choices(
                "account_type",
                new String[] {"ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"},
                "EXPENSE");
    editor(
        "add_account",
        f,
        () -> {
          String code = f.text("code"),
              name = f.text("name"),
              ar = f.text("name_ar"),
              type = f.text("account_type");
          return () -> a.accounting.account(a.user, code, name, ar, type);
        });
  }

  public void journal() {
    Ui.work(
        frame,
        () -> a.queries.choices(a.user, "accounts"),
        accounts -> {
          Form head = new Form().text("date", a.today()).text("description", "");
          Form line =
              new Form()
                  .lookup("account", accounts, null, false)
                  .text("debit", "0")
                  .text("credit", "0");
          Posting posting = new Posting();
          JTable table = Ui.table();
          JLabel totals = new JLabel();
          JPanel root = new JPanel(new BorderLayout(10, 10));
          root.add(head, BorderLayout.NORTH);
          root.add(new JScrollPane(table));
          JPanel south = new JPanel(new BorderLayout());
          south.add(line);
          root.add(south, BorderLayout.SOUTH);
          JDialog d = Ui.dialog(frame, "manual_journal", root, 880, 710);
          JButton post =
              Ui.button(
                  "post",
                  () -> {
                    LocalDate date = head.date("date");
                    String desc = head.text("description");
                    Posting snapshot = new Posting();
                    snapshot.putAll(posting);
                    Ui.work(
                        d,
                        () -> a.accounting.manual(a.user, date, desc, snapshot),
                        id -> {
                          d.dispose();
                          frame.refresh();
                        });
                  });
          post.setEnabled(false);
          Runnable update =
              () -> {
                List<Row> rows = new ArrayList<>();
                BigDecimal debit = ZERO, credit = ZERO;
                for (var item : posting.entrySet()) {
                  Row r = new Row();
                  r.put("code", item.getKey());
                  r.put("debit", item.getValue().max(ZERO));
                  r.put("credit", item.getValue().negate().max(ZERO));
                  rows.add(r);
                  debit = debit.add(r.money("debit"));
                  credit = credit.add(r.money("credit"));
                }
                Ui.rows(table, rows);
                totals.setText(I18n.t("difference") + ": " + debit.subtract(credit));
                post.setEnabled(debit.compareTo(credit) == 0 && debit.signum() > 0);
              };
          south.add(
              Ui.bar(
                  Ui.button(
                      "add_line",
                      () -> {
                        long account = line.id("account");
                        String code =
                            accounts.stream()
                                .filter(r -> r.id() == account)
                                .findFirst()
                                .orElseThrow()
                                .text("code");
                        BigDecimal dr = line.decimal("debit"), cr = line.decimal("credit");
                        check(
                            dr.signum() >= 0
                                && cr.signum() >= 0
                                && (dr.signum() == 0 || cr.signum() == 0),
                            I18n.t("one_debit_or_credit"));
                        posting.add(code, dr.subtract(cr));
                        update.run();
                      }),
                  Ui.button(
                      "remove",
                      () -> {
                        posting.remove(Ui.selected(table).text("code"));
                        update.run();
                      }),
                  totals,
                  post,
                  Ui.button("cancel", d::dispose)),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  private void reverse(long id) {
    Form f = new Form().text("date", a.today()).text("reason", "");
    editor(
        "reverse_journal",
        f,
        () -> {
          LocalDate date = f.date("date");
          String reason = f.text("reason");
          return () -> a.accounting.reverse(a.user, id, date, reason);
        });
  }

  public void periods() {
    Ui.work(
        frame,
        () ->
            a.queries.list(
                a.user,
                QueryService.Page.PERIODS,
                new QueryService.Filter("", a.today(), a.today(), 0)),
        rows -> {
          JTable table = Ui.table();
          Ui.rows(table, rows);
          Form form =
              new Form()
                  .text("from", a.today().withDayOfMonth(1))
                  .text("to", a.today())
                  .text("reason", "");
          JPanel root = new JPanel(new BorderLayout());
          root.add(new JScrollPane(table));
          root.add(form, BorderLayout.NORTH);
          JDialog d = Ui.dialog(frame, "period_locks", root, 850, 600);
          root.add(
              Ui.bar(
                  Ui.button(
                      "close_period",
                      () -> {
                        LocalDate from = form.date("from"), to = form.date("to");
                        String reason = form.text("reason");
                        if (Ui.confirm(d, "close_period_confirm"))
                          Ui.work(
                              d,
                              () -> a.accounting.closePeriod(a.user, from, to, reason),
                              x -> d.dispose());
                      }),
                  Ui.button(
                      "reverse_close",
                      () -> {
                        long lockId = Ui.selected(table).id();
                        String reason = form.text("reason");
                        if (Ui.confirm(d, "reverse_close_confirm"))
                          Ui.work(
                              d,
                              () -> {
                                long closeId =
                                    a.db.read(
                                        c ->
                                            Sql.one(
                                                    c,
                                                    "SELECT id FROM period_closures WHERE lock_id=?"
                                                        + " AND status='POSTED'",
                                                    lockId)
                                                .id());
                                return a.accounting.reverseClose(a.user, closeId, a.today(), reason);
                              },
                              x -> d.dispose());
                      }),
                  Ui.button(
                      "lock_period",
                      () -> {
                        LocalDate from = form.date("from"), to = form.date("to");
                        String reason = form.text("reason");
                        if (Ui.confirm(d, "lock_period_confirm"))
                          Ui.work(
                              d,
                              () -> {
                                a.accounting.lock(a.user, from, to, reason);
                                return null;
                              },
                              x -> d.dispose());
                      }),
                  Ui.button(
                      "reopen",
                      () -> {
                        long id = Ui.selected(table).id();
                        String reason = form.text("reason");
                        if (Ui.confirm(d, "reopen_confirm"))
                          Ui.work(
                              d,
                              () -> {
                                a.accounting.reopen(a.user, id, reason);
                                return null;
                              },
                              x -> d.dispose());
                      }),
                  Ui.button("close", d::dispose)),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  public void currency() {
    Form f =
        new Form()
            .text("code", "")
            .text("name", "")
            .text("symbol", "")
            .text("decimals", "2")
            .check("symbol_before", true);
    editor(
        "currency",
        f,
        () -> {
          String code = f.text("code").toUpperCase(Locale.ROOT),
              name = f.text("name"),
              symbol = f.text("symbol");
          int scale = Integer.parseInt(f.text("decimals"));
          boolean before = f.flag("symbol_before");
          return () -> {
            a.currencies.save(a.user, code, name, symbol, scale, before);
            return null;
          };
        });
  }

  public void rate() {
    Ui.work(
        frame,
        () -> a.queries.choices(a.user, "currencies"),
        currencies -> {
          Form f =
              new Form()
                  .lookup("currency", currencies, null, false)
                  .text("effective_date", a.today())
                  .text("rate_to_base", "");
          JPanel wrapper = new JPanel(new BorderLayout());
          wrapper.add(f);
          wrapper.add(Ui.label("rate_direction_hint"), BorderLayout.SOUTH);
          editor(
              "exchange_rate",
              wrapper,
              () -> {
                String curr = f.text("currency");
                LocalDate date = f.date("effective_date");
                BigDecimal rate = f.decimal("rate_to_base");
                return () -> {
                  a.currencies.addRate(a.user, curr, date, rate);
                  return null;
                };
              });
        });
  }

  public void reference(String type) {
    Form f = new Form().text("name", "");
    if (!type.equals("tax_rates")) f.text("name_ar", "");
    if (type.equals("units")) f.check("fractional", false);
    if (type.equals("tax_rates")) f.text("rate", "0");
    editor(
        type,
        f,
        () -> {
          Map<String, Object> fields = Sql.values("name", f.text("name"));
          if (!type.equals("tax_rates")) fields.put("name_ar", f.text("name_ar"));
          if (type.equals("units")) fields.put("fractional", f.flag("fractional"));
          if (type.equals("tax_rates")) fields.put("rate", f.decimal("rate"));
          return () -> a.catalog.reference(a.user, type, fields);
        });
  }

  public void referencePage(QueryService.Page page) {
    DataPanel panel = new DataPanel(frame, page);
    JDialog d = Ui.dialog(frame, page.name(), panel, 1100, 650);
    panel.reload();
    d.setVisible(true);
  }

  public void store() {
    Ui.work(
        frame,
        () -> a.queries.record(a.user, "business_profile", 1),
        row -> {
          Form basic =
              new Form()
                  .text("store_name", row.text("store_name"))
                  .text("legal_name", row.text("legal_name"))
                  .text("address", row.text("address"))
                  .text("phone", row.text("phone"))
                  .text("email", row.text("email"))
                  .text("tax_number", row.text("tax_number"));
          Form more =
              new Form()
                  .text("country", row.text("country"))
                  .text("time_zone", row.text("time_zone"))
                  .text("footer", row.text("footer"))
                  .text("logo_path", row.text("logo_path"));
          editor(
              "business_information",
              tabs("basic", basic, "more_details", more),
              () -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.putAll(basic.strings());
                fields.putAll(more.strings());
                return () -> {
                  a.queries.business(a.user, fields);
                  return null;
                };
              });
        });
  }

  public void backup() {
    Ui.work(
        frame,
        () -> a.backup.create(a.user, false),
        path -> {
          Ui.info(frame, I18n.t("backup_created") + "\n" + path);
          if (!Ui.confirm(frame, "copy_backup_elsewhere")) return;
          JFileChooser chooser = new JFileChooser();
          chooser.setSelectedFile(path.getFileName().toFile());
          if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
          Path destination = chooser.getSelectedFile().toPath();
          if (Files.exists(destination) && !Ui.confirm(frame, "replace_file")) return;
          Ui.work(
              frame,
              () ->
                  Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING),
              copied -> Ui.info(frame, I18n.t("backup_created") + "\n" + copied));
        });
  }

  public void restore() {
    User verified = LoginDialog.show(frame, a, Permission.BACKUP_RESTORE);
    if (verified == null) return;
    JFileChooser picker = new JFileChooser(a.db.home().resolve("backups").toFile());
    picker.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("POS backup (*.zip)", "zip"));
    if (picker.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path file = picker.getSelectedFile().toPath();
    Ui.work(
        frame,
        () -> a.backup.validate(file),
        image -> {
          if (!Ui.confirm(frame, "restore_confirm")) {
            try {
              Files.deleteIfExists(image);
              Files.deleteIfExists(image.getParent());
            } catch (Exception ignored) {
            }
            return;
          }
          Ui.work(
              frame,
              () -> {
                Main.stopMaintenance();
                a.backup.restore(verified, image);
                return null;
              },
              v -> {
                Ui.info(frame, I18n.t("restore_restart"));
                frame.dispose();
                Main.shutdown(a, false);
              });
        });
  }

  private void importCsv() {
    JFileChooser picker = new JFileChooser(a.db.home().resolve("exports").toFile());
    if (picker.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path path = picker.getSelectedFile().toPath();
    Ui.work(
        frame,
        () -> a.importer.preview(a.user, path),
        preview -> {
          if (!preview.errors().isEmpty()) {
            Ui.info(frame, String.join("\n", preview.errors().stream().limit(30).toList()));
            return;
          }
          List<Row> rows = new ArrayList<>();
          for (Map<String, String> record : preview.records()) {
            Row row = new Row();
            row.putAll(record);
            rows.add(row);
          }
          JTable table = Ui.table();
          Ui.rows(table, rows);
          JPanel root = new JPanel(new BorderLayout());
          root.add(new JScrollPane(table));
          JDialog d = Ui.dialog(frame, "import_preview", root, 1000, 650);
          root.add(
              Ui.bar(
                  Ui.button("cancel", d::dispose),
                  Ui.button(
                      "import",
                      () -> {
                        if (Ui.confirm(d, "import_confirm"))
                          Ui.work(
                              d,
                              () -> a.importer.apply(a.user, preview),
                              count -> {
                                d.dispose();
                                frame.refresh();
                                Ui.info(frame, I18n.t("imported") + ": " + count);
                              });
                      })),
              BorderLayout.SOUTH);
          d.setVisible(true);
        });
  }

  @SuppressWarnings("unchecked")
  public void export(JTable table, String title) {
    List<Row> data = (List<Row>) table.getClientProperty("rows");
    List<String> columns = (List<String>) table.getClientProperty("columns");
    if (data == null || data.isEmpty()) {
      Ui.info(frame, I18n.t("no_data"));
      return;
    }
    List<Row> visible = new ArrayList<>();
    for (int i = 0; i < table.getRowCount(); i++)
      visible.add(data.get(table.convertRowIndexToModel(i)));
    JFileChooser picker = new JFileChooser(a.db.home().resolve("exports").toFile());
    picker.setSelectedFile(
        a.db
            .home()
            .resolve("exports")
            .resolve(title.toLowerCase(Locale.ROOT) + "-" + a.today() + ".csv")
            .toFile());
    if (picker.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path file = picker.getSelectedFile().toPath();
    if (Files.exists(file) && !Ui.confirm(frame, "replace_file")) return;
    Ui.work(
        frame,
        () -> {
          Csv.write(file, columns, visible);
          return file;
        },
        saved -> Ui.info(frame, I18n.t("export_saved") + "\n" + saved));
  }

  public void print(JTable table, String title) {
    try {
      String header = I18n.t(title) + " · " + a.user.name() + " · " + a.today();
      table.print(
          JTable.PrintMode.FIT_WIDTH,
          new java.text.MessageFormat(header.replace("'", "''")),
          new java.text.MessageFormat("{0}"));
    } catch (Exception ex) {
      Ui.error(frame, ex);
    }
  }

  public void showRows(String title, List<Row> rows) {
    JTable table = Ui.table();
    Ui.rows(table, rows);
    JPanel root = new JPanel(new BorderLayout());
    root.add(new JScrollPane(table));
    JDialog d = Ui.dialog(frame, title, root, 1020, 630);
    root.add(
        Ui.bar(
            Ui.button("export_csv", () -> export(table, title)),
            Ui.button("print", () -> print(table, title)),
            Ui.button("close", d::dispose)),
        BorderLayout.SOUTH);
    d.setVisible(true);
  }

  private void showChildren(String kind, long id, String title) {
    Ui.work(frame, () -> a.queries.child(a.user, kind, id), rows -> showRows(title, rows));
  }

  private void showSections(Map<String, List<Row>> sections) {
    JTabbedPane tabs = new JTabbedPane();
    List<JTable> tables = new ArrayList<>();
    for (var section : sections.entrySet()) {
      JTable table = Ui.table();
      Ui.rows(table, section.getValue());
      tables.add(table);
      tabs.addTab(I18n.t(section.getKey()), new JScrollPane(table));
    }
    JPanel root = new JPanel(new BorderLayout());
    root.add(tabs);
    JDialog d = Ui.dialog(frame, "details", root, 1080, 680);
    root.add(
        Ui.bar(
            Ui.button("export_csv", () -> export(tables.get(tabs.getSelectedIndex()), "details")),
            Ui.button("print", () -> print(tables.get(tabs.getSelectedIndex()), "details")),
            Ui.button("close", d::dispose)),
        BorderLayout.SOUTH);
    d.setVisible(true);
  }
}
