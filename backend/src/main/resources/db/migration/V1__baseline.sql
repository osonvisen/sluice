-- Baseline for the Sluice schema: marks the starting point of the migration history.
-- The first tables (jobs) arrive in milestone 1 as V2.
-- Applied migrations are never edited; every change is a new V<n>__<description>.sql (ADR 0003).
SELECT 1;
