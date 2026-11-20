# Sluice

A small integration platform between a webshop, a warehouse and an ERP, with an approval queue for AI agents (MCP). Kotlin + Spring Boot backend, Angular frontend, Postgres.

> Work in progress. See [`PLAN.md`](PLAN.md) for the milestones and [`docs/adr/`](docs/adr) for the architecture decisions.

## Prerequisites

- JDK 25 (Gradle picks it up automatically; the wrapper is included)
- Node.js 24 and npm
- Docker (for Postgres locally and Testcontainers in tests)

## Run locally

The machine this is built on has 8 GB of RAM, so start only what you need. There are two ways to run the backend:

**Without Keycloak (`local-noauth`)**: for everything except login. Every request is a fixed dev user with both roles.

```bash
docker compose up -d                      # Postgres only
docker compose --profile app up -d erp     # stand-in ERP on :8089, so order imports have somewhere to go

cd backend
./gradlew bootRun --args='--spring.profiles.active=migrate'   # run migrations, then exits
SLUICE_SHOPIFY_WEBHOOK_SECRET=local-dev-secret ./gradlew bootRun --args='--spring.profiles.active=local-noauth'
cd ../frontend && npm ci && npm start     # UI on http://localhost:4200, proxies /api and the login flow to :8080
```

**With Keycloak**: when working on login itself (ADR 0004). Keycloak takes about 500 MB and imports `keycloak/realm-sluice.json` at startup.

```bash
docker compose --profile auth up -d keycloak   # Keycloak on :8081 (admin/admin at http://localhost:8081)
cd backend
SLUICE_SHOPIFY_WEBHOOK_SECRET=local-dev-secret SLUICE_OIDC_CLIENT_SECRET=sluice-web-local-dev-secret ./gradlew bootRun
cd ../frontend && npm start                    # open http://localhost:4200 and log in
docker compose --profile auth stop keycloak    # when you are done
```

Demo users (dev only, in `keycloak/realm-sluice.json`):

| User / password | Role | Can |
|---|---|---|
| `operator` / `operator` | operator | see jobs (`jobs.read`) and run failed jobs again (`jobs.retry`) |
| `approver` / `approver` | approver | the same, and approve or reject proposed changes (`approvals.decide`, milestone 5) |

The realm also has `sluice-mcp`, a client for AI agents (client credentials, milestone 5), with no roles yet.

Profiles:

| Profile | What it does |
|---|---|
| *(none)* | Login with Keycloak (or any OIDC provider set by `SLUICE_OIDC_ISSUER_URI`) |
| `local-noauth` | No login: a fixed dev user with both roles. The app refuses to start if it is combined with `prod` |
| `prod` | JSON logs, Swagger UI and `/v3/api-docs` off, secure session cookie. Set by the Docker image |
| `migrate` | Runs the database migrations and exits; needs no identity provider |

Environment variables are listed in `.env.example`. Login needs `SLUICE_OIDC_ISSUER_URI` (default: the local Keycloak), `SLUICE_OIDC_CLIENT_ID` (default `sluice-web`) and `SLUICE_OIDC_CLIENT_SECRET` (required; the app stops at startup without it).

How login works: Spring Boot is the backend-for-frontend. The browser only gets an HttpOnly session cookie and an `XSRF-TOKEN` cookie that Angular sends back as `X-XSRF-TOKEN`; tokens never reach the browser. `/api/**` answers 401 when you are not logged in, and the Angular app then sends the page to `/oauth2/authorization/keycloak`. Webhooks (`/webhooks/**`) need no login and are protected by their HMAC signature.

Useful URLs:

| URL | What |
|---|---|
| http://localhost:4200 | Angular app (dev server with hot reload) |
| http://localhost:4200/jobs | Job overview: filter, search, open a job, "Run again" |
| http://localhost:8080/swagger-ui.html | Swagger UI (not in `prod`; log in through the app first for `/api` calls) |
| http://localhost:8080/health/live, `/health/ready` | Liveness and readiness |
| http://localhost:8081 | Keycloak admin console (`--profile auth`) |

Migrations are a separate step: a normal start only validates the schema and refuses to start if migrations are pending. Run the `migrate` step again after pulling new migrations. The variables in `.env.example` override the local defaults: docker compose reads them from `.env`, while the backend reads them from the environment.

## Build and test

```bash
cd backend && ./gradlew check     # compile, ktlint, detekt, unit + Testcontainers tests, Konsist, OpenAPI contract
cd backend && ./gradlew bootJar   # one jar with the Angular app inside (ADR 0005)

cd frontend && npm run lint && npm run format:check && npm run test:ci && npm run build

docker build -t sluice .          # multi-stage image: Angular → Spring Boot jar → JRE, non-root
```

To run the image locally the same way it runs in production (Postgres → migrations → app, each step waiting for the previous one to be healthy or to finish, plus a stand-in ERP):

```bash
docker compose --profile app up --build --wait   # app on http://localhost:8080 (stop bootRun first)
docker compose --profile app down
```

This also starts Keycloak (about 1.8 GB in total), because the image needs an identity provider to start. Inside the Docker network the app reaches Keycloak as `keycloak:8080`, which your browser cannot resolve, so this setup proves that the image starts and serves health and webhooks, not a browser login. Use `bootRun` with `--profile auth` for that.

The image runs with the `prod` profile (JSON logs). Run it once with `SPRING_PROFILES_ACTIVE=prod,migrate` before each release to migrate the database.

## Changing the API

The OpenAPI spec in `frontend/openapi/sluice-api.json` is the contract, and the Angular client in `frontend/src/app/core/api/generated/` is generated from it. Both are committed, so API changes show up in code review.

```bash
cd backend && ./gradlew test --tests '*OpenApiContractIT' -PupdateOpenApi   # 1. update the spec
cd frontend && npm run generate:api                                         # 2. regenerate the client
```

CI fails if either is out of date.

## Translations

The UI and the API support English (`en`, default) and Norwegian Bokmål (`nb`). Texts live in `frontend/public/i18n/*.json` and `backend/src/main/resources/i18n/messages*.properties`. Tests on both sides fail if a key is missing in one of the languages.
