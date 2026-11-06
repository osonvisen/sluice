# 0008 – A top-level `web/` package for the HTTP edge

Status: Accepted · 2026-11-06

Supplements ADR 0001 (package structure and Konsist rules).

## Context
ADR 0001 has three top-level packages: `features/`, `shared/` and `infrastructure/`. Milestone 0 put the HTTP error handling (ProblemDetail, the global exception handler) in `infrastructure/web/` and made `shared/` plain Kotlin, enforced by Konsist. Konsist also forbids `features/` from depending on `infrastructure/`, because infrastructure holds the adapters that *implement* the features' ports.

From milestone 1, endpoints return the outcome of their use cases (`Result`) over HTTP, and need ETag/If-Match (ADR 0003) and idempotency (webhooks). These are the same rules for every slice and must be written once. They cannot live in:
- `shared/`, because they depend on Spring Web, and `shared/` must stay plain Kotlin;
- `infrastructure/`, because slices may not depend on it.

## Options
1. Let `features/` depend on `infrastructure/web/` only, as an exception to the Konsist rule
2. A new top-level package `web/` for the HTTP edge, which slices may use
3. Put the HTTP helpers in `shared/web/` and exempt that subpackage from the plain-Kotlin rule (what milestone 0 started with)

## Decision
Option 2. Four top-level packages, each with one job:

```
no/orjans/sluice/
  features/        slices: endpoint, handler, SQL, ports
  web/             HTTP edge, shared by all slices: Result → HTTP, ProblemDetail, error codes on the wire,
                   correlation ID, API paths, ETag/If-Match, idempotency
  shared/          plain Kotlin: Result, AppError, typed IDs, value objects
  infrastructure/  adapters (ERP client, Shopify HMAC, DB converters) and Spring configuration
```

Dependency rules, enforced by Konsist:

| Package | May depend on | Must not depend on |
|---|---|---|
| `shared` | the JDK and Kotlin only | Spring, Jakarta, JDBC, HTTP clients, `web`, `features`, `infrastructure` |
| `web` | `shared`, Spring Web | `features`, `infrastructure` |
| `features` | `shared`, `web`, their own slice, any area's root package (the area's public API, e.g. `features.jobs.JobHandler`) | other slices' packages, `infrastructure` |
| `infrastructure` | everything | — (it is the outermost layer: adapters implement feature ports) |

Every production class lives in one of these four packages; only `SluiceApplication` sits at the root.

**Result → HTTP.** Use cases return `Result`. An endpoint unwraps it with `result.getOrThrow()` from `web`. A failure becomes an `AppErrorException`, which `GlobalExceptionHandler` turns into a ProblemDetail through `ProblemDetailFactory`. The exception exists only between the endpoint and the handler; handlers and the domain never throw for expected errors. This keeps the endpoint's return type the real response DTO, so springdoc documents the right schema. The alternatives were a `ResponseEntity<*>` with hand-written OpenAPI annotations on every endpoint, or returning `Result<T>` and teaching springdoc to unwrap it.

## Consequences
- Slices get the HTTP rules (errors, ETag, idempotency) from one place, without depending on adapters.
- `infrastructure/` shrinks to adapters and configuration, which makes "features never depend on infrastructure" a rule without exceptions.
- One more top-level package to explain. The rule of thumb: *if a slice calls it, it is `web` (or `shared` if it has no framework dependency); if Spring calls it or it talks to the outside world, it is `infrastructure`.*
- `AppErrorException` is a controlled use of an exception for an expected error. It never crosses into handler or domain code. It is created without a stack trace, because it is not a bug.
- The OIDC-in-the-cloud decision for milestone 6, which ADR 0007 refers to as 0008, becomes ADR **0009**.
