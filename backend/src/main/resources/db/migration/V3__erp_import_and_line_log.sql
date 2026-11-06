-- Milestone 2: orders are sent to the ERP, with a log per order line and attempt.
-- Expand only (api-and-data standard): nothing is removed or renamed, so the milestone 1 version
-- of the app can still run against this schema during a rollout or rollback.

-- RECEIVED → IMPORTED (the ERP confirmed it) or REJECTED (invalid lines, or the ERP said no).
-- ACCEPTED (milestone 1: validated, not sent) is still allowed for old rows; no new code writes it.
-- Contract step (drop ACCEPTED) in a later migration, once no app version writes it.
ALTER TABLE orders DROP CONSTRAINT orders_status_ck;
ALTER TABLE orders ADD CONSTRAINT orders_status_ck CHECK (status IN ('RECEIVED', 'ACCEPTED', 'IMPORTED', 'REJECTED'));

ALTER TABLE orders ADD COLUMN erp_order_number text;
ALTER TABLE orders ADD COLUMN imported_at timestamptz;
ALTER TABLE orders ADD CONSTRAINT orders_imported_ck
    CHECK ((status = 'IMPORTED') = (erp_order_number IS NOT NULL AND imported_at IS NOT NULL));

-- What was sent to the ERP for each order line, what came back, and errors at line level.
-- One row per line per attempt, so a retried order shows its whole history.
CREATE TABLE job_line_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_attempt_id bigint      NOT NULL REFERENCES job_attempts (id),
    order_id       uuid        NOT NULL,
    line_number    integer     NOT NULL,
    outcome        text        NOT NULL,
    -- The line as sent to the ERP; NULL if it was not sent (invalid locally).
    sent           jsonb,
    -- The ERP's answer for the line; NULL if there was none (timeout, 5xx).
    received       jsonb,
    error_code     text,
    error_message  text,
    logged_at      timestamptz NOT NULL,
    FOREIGN KEY (order_id, line_number) REFERENCES order_lines (order_id, line_number),
    CONSTRAINT job_line_log_outcome_ck
        CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'ORDER_REJECTED', 'INVALID', 'NO_RESPONSE')),
    -- Every outcome but ACCEPTED and ORDER_REJECTED explains itself with a code.
    CONSTRAINT job_line_log_error_ck
        CHECK ((outcome IN ('ACCEPTED', 'ORDER_REJECTED')) = (error_code IS NULL))
);

CREATE INDEX job_line_log_order_ix ON job_line_log (order_id, line_number, id);
CREATE INDEX job_line_log_attempt_ix ON job_line_log (job_attempt_id);
