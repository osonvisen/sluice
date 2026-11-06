package no.orjans.sluice.shared

import java.math.BigDecimal
import java.util.Currency

/**
 * An amount in a currency. BigDecimal, never double (rounding errors on money are bugs, not noise).
 *
 * The amount is kept at the currency's number of decimals (NOK: 2), so 199 and 199.00 are equal.
 * Negative amounts are not allowed: refunds and discounts get their own types when needed.
 *
 * [ConsistentCopyVisibility] makes `copy()` private like the constructor, so an invalid Money
 * can't be made through `copy()` either.
 */
@ConsistentCopyVisibility
data class Money private constructor(
    val amount: BigDecimal,
    val currency: Currency,
) {
    override fun toString(): String = "${amount.toPlainString()} ${currency.currencyCode}"

    companion object {
        val INVALID_AMOUNT = ErrorCode("invalid-amount")
        val INVALID_CURRENCY = ErrorCode("invalid-currency")

        fun of(
            amount: BigDecimal,
            currencyCode: String,
            field: String = "amount",
        ): Result<Money> {
            val currency = currencyOrNull(currencyCode)
            // Reject rather than round: an amount with more decimals than the currency has is a data error.
            val scaled = currency?.let { scaledOrNull(amount, it) }
            return when {
                currency == null -> Result.failure(AppError.Validation(listOf(FieldViolation("currency", INVALID_CURRENCY))))
                amount.signum() < 0 || scaled == null -> Result.failure(AppError.Validation(listOf(FieldViolation(field, INVALID_AMOUNT))))
                else -> Result.success(Money(scaled, currency))
            }
        }

        private fun currencyOrNull(code: String): Currency? =
            try {
                Currency.getInstance(code)
            } catch (_: IllegalArgumentException) {
                null
            }

        /** Null for currencies without a fixed number of decimals (e.g. gold, XAU) and for amounts that would need rounding. */
        private fun scaledOrNull(
            amount: BigDecimal,
            currency: Currency,
        ): BigDecimal? {
            if (currency.defaultFractionDigits < 0) return null
            return try {
                amount.setScale(currency.defaultFractionDigits)
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}
