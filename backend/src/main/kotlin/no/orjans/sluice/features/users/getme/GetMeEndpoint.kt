package no.orjans.sluice.features.users.getme

import io.swagger.v3.oas.annotations.tags.Tag
import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.CurrentUsers
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Who is logged in, what they may do and the language they chose (ADR 0011). The Angular app asks this
 * first: a 401 sends it to the login page, and the permissions decide which actions it shows.
 */
@RestController
@Tag(name = "Me")
class GetMeEndpoint(
    private val handler: GetMeHandler,
    private val currentUsers: CurrentUsers,
) {
    // Any logged-in user, also one without permissions: the app must be able to say "you have no access".
    @GetMapping("${ApiPaths.API}/me", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun getMe(): MeResponse = handler.handle(currentUsers.current())
}
