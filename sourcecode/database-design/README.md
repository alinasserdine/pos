# Database design

The application uses an embedded H2 file database through JDBC. `Main.java` creates the runtime folders and `Database.initialize()` applies the migrations automatically.

## Runtime source of truth

The program reads these files in order:

1. `../src/main/resources/db/migration/V001__initial.sql`
2. `../src/main/resources/db/migration/V002__seed.sql`
3. `../src/main/resources/db/migration/V003__accounting_controls.sql`

Do not combine, rename, or edit an already-applied migration: the program verifies SHA-256 checksums. For a new schema change, add a new numbered migration and update `Database.initialize()`.

## Files for reading the design

- `DATABASE_SCHEMA.md` documents every table and important relationship.
- `schema-manifest.json` provides the schema inventory in structured form.
- `schema-standard.sql` presents the complete equivalent schema in one SQL file for review. It is documentation/reference; the Java application runs the versioned migration files above.

The schema contains 41 tables, 8 views, 73 foreign keys, accounting controls, inventory costing structures, security/permissions, audit history, and seed reference data. Business records and administrator credentials are created only through the running application.
