package no.orjans.sluice.features.orders

/** Stored as text; the database has a matching check constraint. */
enum class OrderStatus {
    /** Stored from the webhook; the import job has not run yet. */
    RECEIVED,

    /** Validated by the import job and ready for the ERP (sent there from milestone 2). */
    ACCEPTED,
}
