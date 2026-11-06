# 0010 – Three-step job worker with fencing, and idempotent external calls

Status: Accepted · 2026-11-06

Supersedes the "same transaction" rule in ADR 0009 (the handler's writes are committed together with `SUCCEEDED`, in a transaction that is open while the handler runs). ADR 0002's "job state and business data in the same transaction" still holds: it now applies to step 3.

## Context
From milestone 2, the order import calls the ERP over HTTP. In milestone 1 the whole handler ran inside one database transaction. With an HTTP call inside it:
- a database connection and row locks are held for as long as the ERP takes to answer (pool exhaustion, lock waits);
- a crash or a lost lease *after* the ERP has created the order, but *before* our commit, rolls back our side only. The retry would then create a second ERP order.

A transaction cannot cover an external system. What it *can* do is make our own bookkeeping atomic and refuse stale results.

## Decision
The worker runs each job in three steps:

1. **Claim** (short transaction): `RUNNING`, `attempts + 1`, a lease (`locked_until`) and `version + 1`. The version after the claim is the job's **fencing token**: only the holder of that version may finish the job.
2. **Work** (no transaction): the handler reads what it needs, validates and calls external systems. It does not write to our database. It returns a `JobOutcome` that carries **effects**: the database writes to make if the result is accepted (order status, ERP order number, line log).
3. **Finish** (short transaction): a conditional `UPDATE jobs … WHERE id = ? AND status = 'RUNNING' AND version = :fencingToken`. If it updates the row, the effects are applied in the same transaction and committed together with the new status. If it updates nothing, someone else owns the job now (our lease expired and another worker took over): the result and its effects are discarded.

Effects are applied for every outcome, not only for success, so a failed attempt still records what was sent and what came back per order line.

**External calls are idempotent.** Step 2 can run more than once for the same piece of work (crash after the ERP call, lease expired during the call, timeout after the ERP processed the request). Every ERP call therefore sends an `Idempotency-Key` header with a key that identifies the business operation, here the order ID. The ERP must return the original result for a repeated key instead of creating a new order. A request that was rejected (4xx) is not remembered, so it can be sent again after the data or the ERP catalogue is fixed. The fake ERP in the tests (WireMock) implements this contract.

**Timeouts are shorter than the lease.** The ERP client's connect and read timeouts together must be well below the job lease, so a call normally ends before the lease does. The application refuses to start otherwise. The fencing token covers the cases where it does not.

**Backoff has jitter.** The delay before the next attempt is the exponential backoff ± a configurable fraction (`sluice.jobs.backoff-jitter`, default 20 %), so many jobs that failed together do not all retry in the same second. The randomness comes from an injected `RandomGenerator` (like the `Clock`), so tests are deterministic.

## Consequences
- No database connection or lock is held during an ERP call.
- A crash at any point leads to at most one ERP order: before the call (nothing happened), after the call (the retry gets the replayed result), during our commit (rolled back; the retry gets the replayed result).
- A slow worker whose lease expired cannot overwrite the result of the worker that took over.
- Handlers are split into "decide" (step 2, no writes) and "record" (effects, step 3). A handler that writes to the database directly in step 2 would break the guarantee; the handler interface makes the effects the only place for writes.
- The guarantee is only as good as the external system's idempotency. An ERP without idempotency keys would need a "look up before create" step instead.
