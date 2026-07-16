# Local fresh PostgreSQL gate

Use this gate before merging Java changes that affect Flyway migrations,
entities, enums, or Spring Data Repository queries. It gives Windows/Git Bash
the two checks that were previously available only in CI:

1. the exact CI Repository query startup test (`test` profile), and
2. a real PostgreSQL 17 + pgvector startup of the `pg` profile on an empty
   database, including Flyway and the complete Spring/JPA context.

## Prerequisites

- Java 21
- Maven, or the repository Maven wrapper
- Docker Desktop with Linux containers and Compose v2
- Git Bash (the script is Bash; run it from the repository root)

No production credentials are used. The Compose file contains visibly local,
non-secret placeholder passwords. The database binds only to `127.0.0.1` and
uses host port `55432`, so it does not touch an existing PostgreSQL on `5432`.

## Commands

Preview the complete plan without starting Docker or Maven:

```bash
bash scripts/testing/fresh-db-gate.sh --dry-run
```

Run the gate and automatically remove the container afterward:

```bash
bash scripts/testing/fresh-db-gate.sh
```

Keep the isolated database for inspection after the gate:

```bash
bash scripts/testing/fresh-db-gate.sh --keep
```

When kept, inspect it with:

```bash
docker compose -p cretas-fresh-db -f docker-compose.fresh-db.yml exec postgres \
  psql -U cretas_user -d cretas_db
```

Remove a kept environment:

```bash
docker compose -p cretas-fresh-db -f docker-compose.fresh-db.yml down -v
```

Optional local-only overrides are `FRESH_DB_PORT`, `FRESH_DB_APP_PORT`,
`FRESH_DB_STARTUP_TIMEOUT`, and a project name matching
`cretas-fresh-db[-suffix]`. Port `5432` is rejected deliberately.

## What success proves

- every `*RepositoryQueryValidationTest` passes with the same Maven switches as
  `.github/workflows/ci.yml`;
- all Flyway migrations apply to a genuinely empty PostgreSQL database;
- the `pg` profile reaches `/api/mobile/health`, so Spring and the complete JPA
  context initialized;
- `flyway_schema_history` is non-empty and contains no failed migration.

This is a local merge/release gate, not a production deployment. It does not
connect to LIUSHANMEN, F006, or any remote database.
