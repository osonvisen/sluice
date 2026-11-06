package no.orjans.sluice.shared

/** Stock keeping unit: the product code shared by the webshop, the warehouse and the ERP. */
@JvmInline
value class Sku private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 64
        val INVALID_SKU = ErrorCode("invalid-sku")

        // Letters, digits and the separators webshops commonly use. No spaces, so "ABC 1" and "ABC1" can't collide.
        private val ALLOWED = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")

        fun parse(
            raw: String?,
            field: String = "sku",
        ): Result<Sku> {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.length in 1..MAX_LENGTH && ALLOWED.matches(trimmed)) {
                Result.success(Sku(trimmed))
            } else {
                Result.failure(AppError.Validation(listOf(FieldViolation(field, INVALID_SKU))))
            }
        }
    }
}
