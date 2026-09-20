package com.professionalpos;

import com.professionalpos.accounting.AccountingService;
import com.professionalpos.backup.BackupService;
import com.professionalpos.db.Database;
import com.professionalpos.model.User;
import com.professionalpos.reporting.ReportService;
import com.professionalpos.service.*;
import java.util.*;

public final class App {
  public final Database db;
  public final AuthService auth;
  public final CatalogService catalog;
  public final TradeService trade;
  public final ReturnService returns;
  public final PurchaseService purchase;
  public final InventoryService inventory;
  public final DebtService debt;
  public final ExpenseService expenses;
  public final CashService cash;
  public final SettingsService settings;
  public final QueryService queries;
  public final CurrencyService currencies;
  public final ReportService reports;
  public final AccountingService accounting;
  public final BackupService backup;
  public final NotificationService notifications;
  public final ImportService importer;
  public User user;
  public Map<String, String> preferences = new HashMap<>();

  public App(Database database) {
    db = database;
    auth = new AuthService(db);
    catalog = new CatalogService(db);
    trade = new TradeService(db);
    returns = new ReturnService(db);
    purchase = new PurchaseService(db);
    inventory = new InventoryService(db);
    debt = new DebtService(db);
    expenses = new ExpenseService(db);
    cash = new CashService(db);
    settings = new SettingsService(db);
    queries = new QueryService(db);
    currencies = new CurrencyService(db);
    reports = new ReportService(db);
    accounting = new AccountingService(db);
    backup = new BackupService(db);
    notifications = new NotificationService(db);
    importer = new ImportService(db);
  }

  public void reload() {
    preferences = settings.all();
  }

  public java.time.LocalDate today() {
    return java.time.LocalDate.now(
        java.time.ZoneId.of(preferences.getOrDefault("time_zone", "Asia/Beirut")));
  }

  public String base() {
    return preferences.getOrDefault("base_currency", "USD");
  }
}
