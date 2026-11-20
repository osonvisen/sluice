-- Milestone 3: indexes for the job overview (GET /api/jobs). Expand only.

-- Unfiltered list, newest first. jobs_status_created_ix (V2) only helps when a status is chosen.
CREATE INDEX jobs_created_ix ON jobs (created_at DESC, id DESC);

-- Search by order number, and the job → order join. Each order has exactly one import job; the unique
-- index states that and keeps the list from showing a job twice.
CREATE INDEX orders_order_number_ix ON orders (order_number);
CREATE UNIQUE INDEX orders_import_job_uq ON orders (import_job_id);
