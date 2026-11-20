package no.orjans.sluice.shared

/**
 * A language the app is translated into (ADR 0006): English (the default and fallback) and Norwegian Bokmål.
 *
 * The one list of supported languages on the server. Adding one means a translation file in the
 * frontend and the backend, and a tag here.
 */
@JvmInline
value class Language private constructor(
    val tag: String,
) {
    override fun toString(): String = tag

    companion object {
        val ENGLISH = Language("en")
        val NORWEGIAN_BOKMAL = Language("nb")

        val SUPPORTED: List<Language> = listOf(ENGLISH, NORWEGIAN_BOKMAL)
        val DEFAULT: Language = ENGLISH

        val UNSUPPORTED_LANGUAGE = ErrorCode("unsupported-language")

        /** Exact tags only (`nb`, not `nb-NO` or `NB`): the client picks from the list it was given. */
        fun parse(
            raw: String,
            field: String,
        ): Result<Language> =
            SUPPORTED.firstOrNull { it.tag == raw }?.let { Result.success(it) }
                ?: Result.failure(AppError.Validation(listOf(FieldViolation(field, UNSUPPORTED_LANGUAGE))))

        /** For values the app wrote itself (the database); an unknown tag there is treated as no choice. */
        fun ofStoredOrNull(tag: String?): Language? = SUPPORTED.firstOrNull { it.tag == tag }
    }
}
