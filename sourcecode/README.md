# Alind POS — source-code edition

This folder is the clean, source-only version of the application. It contains the same production Java code, English/Arabic resources, and embedded H2 database migrations as the full project. It deliberately excludes compiled JAR/EXE files, bundled runtimes, IDE metadata, logs, backups, receipts, generated databases, and test/build output.

## Run from `Main.java`

Requirements:

- JDK 21 or newer (a JDK, not only a Java runtime)
- IntelliJ IDEA or another Java IDE with Maven support

In IntelliJ IDEA:

1. Extract `sourcecode.zip` to a normal writable folder. Do not run it from inside the ZIP.
2. Open the extracted `sourcecode` folder, or open its `pom.xml` as a Maven project.
3. Let Maven load the H2 dependency declared in `pom.xml`.
4. Open `src/main/java/com/professionalpos/Main.java`.
5. Click the green **Run** button beside `public static void main(String[] args)`.
6. Keep the run configuration working directory set to the extracted `sourcecode` folder.

The entry point is `com.professionalpos.Main`. On first run, the setup wizard asks for the store settings and administrator account. There is no default login.

## Database behavior

No database server and no manual SQL import are required. Running `Main.java` automatically:

- creates `data/posdb.mv.db` in this project folder;
- applies the three versioned SQL migrations in `src/main/resources/db/migration/`;
- creates all tables, relationships, indexes, views, and reference data;
- records migration versions and checksums in `schema_version`.

The generated `data/`, `logs/`, `backups/`, `exports/`, and `receipts/` folders are runtime data and are intentionally not included in the ZIP.

Readable database documentation is in `database-design/`:

- `DATABASE_SCHEMA.md` — tables, columns, relationships, views, indexes, and invariants.
- `schema-manifest.json` — machine-readable schema inventory.
- `schema-standard.sql` — a single readable H2/standard-SQL reference schema.

The actual SQL executed by the program remains in `src/main/resources/db/migration/` so the database is ready as soon as `Main.java` runs.

## Optional command-line build

From this folder, with Maven 3.9+ installed:

```text
mvn clean package
java -Dpos.home=. -jar target/professional-pos.jar
```

Running directly from `Main.java` is the intended workflow for this edition.
