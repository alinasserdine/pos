package com.professionalpos.ui;

import static com.professionalpos.util.Money.*;

import com.professionalpos.App;
import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import com.professionalpos.model.*;
import com.professionalpos.security.Permission;
import com.professionalpos.service.*;
import java.awt.*;
import java.awt.event.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

public final class PosPanel extends JPanel {
  private static final class CartRow {
    long id;
    String name, unit = "";
    BigDecimal qty = BigDecimal.ONE, price, discount = ZERO;
    boolean percent;
    String note = "";
    Long poLine;

    CartRow(long id, String name, BigDecimal price) {
      this.id = id;
      this.name = name;
      this.price = price;
    }
  }

  private final MainFrame frame;
  private final App a;
  private final boolean purchase;
  private Window host;
  private final JTextField scan = new JTextField(24), reason = new JTextField(16);
  private final JComboBox<Form.Choice> customer = new JComboBox<>(),
      currency = new JComboBox<>(),
      category = new JComboBox<>();
  private final JPanel products = new JPanel(new GridLayout(0, 2, 10, 10));
  private final JTable table = Ui.table();
  private final List<CartRow> cart = new ArrayList<>();
  private final CartModel model = new CartModel();
  private final JLabel total = new JLabel(), status = new JLabel(), creditLabel = new JLabel();
  private BigDecimal discount = ZERO;
  private boolean discountPercent;
  private Calculator.Total quoted;
  private User approver;
  private Long heldId, orderId;
  private String note = "", requestKey = Documents.key(), lastCurrency;
  private boolean loading, initialized;
  private long quoteRevision;
  private final javax.swing.Timer quoteTimer;
  private Trade.ReturnRequest exchanging;
  private QueryService.Detail exchangeOriginal;
  private BigDecimal exchangeCredit = ZERO;

  public PosPanel(MainFrame frame, boolean purchase) {
    super(new BorderLayout(12, 12));
    this.frame = frame;
    this.a = frame.app;
    this.purchase = purchase;
    scan.setName("barcodeInput");
    customer.setName("customer");
    currency.setName("currency");
    table.setName("cartTable");
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    JLabel title = Ui.label(purchase ? "new_purchase" : "point_of_sale");
    title.setFont(new Font("SansSerif", Font.BOLD, 26));
    JPanel head = new JPanel(new BorderLayout());
    head.add(title, BorderLayout.NORTH);
    JPanel contactRow =
        Ui.bar(
            Ui.label(purchase ? "supplier" : "customer"),
            customer,
            Ui.label("currency"),
            currency,
            Ui.label("reason"),
            reason);
    if (a.user.can(purchase ? Permission.PURCHASE_CREATE : Permission.CUSTOMER_EDIT))
      contactRow.add(
          Ui.button(
              purchase ? "new_supplier" : "new_customer",
              () -> frame.actions.party(null, purchase ? "SUPPLIER" : "CUSTOMER")));
    head.add(contactRow, BorderLayout.CENTER);
    head.add(creditLabel, BorderLayout.SOUTH);
    add(head, BorderLayout.NORTH);
    JPanel catalog = new JPanel(new BorderLayout(10, 10));
    JPanel searchRow = new JPanel(new BorderLayout(8, 8));
    searchRow.add(scan);
    searchRow.add(Ui.button("search", () -> search(false)), BorderLayout.LINE_END);
    JPanel catalogFilters = new JPanel(new BorderLayout(8, 8));
    catalogFilters.add(searchRow, BorderLayout.NORTH);
    catalogFilters.add(category, BorderLayout.SOUTH);
    catalog.add(catalogFilters, BorderLayout.NORTH);
    products.setBackground(Theme.BG);
    JPanel grid = new JPanel(new BorderLayout());
    grid.add(products, BorderLayout.NORTH);
    catalog.add(new JScrollPane(grid));
    catalog.setMinimumSize(new Dimension(340, 300));
    JPanel checkout = new JPanel(new BorderLayout(8, 8));
    table.setModel(model);
    table.setAutoCreateRowSorter(false);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.getColumnModel().getColumn(0).setPreferredWidth(175);
    for (int i = 1; i < table.getColumnCount(); i++)
      table.getColumnModel().getColumn(i).setPreferredWidth(i == 8 ? 160 : 85);
    checkout.add(new JScrollPane(table));
    checkout.add(
        Ui.bar(
            Ui.button(
                "remove",
                () -> {
                  int row = table.getSelectedRow();
                  if (row >= 0) {
                    cart.remove(row);
                    model.fireTableDataChanged();
                    scheduleQuote();
                  }
                }),
            Ui.button("clear_cart", this::clear),
            Ui.button("order_discount", this::discount),
            Ui.button(
                "manager_approval",
                () -> {
                  User user = LoginDialog.show(this, a, Permission.SALE_LARGE_DISCOUNT);
                  if (user != null) {
                    approver = user;
                    scheduleQuote();
                  }
                })),
        BorderLayout.NORTH);
    total.setFont(new Font("SansSerif", Font.BOLD, 28));
    total.setForeground(Theme.ACCENT);
    JPanel footer = new JPanel(new BorderLayout());
    footer.add(total, BorderLayout.NORTH);
    footer.add(status, BorderLayout.CENTER);
    JButton pay = Ui.button(purchase ? "receive_purchase" : "pay", this::pay);
    Theme.primary(pay);
    JPanel controls = Ui.bar(pay);
    if (!purchase) {
      controls.add(Ui.button("hold_sale", this::hold));
      controls.add(Ui.button("held_sales", () -> frame.showPage("held")));
      controls.add(Ui.button("open_shift", frame.actions::openCash));
    } else controls.add(Ui.button("save_purchase_order", this::saveOrder));
    footer.add(controls, BorderLayout.SOUTH);
    checkout.add(footer, BorderLayout.SOUTH);
    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, catalog, checkout);
    split.setResizeWeight(.34);
    split.setDividerLocation(385);
    add(split);
    scan.addActionListener(e -> search(true));
    category.addActionListener(
        e -> {
          if (!loading) search(false);
        });
    customer.addActionListener(
        e -> {
          if (!loading) {
            scheduleQuote();
            customerBalance();
          }
        });
    currency.addActionListener(
        e -> {
          if (!loading) currencyChanged();
        });
    quoteTimer = new javax.swing.Timer(200, e -> quote());
    quoteTimer.setRepeats(false);
    total.setText(I18n.t("total") + ": 0 " + a.base());
    status.setText(" ");
    bind("F1", () -> scan.requestFocusInWindow());
    bind("control F", () -> scan.requestFocusInWindow());
    bind("F2", () -> customer.requestFocusInWindow());
    bind("F4", this::hold);
    bind("F6", this::discount);
    bind("F8", this::pay);
    bind("F10", this::pay);
    bind("ESCAPE", () -> scan.requestFocusInWindow());
  }

  public void setHost(Window window) {
    host = window;
  }

  public void refreshLanguage() {
    loading = true;
    if (customer.getItemCount() > 0) {
      int selected = customer.getSelectedIndex();
      customer.removeItemAt(0);
      customer.insertItemAt(
          new Form.Choice(null, I18n.t(purchase ? "choose_supplier" : "walk_in_customer")), 0);
      customer.setSelectedIndex(selected);
    }
    if (category.getItemCount() > 0) {
      int selected = category.getSelectedIndex();
      category.removeItemAt(0);
      category.insertItemAt(new Form.Choice(null, I18n.t("all_categories")), 0);
      category.setSelectedIndex(selected);
    }
    loading = false;
    I18n.refresh(this);
    scheduleQuote();
    if (initialized && isShowing()) Ui.whenIdle(this, () -> search(false));
  }

  private void bind(String key, Runnable task) {
    getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), key);
    getActionMap()
        .put(
            key,
            new AbstractAction() {
              public void actionPerformed(ActionEvent e) {
                try {
                  if (!Ui.busy(PosPanel.this)) task.run();
                } catch (Exception ex) {
                  Ui.error(PosPanel.this, ex);
                }
              }
            });
  }

  public boolean hasCart() {
    return !cart.isEmpty();
  }

  public void activate() {
    Long selectedParty = party();
    String selectedCurrency = initialized ? curr() : a.base();
    Ui.work(
        this,
        () -> {
          Map<String, List<Row>> r = new HashMap<>();
          for (String key :
              List.of("currencies", purchase ? "suppliers" : "customers", "categories"))
            r.put(key, a.queries.choices(a.user, key));
          return r;
        },
        refs -> {
          loading = true;
          customer.removeAllItems();
          customer.addItem(
              new Form.Choice(null, I18n.t(purchase ? "choose_supplier" : "walk_in_customer")));
          for (Row r : refs.get(purchase ? "suppliers" : "customers"))
            customer.addItem(new Form.Choice(r.id(), r.text("name")));
          select(customer, selectedParty);
          currency.removeAllItems();
          for (Row r : refs.get("currencies"))
            currency.addItem(new Form.Choice(r.text("code"), r.text("code")));
          select(currency, selectedCurrency);
          lastCurrency = curr();
          category.removeAllItems();
          category.addItem(new Form.Choice(null, I18n.t("all_categories")));
          for (Row r : refs.get("categories"))
            category.addItem(new Form.Choice(r.id(), r.text("name")));
          loading = false;
          initialized = true;
          scheduleQuote();
          search(false);
          scan.requestFocusInWindow();
        });
  }

  private static void select(JComboBox<Form.Choice> box, Object key) {
    for (int i = 0; i < box.getItemCount(); i++)
      if (Objects.equals(String.valueOf(box.getItemAt(i).key()), String.valueOf(key))) {
        box.setSelectedIndex(i);
        return;
      }
  }

  private String curr() {
    Form.Choice c = (Form.Choice) currency.getSelectedItem();
    return c == null ? a.base() : c.key().toString();
  }

  private Long party() {
    Form.Choice c = (Form.Choice) customer.getSelectedItem();
    return c == null || c.key() == null ? null : Long.valueOf(c.key().toString());
  }

  private void currencyChanged() {
    String next = curr();
    if (Objects.equals(next, lastCurrency)) return;
    if (hasCart() && !Ui.confirm(this, "currency_clear_cart")) {
      loading = true;
      select(currency, lastCurrency);
      loading = false;
      return;
    }
    reset();
    lastCurrency = next;
    search(false);
  }

  private void search(boolean exact) {
    String query = scan.getText().trim();
    Object selected =
        ((Form.Choice) category.getSelectedItem()) == null
            ? null
            : ((Form.Choice) category.getSelectedItem()).key();
    Ui.work(
        this,
        () -> {
          Row match = exact && !query.isEmpty() ? a.catalog.barcode(a.user, query) : null;
          return Map.entry(match == null ? new Row() : match, a.catalog.search(a.user, query, 0));
        },
        data -> {
          Row found = data.getKey();
          if (!found.isEmpty()) {
            add(found);
            scan.setText("");
            return;
          }
          products.removeAll();
          for (Row p : data.getValue()) {
            if (selected != null && p.number("category_id") != Long.parseLong(selected.toString()))
              continue;
            String name =
                I18n.arabic() && !p.text("name_ar").isBlank() ? p.text("name_ar") : p.text("name");
            JButton b =
                new JButton(
                    "<html><div style='padding:10px'><b>"
                        + html(name)
                        + "</b><br>"
                        + html(p.text("sku"))
                        + "<br>"
                        + I18n.t("stock")
                        + ": "
                        + p.money("on_hand").stripTrailingZeros().toPlainString()
                        + "</div></html>");
            b.setBackground(Color.WHITE);
            b.setPreferredSize(new Dimension(165, 100));
            b.setHorizontalAlignment(SwingConstants.LEADING);
            b.addActionListener(e -> add(p));
            products.add(b);
          }
          products.revalidate();
          products.repaint();
          if (exact && !query.isEmpty() && data.getValue().isEmpty()) {
            if (a.user.can(Permission.PRODUCT_CREATE) && Ui.confirm(this, "unknown_barcode_create"))
              frame.actions.product(null, query);
            else Ui.info(this, I18n.t("product_not_found"));
          }
          scan.requestFocusInWindow();
        });
  }

  private static String html(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private void add(Row p) {
    long id = p.id();
    String currency = curr();
    Ui.work(
        this,
        () -> a.queries.price(a.user, id, currency, purchase),
        price -> {
          CartRow existing =
              cart.stream()
                  .filter(
                      r ->
                          r.id == id
                              && r.price.compareTo(price) == 0
                              && r.discount.signum() == 0
                              && r.note.isBlank()
                              && r.poLine == null)
                  .findFirst()
                  .orElse(null);
          if (existing != null) existing.qty = existing.qty.add(BigDecimal.ONE);
          else {
            CartRow added =
                new CartRow(
                    id,
                    I18n.arabic() && !p.text("name_ar").isBlank()
                        ? p.text("name_ar")
                        : p.text("name"),
                    price);
            added.unit = p.text("unit_name");
            cart.add(added);
          }
          model.fireTableDataChanged();
          scheduleQuote();
          scan.requestFocusInWindow();
        });
  }

  private void scheduleQuote() {
    quoteRevision++;
    quoteTimer.restart();
  }

  private List<Trade.Line> lines() {
    return cart.stream()
        .map(r -> new Trade.Line(r.id, r.qty, r.price, r.discount, r.percent, r.note, r.poLine))
        .toList();
  }

  private Trade.Request request(
      List<Trade.Payment> pays, LocalDate date, LocalDate due, String reference) {
    return new Trade.Request(
        purchase ? "PURCHASE" : "SALE",
        party(),
        date,
        due,
        curr(),
        lines(),
        discount,
        discountPercent,
        pays,
        reference,
        note,
        requestKey,
        heldId,
        orderId,
        approver,
        reason.getText().trim());
  }

  private void quote() {
    if (cart.isEmpty()) {
      quoted = null;
      total.setText(I18n.t("total") + ": 0 " + curr());
      status.setText(" ");
      model.fireTableDataChanged();
      return;
    }
    long revision = quoteRevision;
    Trade.Request r = request(List.of(), a.today(), a.today().plusDays(30), "");
    new SwingWorker<Calculator.Total, Void>() {
      private String secondary = "";

      protected Calculator.Total doInBackground() {
        Calculator.Total calculated = a.trade.quote(a.user, r);
        String code = a.preferences.getOrDefault("secondary_currency", "");
        if (!code.isBlank() && !code.equals(r.currency()))
          secondary =
              "    ≈ "
                  + a.currencies
                      .convert(a.user, calculated.total(), r.currency(), code, r.date())
                      .toPlainString()
                  + " "
                  + code;
        return calculated;
      }

      protected void done() {
        if (revision != quoteRevision) return;
        try {
          quoted = get();
          total.setText(
              I18n.t("total") + ": " + quoted.total().toPlainString() + " " + r.currency());
          status.setText(
              I18n.t("discount")
                  + ": "
                  + quoted.discount()
                  + "    "
                  + I18n.t("tax")
                  + ": "
                  + quoted.tax()
                  + secondary
                  + (exchanging == null
                      ? ""
                      : "    " + I18n.t("exchange_credit") + ": " + exchangeCredit));
          model.fireTableDataChanged();
        } catch (Exception ex) {
          quoted = null;
          status.setText(I18n.error(ex.getCause() == null ? ex : ex.getCause()));
        }
      }
    }.execute();
  }

  private void customerBalance() {
    Long id = party();
    if (id == null) {
      creditLabel.setText(" ");
      return;
    }
    Ui.work(
        this,
        () -> a.queries.child(a.user, "party_statement", id),
        rows -> {
          BigDecimal balance =
              rows.stream().map(r -> r.money("open_base")).reduce(ZERO, BigDecimal::add);
          creditLabel.setText(I18n.t("account_balance") + ": " + balance + " " + a.base());
        });
  }

  private void discount() {
    Form f =
        new Form()
            .text("discount", discount)
            .check("percentage", discountPercent)
            .text("reason", reason.getText());
    JPanel root = new JPanel(new BorderLayout());
    root.add(f);
    JDialog d = Ui.dialog(this, "order_discount", root, 520, 380);
    root.add(
        Ui.bar(
            Ui.button(
                "apply",
                () -> {
                  discount = f.decimal("discount");
                  discountPercent = f.flag("percentage");
                  reason.setText(f.text("reason"));
                  d.dispose();
                  scheduleQuote();
                }),
            Ui.button("cancel", d::dispose)),
        BorderLayout.SOUTH);
    d.setVisible(true);
  }

  private void clear() {
    if (cart.isEmpty() || Ui.confirm(this, "discard_cart")) reset();
  }

  private void reset() {
    cart.clear();
    discount = ZERO;
    discountPercent = false;
    heldId = null;
    orderId = null;
    note = "";
    approver = null;
    exchanging = null;
    exchangeOriginal = null;
    exchangeCredit = ZERO;
    requestKey = Documents.key();
    reason.setText("");
    model.fireTableDataChanged();
    scheduleQuote();
  }

  private void hold() {
    if (purchase || cart.isEmpty()) return;
    if (table.isEditing()) table.getCellEditor().stopCellEditing();
    String entered = JOptionPane.showInputDialog(this, I18n.t("hold_note"), note);
    if (entered == null) return;
    note = entered;
    Trade.Request request = request(List.of(), a.today(), a.today().plusDays(30), "");
    Ui.work(
        this,
        () -> a.trade.hold(a.user, request),
        id -> {
          reset();
          Ui.info(this, I18n.t("sale_held"));
        });
  }

  public void loadHeld(long id) {
    Ui.whenIdle(
        this,
        () -> {
          if (hasCart() && !Ui.confirm(this, "discard_cart")) return;
          Ui.work(
              this,
              () ->
                  Map.entry(
                      a.queries.record(a.user, "held_sales", id),
                      a.queries.child(a.user, "held_lines", id)),
              data -> {
                reset();
                loading = true;
                Row h = data.getKey();
                heldId = id;
                select(customer, h.get("party_id"));
                select(currency, h.get("currency_code"));
                lastCurrency = curr();
                discount = h.money("discount_value");
                discountPercent = h.flag("discount_percent");
                note = h.text("note");
                for (Row l : data.getValue()) {
                  CartRow row =
                      new CartRow(l.number("product_id"), l.text("name"), l.money("price"));
                  row.unit = l.text("unit_name");
                  row.qty = l.money("quantity");
                  row.discount = l.money("discount_value");
                  row.percent = l.flag("discount_percent");
                  row.note = l.text("note");
                  cart.add(row);
                }
                loading = false;
                model.fireTableDataChanged();
                scheduleQuote();
              });
        });
  }

  public void loadOrder(long id) {
    Ui.whenIdle(
        this,
        () ->
            Ui.work(
                this,
                () ->
                    Map.entry(
                        a.queries.record(a.user, "purchase_orders", id),
                        a.queries.child(a.user, "po_lines", id)),
                data -> {
                  reset();
                  loading = true;
                  Row h = data.getKey();
                  orderId = id;
                  select(customer, h.get("supplier_id"));
                  select(currency, h.get("currency_code"));
                  lastCurrency = curr();
                  note = h.text("notes");
                  for (Row l : data.getValue()) {
                    BigDecimal remaining = l.money("quantity").subtract(l.money("received_qty"));
                    if (remaining.signum() <= 0) continue;
                    CartRow row =
                        new CartRow(l.number("product_id"), l.text("name"), l.money("unit_cost"));
                    row.unit = l.text("unit_name");
                    row.qty = remaining;
                    row.discount = l.money("discount_pct");
                    row.percent = true;
                    row.poLine = l.id();
                    cart.add(row);
                  }
                  loading = false;
                  model.fireTableDataChanged();
                  scheduleQuote();
                }));
  }

  public void exchange(Trade.ReturnRequest request, QueryService.Detail original) {
    Ui.whenIdle(
        this,
        () -> {
          if (hasCart() && !Ui.confirm(this, "discard_cart")) return;
          reset();
          exchanging = request;
          exchangeOriginal = original;
          loading = true;
          select(currency, original.header().get("currency_code"));
          select(customer, original.header().get("party_id"));
          loading = false;
          lastCurrency = curr();
          Ui.work(
              this,
              () -> a.returns.quote(a.user, request),
              value -> {
                exchangeCredit = value;
                creditLabel.setText(
                    I18n.t("exchange_credit") + ": " + exchangeCredit + " " + curr());
              });
          creditLabel.setText(I18n.t("exchange_credit") + ": " + exchangeCredit + " " + curr());
          Ui.info(this, I18n.t("scan_replacement_items"));
        });
  }

  private void pay() {
    if (table.isEditing()) table.getCellEditor().stopCellEditing();
    check(!cart.isEmpty(), I18n.t("add_items_first"));
    Trade.Request quoteRequest = request(List.of(), a.today(), a.today().plusDays(30), "");
    Ui.work(
        this,
        () -> a.trade.quote(a.user, quoteRequest),
        calculation -> {
          quoted = calculation;
          BigDecimal payable =
              exchanging == null
                  ? calculation.total()
                  : calculation.total().subtract(exchangeCredit).max(ZERO);
          Form f =
              new Form()
                  .text("date", a.today())
                  .text("due_date", a.today().plusDays(30))
                  .text("reference", "")
                  .text("cash", payable)
                  .text("cash_received", payable)
                  .text("card", "0")
                  .text("bank", "0")
                  .text("other", "0");
          JLabel summary = new JLabel();
          summary.setFont(new Font("SansSerif", Font.BOLD, 19));
          JPanel root = new JPanel(new BorderLayout(10, 10));
          root.add(summary, BorderLayout.NORTH);
          root.add(new JScrollPane(f));
          JPanel quick = Ui.bar();
          quick.add(
              Ui.button(
                  "exact",
                  () -> {
                    f.set("cash", payable);
                    f.set("cash_received", payable);
                  }));
          for (String value : List.of("10", "20", "50", "100"))
            quick.add(Ui.button(value, () -> f.set("cash_received", value)));
          JPanel footer = new JPanel(new BorderLayout());
          footer.add(quick, BorderLayout.NORTH);
          root.add(footer, BorderLayout.SOUTH);
          JDialog d = Ui.dialog(this, "payment", root, 640, 670);
          Runnable update =
              () -> {
                try {
                  BigDecimal paid =
                      f.decimal("cash")
                          .add(f.decimal("card"))
                          .add(f.decimal("bank"))
                          .add(f.decimal("other"));
                  summary.setText(
                      I18n.t("total")
                          + ": "
                          + payable
                          + " "
                          + curr()
                          + "    "
                          + I18n.t("on_account")
                          + ": "
                          + payable.subtract(paid)
                          + "    "
                          + I18n.t("change")
                          + ": "
                          + f.decimal("cash_received").subtract(f.decimal("cash")));
                } catch (Exception ignored) {
                }
              };
          for (String k : List.of("cash", "cash_received", "card", "bank", "other"))
            ((JTextField) f.field(k))
                .getDocument()
                .addDocumentListener(
                    new javax.swing.event.DocumentListener() {
                      public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        update.run();
                      }

                      public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        update.run();
                      }

                      public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        update.run();
                      }
                    });
          update.run();
          JButton complete =
              Ui.button(
                  "complete",
                  () -> {
                    List<Trade.Payment> payments = new ArrayList<>();
                    for (String method : List.of("cash", "card", "bank", "other")) {
                      BigDecimal amount = f.decimal(method);
                      nonnegative(amount, "Payment");
                      if (amount.signum() > 0)
                        payments.add(
                            new Trade.Payment(
                                method.toUpperCase(Locale.ROOT),
                                amount,
                                method.equals("cash") ? f.decimal("cash_received") : amount,
                                f.text("reference")));
                    }
                    Trade.Request transaction =
                        request(
                            payments,
                            f.date("date"),
                            f.optionalDate("due_date"),
                            f.text("reference"));
                    Trade.ReturnRequest exchangeRequest = exchanging;
                    Ui.work(
                        d,
                        () ->
                            exchangeRequest == null
                                ? a.trade.complete(a.user, transaction)
                                : a.returns.exchange(a.user, exchangeRequest, transaction, "CASH"),
                        result -> {
                          d.dispose();
                          reset();
                          if (host != null) host.dispose();
                          Ui.info(
                              frame,
                              I18n.t("sale_completed")
                                  + "\n"
                                  + result.number()
                                  + "\n"
                                  + I18n.t("change")
                                  + ": "
                                  + result.change());
                          frame.actions.receipt(result.id());
                        });
                  });
          Theme.primary(complete);
          footer.add(Ui.bar(Ui.button("cancel", d::dispose), complete), BorderLayout.SOUTH);
          d.getRootPane().setDefaultButton(complete);
          d.getRootPane()
              .registerKeyboardAction(
                  e -> complete.doClick(),
                  KeyStroke.getKeyStroke("F10"),
                  JComponent.WHEN_IN_FOCUSED_WINDOW);
          d.setVisible(true);
        });
  }

  private void saveOrder() {
    check(purchase && !cart.isEmpty(), I18n.t("add_items_first"));
    check(party() != null, I18n.t("choose_supplier"));
    Form f =
        new Form()
            .text("date", a.today())
            .text("expected_date", a.today().plusDays(7))
            .text("notes", note);
    JPanel root = new JPanel(new BorderLayout());
    root.add(f);
    JDialog d = Ui.dialog(this, "save_purchase_order", root, 550, 360);
    root.add(
        Ui.bar(
            Ui.button(
                "save_draft",
                () -> {
                  if (orderId != null) {
                    Ui.info(d, I18n.t("order_receipt_only"));
                    return;
                  }
                  long supplier = party();
                  String currency = curr();
                  LocalDate date = f.date("date"), expected = f.optionalDate("expected_date");
                  String notes = f.text("notes");
                  List<PurchaseService.OrderLine> lines =
                      cart.stream()
                          .map(
                              r ->
                                  new PurchaseService.OrderLine(
                                      r.id,
                                      r.qty,
                                      r.price,
                                      r.percent
                                          ? r.discount
                                          : r.price.multiply(r.qty).signum() == 0
                                              ? ZERO
                                              : divide(
                                                  r.discount.multiply(HUNDRED),
                                                  r.price.multiply(r.qty)),
                                      null))
                          .toList();
                  Ui.work(
                      d,
                      () ->
                          a.purchase.order(
                              a.user, null, supplier, date, expected, currency, notes, lines),
                      id -> {
                        d.dispose();
                        reset();
                        if (host != null) host.dispose();
                        Ui.info(frame, I18n.t("order_saved"));
                      });
                }),
            Ui.button("cancel", d::dispose)),
        BorderLayout.SOUTH);
    d.setVisible(true);
  }

  private final class CartModel extends AbstractTableModel {
    private final String[] columns = {
      "product", "quantity", "price", "discount", "percent", "tax", "line_total", "unit", "note"
    };

    public int getRowCount() {
      return cart.size();
    }

    public int getColumnCount() {
      return columns.length;
    }

    public String getColumnName(int col) {
      return I18n.t(columns[col]);
    }

    public Class<?> getColumnClass(int col) {
      return col == 4 ? Boolean.class : Object.class;
    }

    public boolean isCellEditable(int row, int col) {
      return col == 1
          || col == 3
          || col == 4
          || col == 8
          || col == 2 && (purchase || a.user.can(Permission.PRICE_OVERRIDE));
    }

    public Object getValueAt(int index, int col) {
      CartRow row = cart.get(index);
      return switch (col) {
        case 0 -> row.name;
        case 1 -> row.qty;
        case 2 -> row.price;
        case 3 -> row.discount;
        case 4 -> row.percent;
        case 5 ->
            quoted != null && quoted.lines().size() == cart.size()
                ? quoted.lines().get(index).tax()
                : "";
        case 6 ->
            quoted != null && quoted.lines().size() == cart.size()
                ? quoted.lines().get(index).total()
                : row.qty.multiply(row.price);
        case 7 -> row.unit;
        case 8 -> row.note;
        default -> "";
      };
    }

    public void setValueAt(Object value, int index, int col) {
      CartRow row = cart.get(index);
      try {
        switch (col) {
          case 1 -> row.qty = positive(of(value.toString()), "Quantity");
          case 2 -> {
            if (!purchase && reason.getText().isBlank()) {
              String why =
                  JOptionPane.showInputDialog(PosPanel.this, I18n.t("price_override_reason"));
              if (why == null || why.isBlank()) return;
              reason.setText(why);
            }
            row.price = nonnegative(of(value.toString()), "Price");
          }
          case 3 -> row.discount = nonnegative(of(value.toString()), "Discount");
          case 4 -> row.percent = Boolean.TRUE.equals(value);
          case 8 -> row.note = value.toString();
        }
        fireTableRowsUpdated(index, index);
        scheduleQuote();
      } catch (Exception ex) {
        Ui.error(PosPanel.this, ex);
      }
    }
  }
}
