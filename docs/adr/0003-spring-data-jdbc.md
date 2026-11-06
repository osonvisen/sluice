# 0003 – Spring Data JDBC + JdbcClient, Flyway for migrations

Status: Accepted · 2026-11-06

## Context
We need persistence for jobs, orders, products (with translations and JSONB attributes), approval requests and audit logs. The job queue requires precise SQL (`FOR UPDATE SKIP LOCKED`).

## Options
1. JPA/Hibernate
2. Spring Data JDBC, with `JdbcClient` for custom SQL
3. jOOQ

## Decision
Option 2.
- **Spring Data JDBC** for simple aggregates. It has no lazy loading, no dirty checking and no session cache: what you save is what gets written.
- **`JdbcClient`** with explicit SQL for the queue, lists with filtering and pagination, and reporting queries.
- **Optimistic concurrency** with `@Version` → exposed as ETag/If-Match → 409 on conflict.
- **Converters** for typed IDs and value objects, plus JSONB, are registered once in `infrastructure/`.
- **Flyway** for migrations. Applied migrations are never changed.

## Consequences
- The behaviour is predictable and easy to explain: no N+1 from lazy loading, and no hidden updates.
- More SQL by hand than with JPA. That is acceptable, and it is SQL Ørjan knows well.
- In an interview, the comparison with JPA/Hibernate (and EF Core) is a strength: knowing *why* JPA was not chosen here.
