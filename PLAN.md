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
- The worker runs in three steps: short transaction to claim, the ERP call outside any transaction, short transaction to finish, fenced by the job's version (ADR 0010). ERP calls send an `Idempotency-Key` (the order ID), and backoff has jitter.
- A fake ERP in WireMock: returns an order number, and sometimes 500 or a timeout.
- Log per order line (`job_line_log`): what was sent, what came back, and errors at line level.
- Validation errors (missing SKU, unknown product) → the job fails with a clear explanation and is not retried pointlessly (distinguish permanent from temporary errors).

## Milestone 3 – Login and Angular: job overview
Work in three steps (3a, 3b, 3c), one commit series each, with a green `./gradlew check` (and the frontend checks for 3c) before the next step starts. Stop for review at the end of the milestone.

### 3a – Security in the backend
- Add Spring Security. Verify the version against the current Spring Boot before adding it (ADR 0006).
- Keycloak in Docker Compose (`auth` profile, memory limit) with `keycloak/realm-sluice.json`: demo users `operator` and `approver`, plus a client for MCP (ADR 0004). Check that compose imports the realm at startup. The realm must put `realm_access.roles` in the **ID token** (and userinfo), because `oauth2Login` reads the ID token, not the access token.
- Spring Security `oauth2Login` as BFF: session cookie + CSRF (`CookieCsrfTokenRepository`, cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`, which Angular sends by itself on same-origin calls).
  - `/api/**` answers **401** (not a redirect to Keycloak) when not logged in, so Angular decides what to do.
  - `/webhooks/**` stays open and is exempt from CSRF; HMAC protects it. `/health/**` stays open.
- Roles → permissions in a `GrantedAuthoritiesMapper`: `operator` → `jobs.read`, `jobs.retry`; `approver` → the same plus `approvals.decide`. Endpoints use `@PreAuthorize("hasAuthority(...)")`.
- The `local-noauth` profile with a fixed dev user that has **both** roles. The app refuses to start if it is combined with `prod`, and a test checks it.
- Swagger UI and `/v3/api-docs` are closed in the `prod` profile (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`), with a test that checks it.
- Tests with `spring-security-test` (`oidcLogin()`, no Keycloak in tests): what each role *can* and *cannot* do, 401 when logged out, 403 without the permission, and a `POST …/retry` test that proves the CSRF cookie/header pair works.

### 3b – Read API and user profile
- `GET /api/jobs?status=&q=&page=&size=` (new slice `jobs/listjobs`), newest first, using `jobs_status_created_ix`. Offset pagination with a `Page` response (items, page, size, total). `q` matches order number (via `orders.import_job_id`) and job ID; searching `last_error` is out of scope until it has an index.
- Extend the job detail (new slice or an extended `getjob`) with the payload (order ID only, no personal data), the attempt history from `job_attempts`, and the line log from `job_line_log`. Keep the ETag: the "Run again" button needs it for `If-Match`.
- `V4__users.sql`: `app_users` (OIDC issuer + subject, language, timestamps), created on first login. `GET /api/me` (name, permissions, language) and `PUT /api/me/language`.
- The language choice is stored per user on the server instead of only in the browser's `localStorage`. The browser language and then `en` remain the fallback (ADR 0006, ADR 0011).
- Regenerate the OpenAPI client; CI fails if it is out of date.

### 3c – Angular
- Auth state from `/api/me`. On 401 the app navigates the whole page to `/oauth2/authorization/keycloak`.
- Build the shared table, status chip and error/empty states in `shared/` here, so milestones 3.5, 4 and 5 reuse them.
- A list of jobs with filter on status, search and pagination.
- A detail page: payload, line log, error history and a "Run again" button.
- Display the time and number of attempts in a way the customer understands (for example "Attempt 2 of 5, next attempt in 3 min"), with dates formatted per the chosen language.
- The language switcher saves through `/api/me`.
- Material, responsive, all text through Transloco (en/nb); the key-parity tests cover the new keys.

### Notes
- Develop with `local-noauth` and start Keycloak (8 GB RAM) only when working on login itself.
- Decisions: ADR 0011 (language per user). List conventions (offset, `Page`) are recorded in the code and in this plan; add an ADR only if they grow.

## Milestone 3.5 – Live demo
Goal: the project itself is the demo. A visitor can try Sluice in the browser, see what happens behind the scenes, and see what an end user and an operator each see, with short explanations of the design choices. This is the public face of the project (deployed in milestone 6). It builds on the job views from milestone 3.

**What the visitor sees**
- A split screen that can also be opened as two windows:
  - **Customer:** a small webshop. Pick products, pick a scenario, press "Buy". The customer only sees an order confirmation, like a real shop.
  - **Behind the scenes:** a live timeline. Webhook received and HMAC checked, order and job stored in one transaction, worker claims the job, ERP call, finish. Each step has a "Why do we do it this way?" note that links to the relevant ADR.
- A third tab, **Operations**, is the real job list and detail from milestone 3, where the visitor can press "Run again" on a failed job.
- Guests can try everything without logging in. Logging in with Keycloak is shown as its own scenario.

**Scenarios (the visitor chooses; each has a "Press here to see what happens when…" button)**
1. Successful order: `PENDING → RUNNING → SUCCEEDED`, with the ERP order number.
2. Duplicate webhook: the same order twice gives one job.
3. ERP down (5xx): `FAILED`, backoff and the next attempt counting down.
4. ERP says "wait" (429 with `Retry-After`): the wait time comes from the ERP.
5. Invalid order (missing SKU): permanent error, `DEAD`, line log, then "Run again" after a fix.
6. Crash in the middle of a job: the lease expires, another worker takes over, and the ERP still ends up with one order (fencing + idempotency).

**How**
- A `demo` profile/property. A built-in demo ERP behind the `ErpOrderSender` port (port/adapter in practice), with a per-session switch for how it behaves (ok, 5xx, 429 + `Retry-After`, reject). No separate ERP is needed in the cloud.
- Demo endpoints under `/api/demo/**` that start a scenario. The **server** signs and sends the webhook through the real flow, so the visitor never needs the secret and the real code path is what is shown.
- Time is compressed in demo mode (backoff, lease and poll interval in seconds) so waiting takes seconds, not minutes. The crash scenario simulates a worker that stops after the ERP call.
- Live view by polling (about once a second) of the job, its attempts and the line log; server-sent events only if polling is not enough.
- A sandbox per visitor: demo data carries a demo-session ID, is invisible to other visitors, and is cleaned up after a time limit. Rate limiting and an upper limit on demo data protect the public page.
- The explanations are text in Transloco (en/nb) with links to `docs/adr/`.

**Decisions and open items**
- Write an ADR when this milestone starts, with the next free number: demo mode, the per-session sandbox, and guest access next to ADR 0004 (it adds to it, it does not change it).
- Decide whether the split screen can be popped out to a second window (for example with `BroadcastChannel`) or stays one page.
- Tests: each scenario as an integration test through the real flow; a test that one visitor cannot see another visitor's data; a test that demo endpoints do not exist without the `demo` profile.

**Steps**

Work in four steps, one commit series each, with all checks green before the next step starts. Stop for review at the end of the milestone.

- **3.5.0 – Preparation (from the Milestone 3 review).**
  - Upgrade Spring Boot to the latest stable (4.2.x) as its own `chore` commit, before any feature work. If it needs more than trivial fixes, stop and ask.
  - Move the browser click-through test (Playwright) into the repository and run it in CI. The demo is an end-to-end flow, so this test is needed from here on.
  - Make browser login work against the Docker Compose `app` profile (today Keycloak is `keycloak:8080` inside the Docker network, which a browser cannot reach). Decide how the issuer URL is configured; this must also work for the public deploy (milestone 6).
  - Record who started a manual "Run again" (the user's identity), in the smallest way that milestone 5's audit log can absorb later. Adding a table is a critical decision: ask first.
- **3.5a – Demo backend.** The `demo` profile, the demo ERP behind the port, the scenarios, the per-visitor sandbox, the `/api/demo/**` endpoints, compressed time, and the integration tests.
- **3.5b – Demo frontend.** The split screen (customer and behind the scenes), the Operations tab on the milestone 3 views, scenario buttons, the live timeline, and the explanations (en/nb) linked to the ADRs.
- **3.5c – Hardening.** Rate limiting and limits on demo data, clean-up of old demo sessions, the end-to-end test of every scenario, and the README section.

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
- Deploy the live demo (milestone 3.5) as the public face of the project, for example on orjans.no.
- Rate limiting for open endpoints (the webhooks) in front of the app: Cloud Armor / the load balancer. Deferred from milestone 1 (technical debt until then).
- Circuit breaker in front of the ERP: after repeated `ErpSendResult.Unavailable`, stop calling for a while and fail fast, so a long ERP outage doesn't use up every job's attempts. Backoff, jitter and `Retry-After` dampen this today, but don't stop it. Deferred from milestone 2 (technical debt until then).
- Clean-up of `job_attempts` (and the per-line ERP log): keep the history for a configurable period, then archive or delete it in batches, so the tables don't grow without bound. Deferred from milestone 1.
- README in English: what the project solves, an architecture diagram (Mermaid), how to run it locally, design choices (Postgres queue vs Kafka, idempotency, human-in-the-loop) and screenshots.

## Bonus
- Kafka as an alternative transport, with an explanation of when it pays off over a Postgres queue.
- An EHF invoice (Peppol BIS Billing 3.0) as XML output from an order.
- Metrics (Micrometer) and a simple pattern alert: "unusually many failures in the last hour".
