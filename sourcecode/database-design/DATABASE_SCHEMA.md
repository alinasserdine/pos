# Database schema

The authoritative H2 schema and seed are the versioned files in `src/main/resources/db/migration/`, currently V001 through V003. `Database.initialize()` applies missing migrations and checks SHA-256 fingerprints. H2 DDL initialization can resume after an interruption; seed/version writes are transactional. The program never drops a populated database during startup.

The model contains **48 base tables, 8 views and 94 foreign keys**. Generated BIGINT IDs identify entities; unique document numbers are separate from internal IDs. Natural/composite keys are used for currency, permission, setting and role/read-mapping records. Money/quantity columns use DECIMAL(24,6), rates DECIMAL(28,12), dates DATE and timestamps TIMESTAMP. NOT NULL, UNIQUE and CHECK constraints enforce domains and nonnegative values where applicable.

## Table map

| Table | Purpose | Foreign keys |
| --- | --- | --- |
| `schema_version` | Applied migration version numbers and SHA-256 checksums. | 0 |
| `app_settings` | Application configuration and posting-account mappings. | 0 |
| `business_profile` | Store identity, tax details, receipt footer and external logo path. | 0 |
| `roles` | Administrator, cashier and custom role identities. | 0 |
| `permissions` | Fine-grained authorization codes. | 0 |
| `role_permissions` | Composite-key role-to-permission grants. | 2 |
| `users` | Usernames, password hashes, role references and lockout state. | 1 |
| `currencies` | Currency codes, symbols, decimal places and active flags. | 0 |
| `exchange_rates` | Unique currency/date rates and the user recording each rate. | 2 |
| `tax_rates` | Named rates and activity state. | 0 |
| `categories` | Product category hierarchy. | 1 |
| `units` | Named measurement units and fractional-quantity policy. | 0 |
| `parties` | Customers and suppliers, identified by kind; commercial terms and credit limits. | 1 |
| `products` | SKU/variant identity, category/unit/tax/supplier, current stock value and cached costs. | 5 |
| `product_barcodes` | Unique barcodes linked to products; primary barcode flag. | 1 |
| `price_history` | Auditable price/cost changes. | 2 |
| `accounts` | Chart of accounts, type and optional parent. | 1 |
| `payment_methods` | Payment code to general-ledger account mapping. | 1 |
| `document_sequences` | Human-readable document-number counters, updated transactionally. | 0 |
| `cash_sessions` | Opening/closing till amounts, operator, timestamps and differences. | 1 |
| `purchase_orders` | Unposted order headers with supplier, currency/rate and lifecycle status. | 3 |
| `po_lines` | Ordered quantities, received quantities, agreed cost/discount and tax snapshot. | 2 |
| `documents` | Shared immutable financial headers for sales, purchases, returns, payments, expenses, adjustments and journals. | 7 |
| `document_lines` | Shared invoice/return product snapshots; original-line and PO-line links. | 4 |
| `payments` | Actual tender/application amounts, change, payment method and original/base currency values. | 4 |
| `fx_revaluations` | Currency/date closing revaluation headers and reversal state. | 4 |
| `fx_revaluation_lines` | Per-open-item historical and closing carrying values. | 3 |
| `inventory_revaluations` | Quantity-neutral inventory write-downs and reversals. | 3 |
| `damaged_inventory` | Damaged quantity, historical cost and controlled carrying value by source return line. | 2 |
| `damaged_inventory_events` | Valuation and disposal audit trail for damaged stock. | 3 |
| `card_settlements` | Gross processor settlements, fees, fee tax and bank deposit. | 2 |
| `period_closures` | Income-statement close, linked lock and controlled reversal. | 4 |
| `party_entries` | Contact account movements and remaining original/base carrying amounts. | 3 |
| `allocations` | Links debit and credit account entries to document allocation and realized FX. | 2 |
| `journal_entries` | Posted journal header linked uniquely to its financial document. | 3 |
| `journal_lines` | Debit/credit values by account and optional party. | 2 |
| `stock_movements` | Signed quantity/value movement with original document/product links. | 4 |
| `cost_layers` | FIFO/receipt layer quantities, value and acquisition date. | 2 |
| `cost_consumptions` | Cost layer portions attributed to outgoing stock. | 2 |
| `cash_movements` | Signed original/base money movement linked to shift and document. | 3 |
| `held_sales` | Saved cart header; no financial posting. | 3 |
| `held_lines` | Saved quantities, prices and discounts for held carts. | 2 |
| `stock_counts` | Draft/posted count sessions with reason and user. | 2 |
| `stock_count_lines` | Count snapshot, actual quantity and unique product per count. | 2 |
| `period_locks` | Date intervals locked against posting with authorized reopen history. | 1 |
| `notifications` | Unique application reminder keys and resolved state. | 0 |
| `notification_reads` | Per-user read markers. | 2 |
| `audit_logs` | Actor, action, entity, old/new summaries, reason, approver and timestamp. | 2 |

## Important relationships

A financial `documents` row owns its `document_lines`, `payments` and unique `journal_entries` header. Journal lines refer to accounts, with optional party references. Documents may refer to the original document, order, cash session, user and approver. Original-line references enforce return traceability. Purchase order lines accumulate received quantities from posted receipts.

Products link category, tax, unit, preferred supplier and optional parent product. Barcodes are unique across products. Stock movements link product/document/line, and cost layers/consumptions support original issue-cost attribution. `products.on_hand` and `stock_value` are reconciled caches of movement history.

Parties are typed CUSTOMER or SUPPLIER. `party_entries` link account activity to financial documents; `allocations` retain the relationship between outstanding invoice entries and credits/payments. `open_amount` and `open_base` are the remaining original and base carrying amounts. They are modified only through transactional settlement logic.

`roles` → `role_permissions` → `permissions` controls service operations. `users` stores salted password hashes, not plaintext passwords. `audit_logs` keeps operation summaries with an optional approver. Users/products/contacts/accounts are generally archived rather than deleted after use.

## Convenience views

| View | Source |
| --- | --- |
| `sales` | SALE headers from documents |
| `sale_items` | Lines belonging to SALE documents |
| `sale_returns` | SALE_RETURN headers |
| `purchases` | PURCHASE headers |
| `purchase_returns` | PURCHASE_RETURN headers |
| `customers` | CUSTOMER parties |
| `suppliers` | SUPPLIER parties |
| `v_sales_facts` | Signed sale/return line quantities, base revenue, tax, COGS and discounts |

These views explain the correspondence to the PDF's suggested individual tables. Shared normalized tables avoid separate duplicate posting/return/debt models. Expense categories are real expense accounts; expense headers use documents, attached payment/account rows and journals. Reminder state is stored in notifications.

## Indexes and invariants

Indexes cover product name/SKU/barcode, document dates and party references, original return lines, product movements, FIFO remaining layers, party balances, journal date/account lookup, cash sessions and audit time. Unique indexes also serve username, document/request keys, contact/SKU/account codes and currency/date rate lookup.

Cross-table invariants are enforced in Java within the same transaction: balanced journals, return eligibility, credit limits, currency precision, stock policy, cost-layer allocation, invoice payment totals, open cash session requirements and period locks. SQL scripts carry every database constraint, but database schema alone is not a replacement for those business services.

## Complete column inventory

The SQL files specify all data types/defaults/checks. `schema-manifest.json` provides a machine-readable table/column inventory used to check the separate engine scripts.

### schema_version

`version_no`, `checksum`, `installed_at`.

### app_settings

`setting_key`, `setting_value`.

### business_profile

`id`, `store_name`, `legal_name`, `address`, `phone`, `email`, `tax_number`, `country`, `time_zone`, `footer`, `logo_path`.

### roles

`id`, `role_code`, `name`.

### permissions

`permission_code`.

### role_permissions

`role_id`, `permission_code`.

### users

`id`, `username`, `full_name`, `password_hash`, `role_id`, `active`, `failed_attempts`, `locked_until`, `last_login`, `must_change`, `created_at`.

### currencies

`code`, `name`, `symbol`, `decimal_places`, `symbol_before`, `active`.

### exchange_rates

`id`, `currency_code`, `effective_date`, `rate_to_base`, `created_by`, `created_at`, `UNIQUE(currency_code,effective_date)`.

### tax_rates

`id`, `name`, `rate`, `active`.

### categories

`id`, `name`, `name_ar`, `tax_id`, `active`.

### units

`id`, `name`, `name_ar`, `fractional`, `active`.

### parties

`id`, `kind`, `code`, `name`, `company`, `contact`, `phone`, `email`, `address`, `tax_number`, `currency_code`, `credit_limit`, `terms_days`, `discount_pct`, `notes`, `active`.

### products

`id`, `sku`, `name`, `name_ar`, `description`, `brand`, `category_id`, `unit_id`, `tax_id`, `preferred_supplier_id`, `parent_product_id`, `variant_label`, `price`, `wholesale_price`, `current_cost`, `last_cost`, `on_hand`, `stock_value`, `min_stock`, `reorder_level`, `max_stock`, `track_stock`, `allow_negative`, `fractional`, `favorite`, `active`, `created_at`, `updated_at`.

### product_barcodes

`id`, `product_id`, `barcode`, `is_primary`.

### price_history

`id`, `product_id`, `old_price`, `new_price`, `old_cost`, `new_cost`, `user_id`, `reason`, `changed_at`.

### accounts

`id`, `code`, `name`, `name_ar`, `account_type`, `parent_id`, `system_account`, `active`.

### payment_methods

`code`, `name`, `account_code`, `active`, `is_cash`.

### document_sequences

`sequence_code`, `next_value`.

### cash_sessions

`id`, `user_id`, `opened_at`, `closed_at`, `opening_cash`, `expected_cash`, `counted_cash`, `difference`, `notes`.

### purchase_orders

`id`, `number`, `supplier_id`, `ordered_on`, `expected_on`, `currency_code`, `rate_to_base`, `status`, `notes`, `created_by`.

### po_lines

`id`, `order_id`, `product_id`, `quantity`, `received_qty`, `unit_cost`, `discount_pct`, `tax_rate`.

### documents

`id`, `kind`, `number`, `request_key`, `original_id`, `exchange_id`, `order_id`, `party_id`, `party_name`, `party_address`, `party_tax_number`, `document_date`, `created_at`, `due_date`, `currency_code`, `rate_to_base`, `subtotal`, `discount`, `net`, `tax`, `total`, `base_net`, `base_tax`, `base_total`, `cogs`, `status`, `reference`, `notes`, `reason`, `store_name`, `store_address`, `store_phone`, `store_tax_number`, `logo_path`, `footer`, `created_by`, `approved_by`, `cash_session_id`.

### document_lines

`id`, `document_id`, `original_line_id`, `po_line_id`, `product_id`, `product_name`, `product_name_ar`, `sku`, `barcode`, `category_name`, `unit_name`, `quantity`, `unit_price`, `discount`, `tax_name`, `tax_rate`, `net`, `tax`, `total`, `base_net`, `base_tax`, `base_total`, `cost_total`, `cost_basis`, `returned_qty`, `disposition`, `note`.

### payments

`id`, `document_id`, `method_code`, `currency_code`, `rate_to_base`, `amount`, `base_amount`, `tendered`, `change_amount`, `direction`, `reference`, `cash_session_id`.

### party_entries

`id`, `party_id`, `document_id`, `currency_code`, `amount`, `base_amount`, `open_amount`, `open_base`, `due_date`, `reminder_date`, `created_at`.

### allocations

`id`, `settlement_entry_id`, `invoice_entry_id`, `amount`, `historical_base`, `settlement_base`, `fx_difference`, `created_at`, `CHECK(settlement_entry_id<>invoice_entry_id)`.

### journal_entries

`id`, `number`, `document_id`, `journal_date`, `description`, `reversal_of`, `created_by`, `posted_at`.

### journal_lines

`id`, `entry_id`, `account_id`, `debit`, `credit`, `memo`, `CHECK((debit>0`.

### stock_movements

`id`, `product_id`, `document_id`, `document_line_id`, `movement_type`, `quantity`, `before_qty`, `after_qty`, `cost_value`, `reason`, `user_id`, `created_at`, `CHECK(after_qty=before_qty+quantity)`.

### cost_layers

`id`, `product_id`, `movement_id`, `original_quantity`, `remaining_qty`, `original_value`, `remaining_value`, `acquired_at`, `CHECK(remaining_qty<=original_quantity)`.

### cost_consumptions

`id`, `movement_id`, `layer_id`, `quantity`, `cost_value`.

### cash_movements

`id`, `session_id`, `document_id`, `movement_type`, `amount`, `reason`, `created_by`, `created_at`.

### held_sales

`id`, `number`, `party_id`, `user_id`, `currency_code`, `discount_value`, `discount_percent`, `note`, `created_at`.

### held_lines

`id`, `held_id`, `product_id`, `quantity`, `price`, `discount_value`, `discount_percent`, `note`.

### stock_counts

`id`, `number`, `status`, `created_by`, `created_at`, `posted_document_id`.

### stock_count_lines

`id`, `count_id`, `product_id`, `expected`, `counted`, `UNIQUE(count_id,product_id)`.

### period_locks

`id`, `start_date`, `end_date`, `locked_by`, `reason`, `active`, `CHECK(start_date<=end_date)`.

### notifications

`id`, `notification_key`, `kind`, `entity_id`, `message`, `created_at`, `resolved`.

### notification_reads

`notification_id`, `user_id`.

### audit_logs

`id`, `user_id`, `action`, `entity_type`, `entity_id`, `old_summary`, `new_summary`, `reason`, `approved_by`, `created_at`.
