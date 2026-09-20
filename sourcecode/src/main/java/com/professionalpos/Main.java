package com.professionalpos;

import com.professionalpos.db.*;
import com.professionalpos.i18n.I18n;
import com.professionalpos.model.User;
import com.professionalpos.ui.*;
import java.awt.Desktop;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import javax.swing.*;

public final class Main {
  private static FileChannel instanceChannel;
  private static FileLock instanceLock;
  private static ScheduledExecutorService maintenance;
  private static volatile boolean stopping;

  private Main() {}

  public static void main(String[] args) {
    Theme.install();
    Path home = home();
    try {
      Files.createDirectories(home.resolve("data"));
      Files.createDirectories(home.resolve("logs"));
      instanceChannel =
          FileChannel.open(
              home.resolve("data/app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      instanceLock = instanceChannel.tryLock();
      if (instanceLock == null) {
        JOptionPane.showMessageDialog(null, I18n.t("already_running"));
        return;
      }
      FileHandler log =
          new FileHandler(home.resolve("logs/app-%g.log").toString(), 2_000_000, 3, true);
      log.setFormatter(new SimpleFormatter());
      Logger.getLogger("POS").addHandler(log);
      Logger.getLogger("POS").info("Starting Professional POS 1.0.0");
      App app = new App(new Database(home));
      while (true) {
        try {
          app.db.initialize();
          app.reload();
          break;
        } catch (Exception failure) {
          Logger.getLogger("POS").log(Level.SEVERE, "Database startup failed", failure);
          String[] options = {
            I18n.t("retry"), I18n.t("open_backup_folder"), I18n.t("restore_backup"), I18n.t("exit")
          };
          int choice =
              JOptionPane.showOptionDialog(
                  null,
                  I18n.t("startup_database_error"),
                  I18n.t("professional_pos"),
                  JOptionPane.DEFAULT_OPTION,
                  JOptionPane.ERROR_MESSAGE,
                  null,
                  options,
                  options[0]);
          if (choice == 0) continue;
          if (choice == 1) {
            Files.createDirectories(home.resolve("backups"));
            if (Desktop.isDesktopSupported())
              Desktop.getDesktop().open(home.resolve("backups").toFile());
            continue;
          }
          if (choice == 2) {
            offlineRestore(app);
            continue;
          }
          release();
          return;
        }
      }
      I18n.language(app.preferences.getOrDefault("language", "en"));
      SwingUtilities.invokeLater(
          () -> {
            if (app.auth.setupRequired()) {
              SetupWizard wizard = new SetupWizard(app);
              wizard.setVisible(true);
              if (app.user == null) {
                shutdown(app, false);
                return;
              }
              Ui.work(
                  wizard,
                  () -> {
                    app.reload();
                    return null;
                  },
                  v -> {
                    new MainFrame(app);
                    startMaintenance(app);
                  });
            } else login(app);
          });
    } catch (Exception e) {
      Logger.getLogger("POS").log(Level.SEVERE, "Startup failed", e);
      JOptionPane.showMessageDialog(null, I18n.error(e));
      release();
    }
  }

  private static Path home() {
    String explicit = System.getProperty("pos.home");
    if (explicit != null && !explicit.isBlank())
      return Path.of(explicit).toAbsolutePath().normalize();
    Path working = Path.of("").toAbsolutePath();
    if (Files.isRegularFile(working.resolve("pom.xml"))) return working;
    String local = System.getenv("LOCALAPPDATA");
    return local != null
        ? Path.of(local, "ProfessionalPOS")
        : Path.of(System.getProperty("user.home"), ".professional-pos");
  }

  public static void login(App app) {
    User user = LoginDialog.show(null, app, null);
    if (user == null) {
      shutdown(app, true);
      return;
    }
    app.user = user;
    Ui.work(
        new JPanel(),
        () -> {
          app.reload();
          return null;
        },
        v -> {
          new MainFrame(app);
          startMaintenance(app);
        });
  }

  private static void startMaintenance(App app) {
    if (maintenance != null) return;
    maintenance =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "POS maintenance");
              t.setDaemon(true);
              return t;
            });
    maintenance.scheduleWithFixedDelay(
        () -> {
          if (stopping) return;
          try {
            app.notifications.refresh();
            if (Boolean.parseBoolean(app.preferences.getOrDefault("backup_daily", "true"))) {
              boolean exists;
              try (var files = Files.list(app.db.home().resolve("backups"))) {
                String prefix = "auto-" + LocalDate.now();
                exists = files.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
              }
              if (!exists) app.backup.create(app.user, true);
            }
          } catch (Exception e) {
            Logger.getLogger("POS").log(Level.WARNING, "Scheduled maintenance failed", e);
          }
        },
        30,
        60,
        TimeUnit.SECONDS);
  }

  public static void stopMaintenance() {
    if (maintenance != null) {
      maintenance.shutdown();
      try {
        if (!maintenance.awaitTermination(15, TimeUnit.SECONDS)) maintenance.shutdownNow();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      maintenance = null;
    }
  }

  public static synchronized void shutdown(App app, boolean backup) {
    if (stopping) return;
    stopping = true;
    stopMaintenance();
    try {
      if (backup
          && app.user != null
          && Boolean.parseBoolean(app.preferences.getOrDefault("backup_exit", "true")))
        app.backup.create(app.user, true);
      app.db.close();
    } catch (Exception e) {
      Logger.getLogger("POS").log(Level.WARNING, "Shutdown task failed", e);
    } finally {
      release();
      System.exit(0);
    }
  }

  private static void release() {
    try {
      if (instanceLock != null && instanceLock.isValid()) instanceLock.release();
      if (instanceChannel != null) instanceChannel.close();
    } catch (Exception ignored) {
    }
  }

  private static void offlineRestore(App app) throws Exception {
    JFileChooser chooser = new JFileChooser(app.db.home().resolve("backups").toFile());
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
    Path image = app.backup.validate(chooser.getSelectedFile().toPath());
    Database restored =
        new Database(
            "jdbc:h2:file:" + image.getParent().resolve("posdb") + ";IFEXISTS=TRUE",
            image.getParent());
    App check = new App(restored);
    User admin =
        LoginDialog.show(null, check, com.professionalpos.security.Permission.BACKUP_RESTORE);
    restored.close();
    if (admin == null || !Ui.confirm(null, "restore_confirm")) return;
    Path current = app.db.home().resolve("data/posdb.mv.db");
    if (Files.exists(current))
      Files.copy(
          current,
          app.db
              .home()
              .resolve("backups/damaged-before-restore-" + System.currentTimeMillis() + ".mv.db"));
    Files.copy(image, current, StandardCopyOption.REPLACE_EXISTING);
    Files.deleteIfExists(image);
    Files.deleteIfExists(image.getParent());
  }
}
