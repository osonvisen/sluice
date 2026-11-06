# Sluice

A small integration platform between a webshop, a warehouse and an ERP, with an approval queue for AI agents (MCP). Kotlin + Spring Boot backend, Angular frontend, Postgres.

> Work in progress. See [`PLAN.md`](PLAN.md) for the milestones and [`docs/adr/`](docs/adr) for the architecture decisions.

## Prerequisites

- JDK 25 (Gradle picks it up automatically; the wrapper is included)
- Node.js 24 and npm
- Docker (for Postgres locally and Testcontainers in tests)

## Run locally

```bash
docker compose up -d                      # Postgres only (Keycloak: --profile auth, milestone 3)

cd backend
./gradlew bootRun --args='--spring.profiles.active=migrate'   # run migrations, then exits
SLUICE_SHOPIFY_WEBHOOK_SECRET=local-dev-secret ./gradlew bootRun   # API on http://localhost:8080
cd ../frontend && npm ci && npm start     # UI on http://localhost:4200, proxies /api to :8080
```

Useful URLs:

| URL | What |
|---|---|
| http://localhost:4200 | Angular app (dev server with hot reload) |
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/health/live, `/health/ready` | Liveness and readiness |

Migrations are a separate step: a normal start only validates the schema and refuses to start if migrations are pending. Run the `migrate` step again after pulling new migrations. The variables in `.env.example` override the local defaults: docker compose reads them from `.env`, while the backend reads them from the environment.

## Build and test

```bash
cd backend && ./gradlew check     # compile, ktlint, detekt, unit + Testcontainers tests, Konsist, OpenAPI contract
cd backend && ./gradlew bootJar   # one jar with the Angular app inside (ADR 0005)

cd frontend && npm run lint && npm run format:check && npm run test:ci && npm run build

docker build -t sluice .          # multi-stage image: Angular → Spring Boot jar → JRE, non-root
```

To run the image locally the same way it runs in production (Postgres → migrations → app, each step waiting for the previous one to be healthy or to finish):

```bash
docker compose --profile app up --build --wait   # app on http://localhost:8080 (stop bootRun first)
docker compose --profile app down
```

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
