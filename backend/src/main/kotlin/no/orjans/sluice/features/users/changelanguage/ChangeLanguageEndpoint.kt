package no.orjans.sluice.features.users.changelanguage

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.CurrentUsers
import no.orjans.sluice.web.getOrThrow
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Stores the user's language (ADR 0011). `PUT` with the whole value, so sending it twice is harmless
 * (idempotent); there is nothing to merge, so no `If-Match` either: the last choice wins.
 */
@RestController
@Tag(name = "Me")
class ChangeLanguageEndpoint(
    private val handler: ChangeLanguageHandler,
    private val currentUsers: CurrentUsers,
) {
    @PutMapping("${ApiPaths.API}/me/language", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun changeLanguage(
        @Valid @RequestBody request: ChangeLanguageRequest,
    ): ResponseEntity<Unit> {
        handler.handle(currentUsers.current(), request.language).getOrThrow()
        return ResponseEntity.noContent().build()
    }
}
