package no.orjans.sluice.features.orders

/** Stored as text; the database has a matching check constraint. */
enum class OrderStatus {
    /** Stored from the webhook; the import job has not finished yet. */
    RECEIVED,

    /** Milestone 1: validated but never sent to the ERP. Kept only for old rows (V3 migration); never written. */
    ACCEPTED,

    /** The ERP has the order; `erp_order_number` is set. */
    IMPORTED,

    /** Invalid lines, or the ERP refused it. The job (DEAD, PERMANENT) and the line log say why. */
    REJECTED,
}
