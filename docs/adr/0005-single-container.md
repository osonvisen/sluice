# 0005 – One container: Angular served by Spring Boot

Status: Accepted · 2026-11-06

## Context
The app has an Angular frontend and a Spring Boot backend. The BFF login (ADR 0004) requires the frontend and API to share an origin.

## Decision
- **Production:** the Gradle build runs the Angular build and copies `dist/` into `static/` in the Spring Boot jar. The result is one multi-stage Docker image (build stage → JRE runtime, non-root, pinned base image) and one Cloud Run service. Spring forwards unknown non-API paths to `index.html` so Angular routing works.
- **Locally:** `ng serve` with `proxy.conf.json` against Spring Boot on `:8080`, which gives hot reload without CORS.
- **API paths:** all endpoints live under `/api/**`, webhooks under `/webhooks/**`, and MCP under `/mcp`.

## Consequences
- One service to deploy, monitor and roll back. No CORS. The session cookie works without extra setup.
- Frontend and backend are always deployed together. This is fine for a single product owned by one team.
