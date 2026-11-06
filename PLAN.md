# Plan – Sluice

Goal: show that I can build robust integrations in Kotlin/Spring and Angular, the way a Nordic product company (PIM, WMS, integration and EDI) works. Each milestone should result in something that can be shown on its own.

Architecture decisions are in `docs/adr/`. Read them before starting a milestone. ADR numbers are not reserved in advance: a new decision takes the next free number.

## Milestone 0 – Setup
- Monorepo with `backend/` (Spring Boot, Kotlin, JDK 25, Gradle KTS + version catalog) and `frontend/` (Angular and Material). Folder structure per ADR 0001 (`features/`, `shared/`, `infrastructure/`).
- Spring Data JDBC + `JdbcClient` (ADR 0003), with converters for typed IDs registered in `infrastructure/`.
- `docker-compose.yml` with Postgres by default and Keycloak in its own `auth` profile (ADR 0004; Keycloak itself is set up in milestone 3). Flyway with a first migration.
- `.gitignore` covering `notater/`.
- GitHub Actions: backend build and test (Testcontainers), and frontend build, lint and test.
- Health endpoint (`/health/live`, `/health/ready`) and Swagger UI up and running. All API paths under `/api/**`.
- Shared building blocks in `shared/`: `Result`/`Error`, typed ID base, global ProblemDetail handler, clock (`java.time.Clock`).
- Konsist test for the slice rules (ADR 0001).
- ktlint, detekt, ESLint and Prettier wired into the build and CI.
- i18n foundation: Transloco with `en` and `nb`, a language switcher in the app shell, `MessageSource` with `Accept-Language` in the backend, `ProblemDetail` with a `code` in a global error handler, and a test that checks the translation files have the same keys.
- OpenAPI spec → generated Angular client as part of the frontend build.
- Shared Testcontainers base for integration tests.
- Gradle builds Angular into the Spring Boot jar, plus a multi-stage Dockerfile (ADR 0005). `ng serve` with a proxy for local development.

## Milestone 1 – Order flow with a job queue
- `POST /webhooks/shopify/orders` receives an order in Shopify format (use a realistic sample payload in the test resources).
- HMAC verification of the webhook (`X-Shopify-Hmac-Sha256`).
- Idempotency: the same order (Shopify order id / webhook id) creates only one job, even under parallel calls. A unique constraint in the database is the final guard.
- The queue is **generic**: a `JobHandler<T>` interface per job type. Order import is the first implementation, and later milestones reuse the same queue.
- Each order becomes a row in `jobs` with status `PENDING → RUNNING → SUCCEEDED | FAILED → DEAD`, attempts, `next_run_at` and error message. `FAILED` = a new attempt is planned, `DEAD` = gave up, with `dead_reason` `PERMANENT | EXHAUSTED` (ADR 0009).
- A worker picks up jobs with `SELECT … FOR UPDATE SKIP LOCKED` so that multiple instances can run in parallel without taking the same job.
- Retries with exponential backoff and a maximum number of attempts, then dead-letter (`DEAD`).
- `POST /api/jobs/{id}/retry` to rerun a job manually: only from `FAILED`/`DEAD` (409 `job-not-retryable`), with `If-Match` (428 if missing, 412 if stale). The ETag building block lives in `web/`.
- The webhook verifies the HMAC before parsing JSON, limits the request size, stores the order and the job in one transaction and answers quickly (Shopify's 5 s timeout).
- Tests: duplicate webhook, parallel workers, an error followed by success on retry, and dead-letter after N failures.

## Milestone 2 – Mapping to ERP and line-level traceability
- Map a Shopify order to a simple ERP order format (own DTO with order lines, GTIN/SKU, quantity and price as `BigDecimal`).
- The ERP is a port (`ErpOrderSender`) with an HTTP adapter, so another ERP can be added without touching the domain.
- A fake ERP in WireMock: returns an order number, and sometimes 500 or a timeout.
- Log per order line (`job_line_log`): what was sent, what came back, and errors at line level.
- Validation errors (missing SKU, unknown product) → the job fails with a clear explanation and is not retried pointlessly (distinguish permanent from temporary errors).

## Milestone 3 – Login and Angular: job overview
- Keycloak in Docker Compose (`auth` profile, memory limit) with a realm export: demo users `operator` and `approver`, plus a client for MCP (ADR 0004).
- Spring Security `oauth2Login` as BFF: session cookie + CSRF, roles → permissions (`jobs.read`, `jobs.retry`, `approvals.decide`). Webhooks stay open, protected by HMAC.
- The `local-noauth` profile with a fixed dev user, plus a test that it cannot be enabled together with `prod`.
- Tests with `spring-security-test` (no Keycloak in tests): what each role *can* and *cannot* do.
- A list of jobs with filter on status, search and pagination.
- A detail page: payload, line log, error history and a "Run again" button.
- Display the time and number of attempts in a way the customer understands, with dates formatted per the chosen language.
- Build the shared table, status chip and error/empty states here, so milestones 4 and 5 reuse them.
- Swagger UI and `/v3/api-docs` are closed in the `prod` profile (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`), with a test that checks it.
- The language choice is stored per user (server-side, on the user profile) instead of only in the browser's `localStorage`. The browser language and then `en` remain the fallback (ADR 0006).
- Material, responsive, all text through Transloco (en/nb).

## Milestone 4 – Product data and quality
- `products` with SKU, GTIN, attributes (JSONB) and images. Name and description are stored per locale (at least `en` and `nb`).
- GTIN validation with the GS1 check digit (GTIN-8/12/13/14), with good unit tests.
- A completeness score per channel (e.g. a webshop requires an image and description in Norwegian and English; a marketplace requires GTIN and a brand).
- REST CRUD with ETag/If-Match, plus GraphQL (Spring for GraphQL) for reading products and scores.
- Angular: a product list with the score and what is missing.

## Milestone 5 – MCP with human approval
- An MCP server (Spring AI MCP server or the official Kotlin MCP SDK – pick the one that is most stable when we get there). `/mcp` is a resource server, and the agent authenticates with client credentials from Keycloak.
- Read tools: search products, stock/order status, failed jobs.
- Write tools are **proposals only**: "update product text", "correct stock", "rerun job". They create a row in `approval_requests` with the diff and justification. Nothing is executed directly.
- Angular: an approval queue showing the diff, approve/reject buttons and a comment. Only approved requests are executed, through the same domain services and rules as the rest of the app.
- Audit log: who proposed, who approved, and when. You cannot approve your own proposal.
- README: show how to connect Claude Desktop / Claude Code to the MCP server.

## Milestone 6 – Publishing and README
- One Docker image (ADR 0005), versioned, non-root.
- OIDC in the cloud: a Keycloak container on Cloud Run (min-instances 0) or a managed OIDC provider. Decide and record it as a new ADR (next free number).
- Deploy to Google Cloud Run with Cloud SQL (or Neon) for Postgres.
- Rate limiting for open endpoints (the webhooks) in front of the app: Cloud Armor / the load balancer. Deferred from milestone 1 (technical debt until then).
- README in English: what the project solves, an architecture diagram (Mermaid), how to run it locally, design choices (Postgres queue vs Kafka, idempotency, human-in-the-loop) and screenshots.

## Bonus
- Kafka as an alternative transport, with an explanation of when it pays off over a Postgres queue.
- An EHF invoice (Peppol BIS Billing 3.0) as XML output from an order.
- Metrics (Micrometer) and a simple pattern alert: "unusually many failures in the last hour".
