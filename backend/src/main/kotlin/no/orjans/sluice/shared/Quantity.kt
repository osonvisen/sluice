package no.orjans.sluice.shared

/** A number of items on an order line. Always at least 1; whole units only (no fractional items). */
@JvmInline
value class Quantity private constructor(
    val value: Int,
) {
    override fun toString(): String = value.toString()

    companion object {
        val INVALID_QUANTITY = ErrorCode("invalid-quantity")

        fun of(
            value: Int,
            field: String = "quantity",
        ): Result<Quantity> =
            if (value >= 1) {
                Result.success(Quantity(value))
            } else {
                Result.failure(AppError.Validation(listOf(FieldViolation(field, INVALID_QUANTITY))))
            }
    }
}
