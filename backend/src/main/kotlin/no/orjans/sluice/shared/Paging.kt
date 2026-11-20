package no.orjans.sluice.shared

/**
 * Offset pagination, the same for every list (api-and-data standard): `?page=0&size=20`, where `page`
 * is 0-based like Angular Material's paginator. Out-of-range values are a validation error, never
 * silently clamped, so a client bug shows up instead of returning the wrong page.
 *
 * Offset paging re-reads the skipped rows, which is fine for an operator's overview (thousands of rows).
 * A list that grows to millions, or is read by machines page by page, should use keyset paging instead.
 */
@ConsistentCopyVisibility
data class PageRequest private constructor(
    val page: Int,
    val size: Int,
) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        // Far beyond any real list; keeps page × size from overflowing and OFFSET from scanning forever.
        private const val MAX_PAGE = 10_000

        private val TOO_SMALL = ErrorCode("min")
        private val TOO_LARGE = ErrorCode("max")

        fun of(
            page: Int,
            size: Int,
        ): Result<PageRequest> {
            val violations =
                listOfNotNull(
                    when {
                        page < 0 -> FieldViolation("page", TOO_SMALL)
                        page > MAX_PAGE -> FieldViolation("page", TOO_LARGE)
                        else -> null
                    },
                    when {
                        size < 1 -> FieldViolation("size", TOO_SMALL)
                        size > MAX_SIZE -> FieldViolation("size", TOO_LARGE)
                        else -> null
                    },
                )
            return if (violations.isEmpty()) Result.success(PageRequest(page, size)) else Result.failure(AppError.Validation(violations))
        }
    }
}

/** One page of a list. [total] is the number of matching rows across all pages, so the client can show "21–40 of 135". */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun <T> of(
            request: PageRequest,
            items: List<T>,
            total: Long,
        ) = Page(items, request.page, request.size, total)
    }
}
