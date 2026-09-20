package com.professionalpos.service;

import static com.professionalpos.util.Money.*;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import java.time.*;
import java.util.*;

public final class QueryService {
  public enum Page {
    PRODUCTS,
    INVENTORY,
    CUSTOMERS,
    SUPPLIERS,
    SALES,
    PURCHASES,
    ORDERS,
    EXPENSES,
    DEBTS,
    HELD,
    CASH,
    USERS,
    ACCOUNTS,
    JOURNALS,
    AUDIT,
    NOTIFICATIONS,
    COUNTS,
    PERIODS,
    RATES,
    CURRENCIES,
    TAXES,
    CATEGORIES,
    UNITS
  }

  public record Filter(
      String search, LocalDate from, LocalDate to, int offset, Map<String, String> advanced) {
    public Filter {
      advanced = Map.copyOf(advanced);
    }

    public Filter(String s, LocalDate f, LocalDate t, int offset) {
      this(s, f, t, offset, Map.of());
    }
  }

  public record Detail(Row header, List<Row> lines, List<Row> payments, List<Row> linked) {}

  private final Database db;

  public QueryService(Database db) {
    this.db = db;
  }

  public static Permission permission(Page p) {
    return switch (p) {
      case PRODUCTS -> Permission.PRODUCT_VIEW;
      case INVENTORY, COUNTS -> Permission.INVENTORY_VIEW;
      case CUSTOMERS -> Permission.CUSTOMER_VIEW;
      case SUPPLIERS -> Permission.SUPPLIER_VIEW;
      case PURCHASES, ORDERS -> Permission.PURCHASE_VIEW;
      case SALES, HELD -> Permission.POS_ACCESS;
      case EXPENSES -> Permission.EXPENSE_CREATE;
      case DEBTS -> Permission.CUSTOMER_VIEW;
      case CASH -> Permission.CASH_SESSION;
      case USERS -> Permission.SETTINGS_USERS;
      case ACCOUNTS, JOURNALS, PERIODS -> Permission.ACCOUNTING_VIEW;
      case AUDIT -> Permission.AUDIT_VIEW;
      case CURRENCIES, RATES -> Permission.SETTINGS_CURRENCY;
      case TAXES -> Permission.SETTINGS_TAX;
      case CATEGORIES, UNITS -> Permission.PRODUCT_EDIT;
      case NOTIFICATIONS -> Permission.REPORT_SALES;
    };
  }

  public List<Row> list(User u, Page page, Filter f) {
    u.require(permission(page));
    return db.read(
        c -> {
          List<Object> args = new ArrayList<>();
          String sql, like = CatalogService.like(f.search());
          switch (page) {
            case PRODUCTS, INVENTORY -> {
              sql =
                  "SELECT p.id,p.sku,p.name,p.name_ar,ca.name AS category,un.name AS"
                      + " unit,p.price,p.current_cost,p.on_hand,p.stock_value,p.reorder_level,p.active,(SELECT"
                      + " b.barcode FROM product_barcodes b WHERE b.product_id=p.id AND"
                      + " b.is_primary=TRUE FETCH FIRST 1 ROW ONLY) AS barcode FROM products p JOIN"
                      + " units un ON un.id=p.unit_id LEFT JOIN categories ca ON"
                      + " ca.id=p.category_id WHERE (LOWER(p.name) LIKE ? OR LOWER(p.name_ar) LIKE"
                      + " ? OR LOWER(p.sku) LIKE ? OR EXISTS(SELECT 1 FROM product_barcodes b WHERE"
                      + " b.product_id=p.id AND b.barcode LIKE ?))";
              Collections.addAll(args, like, like, like, "%" + f.search() + "%");
              if (page == Page.INVENTORY) sql += " AND p.track_stock=TRUE";
              sql += " ORDER BY p.name";
            }
            case CUSTOMERS, SUPPLIERS -> {
              sql =
                  "SELECT"
                      + " p.id,p.code,p.name,p.phone,p.currency_code,p.credit_limit,p.terms_days,p.active,COALESCE((SELECT"
                      + " SUM(e.open_base) FROM party_entries e WHERE e.party_id=p.id),0) AS"
                      + " balance FROM parties p WHERE p.kind=? AND (LOWER(p.name) LIKE ? OR"
                      + " LOWER(p.code) LIKE ? OR p.phone LIKE ?) ORDER BY p.name";
              Collections.addAll(
                  args, page == Page.CUSTOMERS ? "CUSTOMER" : "SUPPLIER", like, like, like);
            }
            case SALES, PURCHASES, EXPENSES -> {
              sql =
                  "SELECT d.id,d.number,d.document_date,d.created_at,d.party_name,u.full_name AS"
                      + " cashier,d.subtotal,d.discount,d.net,d.tax,d.total,d.currency_code,d.base_total,d.status,d.reference,COALESCE((SELECT"
                      + " SUM(e.open_amount) FROM party_entries e WHERE e.document_id=d.id),0) AS"
                      + " unpaid FROM documents d JOIN users u ON u.id=d.created_by WHERE d.kind=?"
                      + " AND d.document_date BETWEEN ? AND ? AND (LOWER(d.number) LIKE ? OR"
                      + " LOWER(COALESCE(d.party_name,'')) LIKE ? OR"
                      + " LOWER(COALESCE(d.reference,'')) LIKE ?)";
              Collections.addAll(
                  args,
                  page == Page.SALES ? "SALE" : page == Page.PURCHASES ? "PURCHASE" : "EXPENSE",
                  f.from(),
                  f.to(),
                  like,
                  like,
                  like);
              if (page == Page.SALES && !u.can(Permission.REPORT_SALES)) {
                sql += " AND d.created_by=?";
                args.add(u.id());
              }
              Map<String, String> columns =
                  Map.of(
                      "cashier",
                      "d.created_by",
                      "customer",
                      "d.party_id",
                      "status",
                      "d.status",
                      "currency",
                      "d.currency_code",
                      "min_amount",
                      "d.total",
                      "max_amount",
                      "d.total");
              for (var e : f.advanced().entrySet()) {
                if (e.getValue().isBlank()) continue;
                if (columns.containsKey(e.getKey())) {
                  sql +=
                      " AND "
                          + columns.get(e.getKey())
                          + (e.getKey().equals("min_amount")
                              ? ">="
                              : e.getKey().equals("max_amount") ? "<=" : "=")
                          + "?";
                  args.add(e.getKey().endsWith("amount") ? of(e.getValue()) : e.getValue());
                } else if (e.getKey().equals("payment")) {
                  sql +=
                      " AND EXISTS(SELECT 1 FROM payments p WHERE p.document_id=d.id AND"
                          + " p.method_code=?)";
                  args.add(e.getValue());
                } else if (Set.of("product", "barcode", "category").contains(e.getKey())) {
                  String column =
                      e.getKey().equals("product")
                          ? "product_name"
                          : e.getKey().equals("barcode") ? "barcode" : "category_name";
                  sql +=
                      " AND EXISTS(SELECT 1 FROM document_lines dl WHERE dl.document_id=d.id AND"
                          + " LOWER(dl."
                          + column
                          + ") LIKE ?)";
                  args.add(CatalogService.like(e.getValue()));
                }
              }
              sql += " ORDER BY d.id DESC";
            }
            case ORDERS -> {
              sql =
                  "SELECT o.id,o.number,p.name AS"
                      + " supplier,o.ordered_on,o.expected_on,o.currency_code,o.status,o.notes FROM"
                      + " purchase_orders o JOIN parties p ON p.id=o.supplier_id WHERE"
                      + " LOWER(o.number) LIKE ? OR LOWER(p.name) LIKE ? ORDER BY o.id DESC";
              Collections.addAll(args, like, like);
            }
            case DEBTS -> {
              sql =
                  "SELECT e.id,p.id AS party_id,p.kind,p.name,d.number,d.id AS"
                      + " document_id,d.document_date,e.currency_code,e.amount,e.open_amount,e.open_base,e.due_date,e.reminder_date,CASE"
                      + " WHEN e.open_amount=0 THEN 'PAID' WHEN e.open_amount<0 THEN 'CREDIT' WHEN"
                      + " e.due_date<CURRENT_DATE THEN 'OVERDUE' WHEN e.open_amount<e.amount THEN"
                      + " 'PARTIALLY_PAID' ELSE 'UNPAID' END AS payment_status FROM party_entries e"
                      + " JOIN parties p ON p.id=e.party_id JOIN documents d ON d.id=e.document_id"
                      + " WHERE e.open_amount<>0 AND (LOWER(p.name) LIKE ? OR LOWER(d.number) LIKE"
                      + " ?)";
              Collections.addAll(args, like, like);
              if (!u.can(Permission.PURCHASE_VIEW)) sql += " AND p.kind='CUSTOMER'";
              sql += " ORDER BY e.due_date,e.id";
            }
            case HELD -> {
              sql =
                  "SELECT h.id,h.number,p.name AS"
                      + " customer,u.full_name,h.currency_code,h.created_at,h.note FROM held_sales"
                      + " h LEFT JOIN parties p ON p.id=h.party_id JOIN users u ON u.id=h.user_id"
                      + " WHERE LOWER(h.number) LIKE ?";
              args.add(like);
              if (!u.can(Permission.SETTINGS_USERS)) {
                sql += " AND h.user_id=?";
                args.add(u.id());
              }
              sql += " ORDER BY h.id DESC";
            }
            case CASH -> {
              sql =
                  "SELECT"
                      + " s.id,u.full_name,s.opened_at,s.closed_at,s.opening_cash,s.expected_cash,s.counted_cash,s.difference,s.notes"
                      + " FROM cash_sessions s JOIN users u ON u.id=s.user_id WHERE 1=1";
              if (!u.can(Permission.REPORT_SALES)) {
                sql += " AND s.user_id=?";
                args.add(u.id());
              }
              sql += " ORDER BY s.id DESC";
            }
            case USERS -> {
              sql =
                  "SELECT u.id,u.username,u.full_name,r.name AS"
                      + " role,u.role_id,u.active,u.last_login,u.must_change FROM users u JOIN"
                      + " roles r ON r.id=u.role_id ORDER BY u.username";
            }
            case ACCOUNTS ->
                sql =
                    "SELECT id,code,name,name_ar,account_type,system_account,active FROM accounts"
                        + " ORDER BY code";
            case JOURNALS -> {
              sql =
                  "SELECT j.id,j.number,j.journal_date,d.number AS"
                      + " source,j.description,j.reversal_of FROM journal_entries j JOIN documents"
                      + " d ON d.id=j.document_id WHERE j.journal_date BETWEEN ? AND ? AND"
                      + " (LOWER(j.number) LIKE ? OR LOWER(j.description) LIKE ?) ORDER BY j.id"
                      + " DESC";
              Collections.addAll(args, f.from(), f.to(), like, like);
            }
            case AUDIT -> {
              sql =
                  "SELECT"
                      + " a.id,a.created_at,u.username,a.action,a.entity_type,a.entity_id,a.old_summary,a.new_summary,a.reason,a.approved_by"
                      + " FROM audit_logs a LEFT JOIN users u ON u.id=a.user_id WHERE"
                      + " CAST(a.created_at AS DATE) BETWEEN ? AND ? AND (LOWER(a.action) LIKE ? OR"
                      + " LOWER(COALESCE(a.reason,'')) LIKE ?) ORDER BY a.id DESC";
              Collections.addAll(args, f.from(), f.to(), like, like);
            }
            case NOTIFICATIONS -> {
              sql =
                  "SELECT n.id,n.kind,n.entity_id,n.message,n.created_at,n.resolved,CASE WHEN"
                      + " r.user_id IS NULL THEN FALSE ELSE TRUE END AS is_read FROM notifications"
                      + " n LEFT JOIN notification_reads r ON r.notification_id=n.id AND"
                      + " r.user_id=? ORDER BY n.resolved,n.id DESC";
              args.add(u.id());
            }
            case COUNTS ->
                sql =
                    "SELECT id,number,status,created_at,posted_document_id FROM stock_counts ORDER"
                        + " BY id DESC";
            case PERIODS ->
                sql =
                    "SELECT id,start_date,end_date,reason,active FROM period_locks ORDER BY id"
                        + " DESC";
            case RATES ->
                sql =
                    "SELECT r.id,r.currency_code,r.effective_date,r.rate_to_base,u.full_name FROM"
                        + " exchange_rates r JOIN users u ON u.id=r.created_by ORDER BY"
                        + " r.effective_date DESC";
            case CURRENCIES -> sql = "SELECT * FROM currencies ORDER BY code";
            case TAXES -> sql = "SELECT * FROM tax_rates ORDER BY name";
            case CATEGORIES -> sql = "SELECT * FROM categories ORDER BY name";
            case UNITS -> sql = "SELECT * FROM units ORDER BY name";
            default -> throw new IllegalArgumentException("Unknown page.");
          }
          args.add(Math.max(0, f.offset()));
          sql += " OFFSET ? ROWS FETCH NEXT 100 ROWS ONLY";
          List<Row> rows = Sql.rows(c, sql, args.toArray());
          if (!u.can(Permission.PRODUCT_COST_VIEW))
            for (Row row : rows) {
              row.remove("current_cost");
              row.remove("stock_value");
            }
          return rows;
        });
  }

  public List<Row> choices(User u, String type) {
    return db.read(
        c -> {
          String sql =
              switch (type) {
                case "products" -> {
                  u.require(Permission.POS_ACCESS);
                  yield "SELECT id,name,sku,price FROM products WHERE active=TRUE ORDER BY name";
                }
                case "customers" -> {
                  u.require(Permission.CUSTOMER_VIEW);
                  yield "SELECT id,name,code,credit_limit FROM parties WHERE kind='CUSTOMER' AND"
                      + " active=TRUE ORDER BY name";
                }
                case "suppliers" -> {
                  u.require(Permission.SUPPLIER_VIEW);
                  yield "SELECT id,name FROM parties WHERE kind='SUPPLIER' AND active=TRUE ORDER BY"
                      + " name";
                }
                case "categories" ->
                    "SELECT id,name FROM categories WHERE active=TRUE ORDER BY name";
                case "units" -> "SELECT id,name FROM units WHERE active=TRUE ORDER BY name";
                case "tax_rates" ->
                    "SELECT id,name,rate FROM tax_rates WHERE active=TRUE ORDER BY name";
                case "currencies" ->
                    "SELECT code,name,decimal_places,symbol,symbol_before FROM currencies WHERE"
                        + " active=TRUE ORDER BY code";
                case "payment_methods" ->
                    "SELECT code,name FROM payment_methods WHERE active=TRUE AND code<>'EXCHANGE'"
                        + " ORDER BY code";
                case "roles" -> {
                  u.require(Permission.SETTINGS_USERS);
                  yield "SELECT id,name,role_code FROM roles ORDER BY id";
                }
                case "accounts" -> {
                  u.require(Permission.ACCOUNTING_VIEW);
                  yield "SELECT id,code,name,account_type FROM accounts WHERE active=TRUE ORDER BY"
                      + " code";
                }
                default -> throw new IllegalArgumentException("Unknown reference list.");
              };
          return Sql.rows(c, sql);
        });
  }

  public Row record(User u, String entity, long id) {
    return db.read(
        c -> {
          String sql;
          switch (entity) {
            case "products" -> {
              u.require(Permission.PRODUCT_EDIT);
              sql = "SELECT * FROM products WHERE id=?";
            }
            case "parties" -> {
              u.require(Permission.CUSTOMER_VIEW);
              Row p = Sql.one(c, "SELECT * FROM parties WHERE id=?", id);
              if (p.text("kind").equals("SUPPLIER")) u.require(Permission.SUPPLIER_VIEW);
              return p;
            }
            case "held_sales" -> {
              u.require(Permission.SALE_CREATE);
              Row h = Sql.one(c, "SELECT * FROM held_sales WHERE id=?", id);
              check(
                  h.number("user_id") == u.id() || u.can(Permission.SETTINGS_USERS),
                  "This held cart belongs to another cashier.");
              return h;
            }
            case "purchase_orders" -> {
              u.require(Permission.PURCHASE_VIEW);
              sql = "SELECT * FROM purchase_orders WHERE id=?";
            }
            case "business_profile" -> {
              u.require(Permission.SETTINGS_GENERAL);
              sql = "SELECT * FROM business_profile WHERE id=?";
            }
            default -> throw new IllegalArgumentException("Unknown record type.");
          }
          return Sql.one(c, sql, id);
        });
  }

  public List<Row> child(User u, String kind, long id) {
    return db.read(
        c -> {
          return switch (kind) {
            case "barcodes" -> {
              u.require(Permission.PRODUCT_EDIT);
              yield Sql.rows(
                  c,
                  "SELECT * FROM product_barcodes WHERE product_id=? ORDER BY is_primary DESC,id",
                  id);
            }
            case "product_history" -> {
              u.require(Permission.PRODUCT_COST_VIEW);
              yield Sql.rows(
                  c, "SELECT * FROM price_history WHERE product_id=? ORDER BY id DESC", id);
            }
            case "product_movements" -> {
              u.require(Permission.INVENTORY_VIEW);
              List<Row> movements =
                  Sql.rows(
                      c,
                      "SELECT m.*,d.number FROM stock_movements m JOIN documents d ON"
                          + " d.id=m.document_id WHERE product_id=? ORDER BY m.id DESC FETCH FIRST"
                          + " 250 ROWS ONLY",
                      id);
              if (!u.can(Permission.PRODUCT_COST_VIEW))
                for (Row movement : movements) {
                  movement.remove("cost_value");
                  movement.remove("unit_cost");
                }
              yield movements;
            }
            case "product_sales" -> {
              u.require(Permission.REPORT_SALES);
              yield Sql.rows(
                  c,
                  "SELECT f.number,f.document_date,f.quantity,f.revenue FROM v_sales_facts f WHERE"
                      + " f.product_id=? ORDER BY f.document_id DESC FETCH FIRST 250 ROWS ONLY",
                  id);
            }
            case "product_purchases" -> {
              u.require(Permission.PURCHASE_VIEW);
              yield Sql.rows(
                  c,
                  "SELECT d.number,d.document_date,l.quantity,l.unit_price,l.total,d.currency_code"
                      + " FROM documents d JOIN document_lines l ON l.document_id=d.id WHERE"
                      + " l.product_id=? AND d.kind IN ('PURCHASE','PURCHASE_RETURN') ORDER BY d.id"
                      + " DESC FETCH FIRST 250 ROWS ONLY",
                  id);
            }
            case "held_lines" -> {
              Row h = Sql.one(c, "SELECT * FROM held_sales WHERE id=?", id);
              check(
                  h.number("user_id") == u.id() || u.can(Permission.SETTINGS_USERS),
                  "This cart belongs to another cashier.");
              yield Sql.rows(
                  c,
                  "SELECT l.*,p.name,un.name AS unit_name FROM held_lines l JOIN products p ON"
                      + " p.id=l.product_id JOIN units un ON un.id=p.unit_id WHERE held_id=? ORDER"
                      + " BY l.id",
                  id);
            }
            case "po_lines" -> {
              u.require(Permission.PURCHASE_VIEW);
              yield Sql.rows(
                  c,
                  "SELECT l.*,p.name,un.name AS unit_name FROM po_lines l JOIN products p ON"
                      + " p.id=l.product_id JOIN units un ON un.id=p.unit_id WHERE order_id=? ORDER"
                      + " BY l.id",
                  id);
            }
            case "count_lines" -> {
              u.require(Permission.INVENTORY_VIEW);
              yield Sql.rows(
                  c,
                  "SELECT l.*,p.name,l.counted-l.expected AS variance FROM stock_count_lines l JOIN"
                      + " products p ON p.id=l.product_id WHERE count_id=? ORDER BY l.id",
                  id);
            }
            case "journal_lines" -> {
              u.require(Permission.ACCOUNTING_VIEW);
              yield Sql.rows(
                  c,
                  "SELECT a.code,a.name,l.debit,l.credit,l.memo FROM journal_lines l JOIN accounts"
                      + " a ON a.id=l.account_id WHERE entry_id=? ORDER BY l.id",
                  id);
            }
            case "party_statement" -> {
              u.require(Permission.CUSTOMER_VIEW);
              Row p = Sql.one(c, "SELECT * FROM parties WHERE id=?", id);
              if (p.text("kind").equals("SUPPLIER")) u.require(Permission.SUPPLIER_VIEW);
              yield Sql.rows(
                  c,
                  "SELECT"
                      + " d.document_date,d.number,d.kind,e.currency_code,e.amount,e.base_amount,SUM(e.base_amount)"
                      + " OVER(ORDER BY e.id) AS"
                      + " running_base_balance,e.open_amount,e.open_base,e.due_date FROM"
                      + " party_entries e JOIN documents d ON d.id=e.document_id WHERE e.party_id=?"
                      + " ORDER BY e.id",
                  id);
            }
            case "party_history" -> {
              u.require(Permission.CUSTOMER_VIEW);
              Row p = Sql.one(c, "SELECT * FROM parties WHERE id=?", id);
              if (p.text("kind").equals("SUPPLIER")) u.require(Permission.SUPPLIER_VIEW);
              yield Sql.rows(
                  c,
                  "SELECT id,number,kind,document_date,currency_code,total,base_total,status FROM"
                      + " documents WHERE party_id=? ORDER BY id DESC FETCH FIRST 250 ROWS ONLY",
                  id);
            }
            case "permissions" -> {
              u.require(Permission.SETTINGS_USERS);
              yield Sql.rows(c, "SELECT permission_code FROM role_permissions WHERE role_id=?", id);
            }
            default -> throw new IllegalArgumentException("Unknown detail type.");
          };
        });
  }

  public Detail document(User u, long id) {
    return db.read(
        c -> {
          Row d =
              Sql.one(
                  c,
                  "SELECT d.*,u.full_name AS"
                      + " cashier,cur.decimal_places,cur.symbol,cur.symbol_before,"
                      + "COALESCE((SELECT SUM(e.open_amount) FROM party_entries e WHERE"
                      + " e.document_id=d.id AND e.open_amount>0),0) AS account_outstanding FROM documents d"
                      + " JOIN users u ON u.id=d.created_by JOIN currencies cur ON"
                      + " cur.code=d.currency_code WHERE d.id=?",
                  id);
          String kind = d.text("kind");
          if (kind.startsWith("PURCHASE") || kind.equals("SUPPLIER_PAYMENT"))
            u.require(Permission.PURCHASE_VIEW);
          else if (kind.equals("SALE") || kind.equals("SALE_RETURN")) {
            u.require(Permission.POS_ACCESS);
            check(
                u.can(Permission.REPORT_SALES) || d.number("created_by") == u.id(),
                "You may view your own receipts.");
          } else if (kind.equals("CUSTOMER_PAYMENT")) u.require(Permission.CUSTOMER_VIEW);
          else u.require(Permission.ACCOUNTING_VIEW);
          List<Row> lines =
              Sql.rows(c, "SELECT * FROM document_lines WHERE document_id=? ORDER BY id", id);
          if (!u.can(Permission.PRODUCT_COST_VIEW)) {
            d.remove("cogs");
            for (Row l : lines) {
              l.remove("cost_total");
              l.remove("cost_basis");
            }
          }
          return new Detail(
              d,
              lines,
              Sql.rows(c, "SELECT * FROM payments WHERE document_id=? ORDER BY id", id),
              Sql.rows(
                  c,
                  "SELECT id,number,kind,document_date,total,currency_code FROM documents WHERE"
                      + " original_id=? OR (exchange_id IS NOT NULL AND exchange_id=?) ORDER BY id",
                  id,
                  d.get("exchange_id")));
        });
  }

  public java.math.BigDecimal price(User u, long product, String currency, boolean purchase) {
    u.require(purchase ? Permission.PURCHASE_CREATE : Permission.POS_ACCESS);
    return db.read(
        c -> {
          Row p = Sql.one(c, "SELECT * FROM products WHERE id=?", product);
          return round(
              divide(
                  p.money(purchase ? "last_cost" : "price"),
                  CurrencyService.rate(c, currency, SettingsService.today(c))),
              SettingsService.scale(c, currency));
        });
  }

  public void business(User u, Map<String, Object> fields) {
    u.require(Permission.SETTINGS_GENERAL);
    db.tx(
        c -> {
          check(
              Set.of(
                      "store_name",
                      "legal_name",
                      "address",
                      "phone",
                      "email",
                      "tax_number",
                      "country",
                      "time_zone",
                      "footer",
                      "logo_path")
                  .containsAll(fields.keySet()),
              "Invalid store field.");
          required(String.valueOf(fields.get("store_name")), "Store name");
          Sql.edit(
              c, "business_profile", Sql.one(c, "SELECT * FROM business_profile").id(), fields);
          Audit.log(c, u, "STORE_UPDATED", "BUSINESS", 1, "");
          return null;
        });
  }
}
