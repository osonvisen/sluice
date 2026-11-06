# Sluice

A small integration platform between a webshop, a warehouse and an ERP, with an approval queue for AI agents (MCP). It's a portfolio project in Kotlin, Spring Boot and Angular.

My global coding standard in `~/.claude/CLAUDE.md` and `~/.claude/standards/` applies in full. This file only covers what is specific to this project.

## Decisions (read before you start)
The architecture decisions are in `docs/adr/`:
- **0001:** vertical slices in one Gradle module, with ports & adapters only towards external systems
- **0002:** job queue in Postgres with `FOR UPDATE SKIP LOCKED`, not Kafka
- **0003:** Spring Data JDBC + `JdbcClient`, Flyway
- **0004:** Keycloak/OIDC with Spring Boot as the BFF, client credentials for MCP, and roles mapped to permissions
- **0005:** one container, with Angular built into the Spring Boot jar
- **0006:** stack, versions and language support (`en` + `nb`)

New decisions are recorded as a new ADR. Don't change an accepted ADR. Supersede it with a new one instead.

## Stack in brief
- Kotlin, JDK 25, Spring Boot (latest stable), Gradle KTS + version catalog
- Postgres, Spring Data JDBC, Flyway
- Angular + Material + Transloco, with the client generated from OpenAPI
- Keycloak (Docker Compose profile `auth`)
- JUnit 5, Testcontainers, WireMock, spring-security-test, Konsist
- ktlint, detekt, ESLint, Prettier
- GitHub Actions

## Structure
Base package: `no.orjans.sluice` (Gradle group `no.orjans`).

```
backend/      features/<area>/<slice>/, shared/, infrastructure/
frontend/     core/, shared/, features/<feature>/
keycloak/     realm export (demo users, roles, MCP client)
docs/adr/     architecture decisions
notater/      private, gitignored
```

## Running locally (8 GB RAM, start only what you need)
- `docker compose up` gives you Postgres only.
- `docker compose --profile auth up` adds Keycloak (memory-capped). Only use it when working on login.
- The `local-noauth` profile gives a fixed dev user when you're not working on login.
- Tests never start Keycloak.

## Milestones
See `PLAN.md`. Work through one milestone at a time, and stop for review after each one.
