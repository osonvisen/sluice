-- Milestone 1: generic job queue (ADR 0002, ADR 0009) and incoming orders.
-- All times are UTC (timestamptz). Values come from the app's injected Clock, not from now(),
-- so tests can control time.

CREATE TABLE jobs (
    id              uuid PRIMARY KEY,
    type            text        NOT NULL,
    payload         jsonb       NOT NULL,
    status          text        NOT NULL,
    dead_reason     text,
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL,
    next_run_at     timestamptz NOT NULL,
    locked_until    timestamptz,
    last_error      text,
    -- Final guard against duplicates: the same business event can only create one job.
    idempotency_key text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT jobs_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT jobs_status_ck CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')),
    CONSTRAINT jobs_dead_reason_ck CHECK (dead_reason IN ('PERMANENT', 'EXHAUSTED')),
    -- Only DEAD has a reason, and DEAD always has one (ADR 0009).
    CONSTRAINT jobs_dead_reason_only_when_dead_ck CHECK ((status = 'DEAD') = (dead_reason IS NOT NULL)),
    -- A lease exists exactly while the job is RUNNING.
    CONSTRAINT jobs_lease_only_when_running_ck CHECK ((status = 'RUNNING') = (locked_until IS NOT NULL)),
    CONSTRAINT jobs_attempts_ck CHECK (attempts >= 0 AND max_attempts > 0 AND attempts <= max_attempts)
);

-- The worker's two queries: due jobs, and RUNNING jobs whose lease has expired.
CREATE INDEX jobs_due_ix ON jobs (next_run_at) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX jobs_expired_lease_ix ON jobs (locked_until) WHERE status = 'RUNNING';
-- The overview in milestone 3 filters on status, newest first.
CREATE INDEX jobs_status_created_ix ON jobs (status, created_at DESC);

-- One row per attempt, so the full history survives retries (ADR 0009).
CREATE TABLE job_attempts (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id      uuid        NOT NULL REFERENCES jobs (id),
    attempt     integer     NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    -- NULL while the attempt is running.
    outcome     text,
    error       text,
    CONSTRAINT job_attempts_outcome_ck
        CHECK (outcome IN ('SUCCEEDED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'LEASE_EXPIRED')),
    CONSTRAINT job_attempts_finished_ck CHECK ((outcome IS NULL) = (finished_at IS NULL))
);

CREATE INDEX job_attempts_job_ix ON job_attempts (job_id, id);

-- Orders received from sales channels. Data minimisation: only what the ERP needs (no customer
-- name, e-mail, phone or address). The raw webhook payload is never stored.
CREATE TABLE orders (
    id                uuid PRIMARY KEY,
    channel           text        NOT NULL,
    shop_domain       text        NOT NULL,
    external_order_id bigint      NOT NULL,
    order_number      text        NOT NULL,
    currency          char(3)     NOT NULL,
    ordered_at        timestamptz NOT NULL,
    status            text        NOT NULL,
    -- The delivery that brought the order (X-Shopify-Webhook-Id), for tracing.
    webhook_id        text        NOT NULL,
    import_job_id     uuid        NOT NULL REFERENCES jobs (id),
    received_at       timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    updated_at        timestamptz NOT NULL,
    -- Business idempotency: one order per shop and Shopify order id, whatever the delivery.
    CONSTRAINT orders_external_uq UNIQUE (channel, shop_domain, external_order_id),
    CONSTRAINT orders_channel_ck CHECK (channel IN ('SHOPIFY')),
    -- RECEIVED → ACCEPTED by the import job. A rejected order is visible through its job (DEAD, PERMANENT).
    CONSTRAINT orders_status_ck CHECK (status IN ('RECEIVED', 'ACCEPTED'))
);

CREATE TABLE order_lines (
    order_id         uuid          NOT NULL REFERENCES orders (id),
    line_number      integer       NOT NULL,
    external_line_id bigint        NOT NULL,
    -- As received. May be missing or invalid; the import job rejects the order then (milestone 2: per line).
    sku              text,
    title            text          NOT NULL,
    quantity         integer       NOT NULL,
    -- Money: numeric, never float. Scale 4 covers every ISO currency.
    unit_price       numeric(19, 4) NOT NULL,
    PRIMARY KEY (order_id, line_number),
    CONSTRAINT order_lines_quantity_ck CHECK (quantity > 0),
    CONSTRAINT order_lines_unit_price_ck CHECK (unit_price >= 0)
);
