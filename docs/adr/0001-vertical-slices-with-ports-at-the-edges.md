# 0001 – Vertical slices with ports at the edges, single Gradle module

Status: Accepted · 2026-11-06

## Context
Sluice is a small integration platform. It has many independent features (import order, retry job, update product, approve request) and a few external systems (webshop, ERP, AI agents via MCP). The domain logic is moderate: job states, retry rules, GTIN validation, completeness scoring, and approval rules.

## Options
1. Clean Architecture with separate Gradle modules (domain, application, infrastructure, api)
2. Classic layers (controller → service → repository)
3. Vertical slices, with ports & adapters only towards external systems

## Decision
Option 3. Everything lives in **one Gradle module (backend)**, organised as follows:

```
backend/src/main/kotlin/no/orjans/sluice/
  features/
    jobs/        (queue, worker, retry-job, list-jobs, ...)
    orders/      (import-order from webhook, map-to-erp, ...)
    products/    (update-product, validate-gtin, completeness, ...)
    approvals/   (propose-change, approve, reject, ...)
    mcp/         (MCP tools that call into the slices)
  shared/        (Result, typed IDs, value objects, errors, pagination, ETag, idempotency, clock)
  infrastructure/(db config, ERP client, Shopify HMAC, security config)
```

- Each slice owns its endpoint, request/response types, handler, validation, SQL and tests.
- External systems sit behind **ports** (`ErpOrderSender`, `OrderSource`, ...) with adapters in `infrastructure/`.
- Value objects and domain rules live in `shared/` or inside the feature that owns them.
- **Konsist tests** enforce that slices don't use each other's internals, and that `shared/` and the domain rules don't depend on Spring Web, JDBC or HTTP clients.

## Consequences
- A new feature takes few files, and everything for one feature sits in one place, which makes it easy to review.
- Some duplication between slices is accepted.
- If the project grows, a feature can be promoted to a module with a public API without restructuring everything else.
