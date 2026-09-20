package com.professionalpos.db;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.h2.tools.RunScript;

public final class Database implements AutoCloseable {
  @FunctionalInterface
  public interface Work<T> {
    T run(Connection c) throws Exception;
  }

  private final String url;
  private final Path home;
  private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);

  public Database(Path home) {
    this.home = home.toAbsolutePath().normalize();
    this.url =
        "jdbc:h2:file:"
            + this.home.resolve("data/posdb")
            + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
  }

  public Database(String url, Path home) {
    this.url = url;
    this.home = home;
  }

  public Path home() {
    return home;
  }

  public Connection open() throws SQLException {
    return DriverManager.getConnection(url, "sa", "");
  }

  public <T> T read(Work<T> work) {
    gate.readLock().lock();
    try (Connection c = open()) {
      return work.run(c);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new DatabaseException(e);
    } finally {
      gate.readLock().unlock();
    }
  }

  public <T> T tx(Work<T> work) {
    gate.writeLock().lock();
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        if (e instanceof RuntimeException re) throw re;
        throw new DatabaseException(e);
      }
    } catch (SQLException e) {
      throw new DatabaseException(e);
    } finally {
      gate.writeLock().unlock();
    }
  }

  /**
   * Exclusive maintenance operations; BACKUP/SHUTDOWN must not run inside a financial transaction.
   */
  public <T> T exclusive(Work<T> work) {
    gate.writeLock().lock();
    try (Connection c = open()) {
      return work.run(c);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new DatabaseException(e);
    } finally {
      gate.writeLock().unlock();
    }
  }

  public void initialize() {
    try {
      for (String dir : List.of("data", "backups", "exports", "logs", "receipts"))
        Files.createDirectories(home.resolve(dir));
    } catch (IOException e) {
      throw new DatabaseException(e);
    }
    exclusive(
        c -> {
          Sql.update(
              c,
              "CREATE TABLE IF NOT EXISTS schema_version(version_no INTEGER PRIMARY KEY,checksum"
                  + " VARCHAR(64) NOT NULL,installed_at TIMESTAMP NOT NULL DEFAULT"
                  + " CURRENT_TIMESTAMP)");
          for (int n = 1; n <= 3; n++) {
            String resource =
                switch (n) {
                  case 1 -> "/db/migration/V001__initial.sql";
                  case 2 -> "/db/migration/V002__seed.sql";
                  default -> "/db/migration/V003__accounting_controls.sql";
                };
            String source;
            try (InputStream in =
                Objects.requireNonNull(getClass().getResourceAsStream(resource))) {
              source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String checksum =
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
            Row prior = Sql.optional(c, "SELECT * FROM schema_version WHERE version_no=?", n);
            if (prior != null) {
              if (!checksum.equals(prior.text("checksum")))
                throw new IllegalStateException(
                    "Database migration checksum differs. Restore the original application"
                        + " resources.");
              continue;
            }
            if (n == 1)
              source = source.replaceFirst("(?s)CREATE TABLE schema_version \\(.*?\\);", "");
            if (n == 1)
              source =
                  source
                      .replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
                      .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                      .replace("CREATE VIEW ", "CREATE VIEW IF NOT EXISTS ");
            if (n > 1) c.setAutoCommit(false);
            try {
              RunScript.execute(c, new StringReader(source));
              Sql.update(
                  c, "INSERT INTO schema_version(version_no,checksum) VALUES (?,?)", n, checksum);
              if (n > 1) c.commit();
            } catch (Exception failure) {
              if (n > 1) c.rollback();
              throw failure;
            } finally {
              if (n > 1) c.setAutoCommit(true);
            }
          }
          if (Sql.count(c, "SELECT COUNT(*) FROM schema_version") != 3)
            throw new IllegalStateException("This database needs a different application version.");
          for (String name :
              List.of(
                  "documents", "products", "journal_lines", "app_settings", "users", "accounts"))
            Sql.count(c, "SELECT COUNT(*) FROM " + name);
          return null;
        });
  }

  @Override
  public void close() {
    exclusive(
        c -> {
          Sql.update(c, "SHUTDOWN");
          return null;
        });
  }

  public static final class DatabaseException extends RuntimeException {
    public DatabaseException(Throwable cause) {
      super("The database operation could not be completed.", cause);
    }
  }
}
