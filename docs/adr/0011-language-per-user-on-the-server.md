# 0011 – The language choice is stored per user on the server

Status: Accepted · 2026-11-21

Extends ADR 0006 (language support) and relies on ADR 0004 (login). It does not change either.

## Context
ADR 0006 says the language choice is stored per user, falling back to the browser language and then `en`. Until milestone 3 there are no users, so the choice lives only in the browser. Once people log in, the same person on another device or browser should get the same language, and the backend should answer in it (ProblemDetail messages, via `Accept-Language`).

## Options
1. Keep the choice in the browser (`localStorage`) only
2. Store it on the server, per user, in our own table
3. Store it as a claim or attribute in Keycloak

## Decision
Option 2.
- A table `app_users` (`V4__users.sql`) with the OIDC issuer and subject as the key, the language, and timestamps. A row is created on first login.
- `GET /api/me` returns the name, permissions and language. `PUT /api/me/language` changes it.
- The frontend asks `/api/me` after login, uses that language, and sends it as `Accept-Language`. Before login, and for a user with no stored choice on the server, the order is a language choice already saved in the browser, then the browser language, then `en`. A choice saved in the browser before login is therefore kept for a logged-in user until they choose a language on the server.
- The table holds no personal data beyond what the identity provider's subject already identifies, and no e-mail address.

## Consequences
- The choice follows the person across devices, and the backend and frontend agree on the language.
- Keycloak stays swappable (ADR 0004): only a standard OIDC `issuer-uri` is needed, and the app owns its own profile data.
- Option 1 remains as the fallback for logged-out visitors, such as guests in the live demo (milestone 3.5).
- A later setting per user (for example time zone) can go in the same table.
