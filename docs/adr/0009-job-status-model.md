# 0009 – Job status model: FAILED means "will retry", DEAD means "gave up"

Status: Accepted · 2026-11-06

Clarifies ADR 0002, which is ambiguous: it lists `PENDING → RUNNING → SUCCEEDED | FAILED → DEAD`, but also says permanent errors "go straight to FAILED".

## Context
Operators (milestone 3) must be able to tell at a glance whether a job needs them. A job that will retry by itself needs nobody. A job that has given up needs a decision, and the reason it gave up decides which one: fix the data, or wait for the external system and rerun.

## Decision

| Status | Meaning | Next |
|---|---|---|
| `PENDING` | Waiting for its first run, or rerun manually | `RUNNING` when `next_run_at` has passed |
| `RUNNING` | Claimed by a worker until `locked_until` | `SUCCEEDED`, `FAILED` or `DEAD` |
| `SUCCEEDED` | Done | — |
| `FAILED` | The last attempt failed with a **temporary** error; a new attempt is planned at `next_run_at` | `RUNNING` |
| `DEAD` | **Gave up.** Needs a person | `PENDING` via manual retry |

`DEAD` always has a `dead_reason`, and only `DEAD` has one (a check constraint guards this):
- `PERMANENT` – the handler reported an error that a retry cannot fix (missing SKU, unknown job type, unreadable payload).
- `EXHAUSTED` – temporary errors until `max_attempts` was reached.

Further rules:
- **Outcome from the handler.** A `JobHandler` returns `Succeeded`, `TemporaryFailure` or `PermanentFailure`. An unexpected exception counts as temporary, so a bug ends up `EXHAUSTED` rather than retried forever.
- **Backoff.** Exponential with a cap, configured in `sluice.jobs`.
- **Same transaction.** The handler's database writes are committed together with `SUCCEEDED`. On any other outcome they are rolled back, and the failure is recorded in a new transaction (ADR 0002: no dual write).
- **Crashed workers.** A claim sets `locked_until` (a lease). A `RUNNING` job whose lease has expired is treated as a failed attempt (`FAILED`, or `DEAD`/`EXHAUSTED` if it was the last one), so it is never stuck.
- **History.** Every attempt is a row in `job_attempts` (start, end, outcome, error), so the detail page can show the whole story.
- **Manual retry** (`POST /api/jobs/{id}/retry`) is allowed only from `FAILED` and `DEAD`. It sets `PENDING`, resets `attempts` and clears `dead_reason`. It requires `If-Match` with the job's version: 428 if the header is missing, 412 if the version is stale. The status check is a conditional `UPDATE … WHERE status IN ('FAILED','DEAD')`, and 0 updated rows gives 409 `job-not-retryable`, so two concurrent retries cannot both succeed.

## Consequences
- The milestone 3 overview can filter on `DEAD` for "needs attention", and show *why* from `dead_reason`.
- `FAILED` jobs need no action; showing them is information, not an alarm.
- ADR 0002's "permanent errors go straight to FAILED" now reads "straight to `DEAD` with `dead_reason = PERMANENT`".
- ADR numbers are no longer reserved in advance. The OIDC-in-the-cloud decision (milestone 6) gets the next free number when it is written. This replaces the numbering notes in ADR 0007 and ADR 0008.
