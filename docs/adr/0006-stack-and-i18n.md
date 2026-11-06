# 0006 – Stack, versions and language support

Status: Accepted · 2026-11-06

## Decision
- **Backend:** Kotlin on **JDK 25 (LTS)**, using the latest stable Spring Boot, Gradle Kotlin DSL and a version catalog (`libs.versions.toml`).
- **Frontend:** the latest stable Angular, with standalone components, signals, `OnPush` and Angular Material.
- **API contract:** springdoc-openapi generates the OpenAPI spec, and openapi-generator generates the Angular client from it. CI fails if the client is out of date.
- **Quality:** ktlint, detekt, Konsist, ESLint and Prettier.
- **Tests:** JUnit 5, Testcontainers (Postgres), WireMock (ERP/Shopify) and spring-security-test.
- **Language support (`en` default/fallback, `nb`):**
  - Frontend: Transloco with runtime language switching. The choice is stored per user, falling back to the browser language and then `en`.
  - Backend: ProblemDetail with a stable `code`, and text from `MessageSource` based on `Accept-Language`.
  - Product names and descriptions are stored per locale.
  - Tests check that `en` and `nb` contain the same keys, both in the frontend and in the backend.
- **Versions are verified when added**, not taken from memory. If a library isn't ready for the latest Spring Boot (for example Spring AI in milestone 5), we pick the newest compatible combination and record it here.

## Consequences
- Modern, widely used choices that match iSYS's stack (Java/Kotlin, Spring, Angular, Postgres).
- JDK 25 is already installed from Sparemål-API.
