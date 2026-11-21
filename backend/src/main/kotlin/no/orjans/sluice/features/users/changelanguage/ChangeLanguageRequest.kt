package no.orjans.sluice.features.users.changelanguage

import jakarta.validation.constraints.NotBlank

data class ChangeLanguageRequest(
    /** One of the supported language tags, e.g. `nb`. */
    @field:NotBlank
    val language: String,
)
