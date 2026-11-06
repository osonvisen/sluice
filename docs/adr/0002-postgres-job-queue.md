# 0002 – Job queue in Postgres (SKIP LOCKED) instead of Kafka

Status: Accepted · 2026-11-06

## Context
Every incoming flow (orders, product updates, approved changes) must run as a job. Each job needs persistent state, retries with backoff, a dead-letter state, manual reruns and full history, in the same way breeze works.

## Options
1. Kafka (or RabbitMQ) as the transport, with job state in Postgres
2. A job table in Postgres that workers poll with `SELECT … FOR UPDATE SKIP LOCKED`
3. A library (db-scheduler, JobRunr)

## Decision
Option 2. A generic `jobs` table, a `JobHandler<T>` interface per job type, and a worker that claims jobs using `FOR UPDATE SKIP LOCKED`.
- **Statuses:** `PENDING → RUNNING → SUCCEEDED | FAILED → DEAD`, plus `attempts`, `next_run_at`, `last_error`, `idempotency_key` (unique) and `version`.
- Only temporary errors are retried. Permanent errors go straight to `FAILED` with an explanation.

## Consequences
- Job state and business data are written in **the same transaction**, so there is no dual-write problem between the database and a message broker.
- One less service to run, which matters on 8 GB of RAM and on Cloud Run.
- Several worker instances can run in parallel without picking up the same job.
- The limit is throughput: thousands of jobs per minute are fine, but millions are not. The README explains when Kafka would pay off (many consumers, event streams, very high volume), and that is an optional bonus milestone.
- We build the queue ourselves rather than using a library, because it is the core of the project and something to explain in an interview.
