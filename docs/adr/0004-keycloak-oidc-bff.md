# 0004 – Login with Keycloak (OIDC), Spring Boot as BFF

Status: Accepted · 2026-11-06

## Context
The approval queue must show who proposed and who approved each change. AI agents (MCP) must authenticate separately from people. The solution should look like a real production setup, but the dev machine has only 8 GB of RAM.

## Options
1. Plain Spring Security with in-memory demo users
2. Keycloak as an OIDC provider
3. No login

## Decision
Option 2, using the **Backend-for-Frontend** pattern:
- **People:** Spring Security `oauth2Login` against Keycloak. The session lives in Spring Boot, and the browser only receives a session cookie (HttpOnly, SameSite) and a CSRF token. Tokens never reach the browser. Angular calls the API on the same origin.
- **MCP agents:** the client credentials flow against Keycloak. The `/mcp` endpoint is an OAuth2 resource server that validates JWTs.
- **Webhooks** (`/webhooks/**`): no login. They are verified with HMAC instead.
- **Roles from Keycloak:** `operator` (view, rerun jobs, propose changes) and `approver` (approve/reject). These map to *permissions* in the code (`jobs.retry`, `approvals.decide`). A user cannot approve their own proposal.
- **Realm export** (`keycloak/realm-sluice.json`) holds the demo users, roles and the MCP client. It is imported automatically at startup.
- The app depends only on a standard OIDC `issuer-uri`, so the provider can be swapped through configuration.

## RAM measures
- Keycloak runs in its own Docker Compose **profile** (`docker compose --profile auth up`). It does not start by default.
- Run in dev mode with a memory limit (`mem_limit: 768m`, `JAVA_OPTS_KC_HEAP="-Xmx512m"`).
- **Tests never use Keycloak.** They use `spring-security-test` (`oidcLogin()`, `jwt()`).
- A `local-noauth` profile with a fixed dev user is available for working on things other than auth. It can never be enabled in the `prod` profile, and a test checks this.

## Consequences
- Production-like login, BFF and service-to-service auth: strong interview topics.
- Running Keycloak in the cloud costs money. For the demo deploy (milestone 6) we choose between a Keycloak container on Cloud Run with min-instances 0 or a managed OIDC provider. Because only `issuer-uri` is configured, no code changes are needed.
