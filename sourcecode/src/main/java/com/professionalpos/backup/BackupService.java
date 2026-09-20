package com.professionalpos.backup;

import com.professionalpos.db.*;
import com.professionalpos.model.User;
import com.professionalpos.security.Permission;
import com.professionalpos.service.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

public final class BackupService {
  private final Database db;

  public BackupService(Database db) {
    this.db = db;
  }

  public Path create(User u, boolean automatic) {
    if (!automatic) u.require(Permission.BACKUP_CREATE);
    return db.exclusive(
        c -> {
          Path folder = db.home().resolve("backups");
          Files.createDirectories(folder);
          String stamp =
              LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS"));
          Path file = folder.resolve((automatic ? "auto-" : "POS_Backup_") + stamp + ".zip");
          // H2 requires a filename literal for this administrative command. Quotes are escaped,
          // never executed by a shell.
          Sql.update(c, "BACKUP TO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
          try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.stream().noneMatch(e -> e.getName().endsWith("posdb.mv.db")))
              throw new IOException("Backup has no database image.");
          }
          Audit.log(c, u, "BACKUP_CREATED", "BACKUP", 0, file.getFileName().toString());
          if (automatic) {
            int keep = Integer.parseInt(SettingsService.get(c, "backup_keep"));
            try (var files = Files.list(folder)) {
              List<Path> autos =
                  files
                      .filter(
                          p ->
                              p.getFileName().toString().startsWith("auto-")
                                  && p.toString().endsWith(".zip"))
                      .sorted(Comparator.reverseOrder())
                      .toList();
              for (int i = keep; i < autos.size(); i++) Files.deleteIfExists(autos.get(i));
            }
          }
          return file;
        });
  }

  public Path validate(Path file) throws Exception {
    Path temp = Files.createTempDirectory(db.home().resolve("data"), "restore-check-");
    Path image = temp.resolve("posdb.mv.db");
    boolean success = false;
    try (ZipFile zip = new ZipFile(file.toFile())) {
      List<? extends ZipEntry> entries = zip.stream().filter(e -> !e.isDirectory()).toList();
      ZipEntry entry = null;
      for (ZipEntry e : entries) {
        String name = e.getName().replace('\\', '/');
        if (name.equals("posdb.mv.db") || name.endsWith("/posdb.mv.db")) {
          if (entry != null) throw new IOException("Backup contains multiple database images.");
          entry = e;
        }
      }
      if (entry == null) throw new IOException("This is not a Professional POS backup.");
      if (entry.getSize() > 5_000_000_000L)
        throw new IOException("Backup exceeds the supported restore size.");
      try (InputStream in = zip.getInputStream(entry);
          OutputStream out = Files.newOutputStream(image)) {
        byte[] buffer = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
          total += n;
          if (total > 5_000_000_000L) throw new IOException("Backup is too large.");
          out.write(buffer, 0, n);
        }
      }
      try (Connection c =
          DriverManager.getConnection(
              "jdbc:h2:file:" + temp.resolve("posdb") + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r",
              "sa",
              "")) {
        long schemaVersions = Sql.count(c, "SELECT COUNT(*) FROM schema_version");
        if ((schemaVersions < 2 || schemaVersions > 3)
            || Sql.count(c, "SELECT COUNT(*) FROM business_profile") != 1)
          throw new IOException("Backup schema or business profile is incompatible.");
        Sql.count(c, "SELECT COUNT(*) FROM documents");
        Sql.count(c, "SELECT COUNT(*) FROM journal_entries");
      }
      success = true;
      return image;
    } finally {
      if (!success) deleteTree(temp);
    }
  }

  /** Called only after admin reauthentication and confirmation. App exits after this operation. */
  public void restore(User u, Path validatedImage) throws Exception {
    u.require(Permission.BACKUP_RESTORE);
    Path preRestore = create(u, false);
    db.exclusive(
        c -> {
          Sql.update(c, "SHUTDOWN");
          Path current = db.home().resolve("data/posdb.mv.db"),
              old = db.home().resolve("data/pre-restore.mv.db");
          Files.copy(current, old, StandardCopyOption.REPLACE_EXISTING);
          try {
            Path staged = current.resolveSibling("restore-ready.mv.db");
            Files.copy(validatedImage, staged, StandardCopyOption.REPLACE_EXISTING);
            try {
              Files.move(
                  staged,
                  current,
                  StandardCopyOption.REPLACE_EXISTING,
                  StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
              Files.move(staged, current, StandardCopyOption.REPLACE_EXISTING);
            }
            try (Connection restored = db.open()) {
              Audit.log(
                  restored,
                  null,
                  "RESTORE_PERFORMED",
                  "BACKUP",
                  0,
                  "Previous database saved in " + preRestore.getFileName());
            }
          } catch (Exception e) {
            Files.copy(old, current, StandardCopyOption.REPLACE_EXISTING);
            throw e;
          } finally {
            deleteTree(validatedImage.getParent());
          }
          return null;
        });
  }

  private static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p)) return;
    try (var stream = Files.walk(p)) {
      for (Path x : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
    }
  }
}
