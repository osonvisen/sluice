package no.orjans.sluice.features.system.appinfo

import io.swagger.v3.oas.annotations.tags.Tag
import no.orjans.sluice.shared.ApiPaths
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Name and version of the running build, shown in the app shell so a screenshot tells which build it came from.
 *
 * Comes from `META-INF/build-info.properties`, which every Gradle build writes (`springBoot { buildInfo() }`).
 */
@RestController
@Tag(name = "System")
class GetAppInfoEndpoint(
    buildProperties: BuildProperties,
) {
    // Checked once at startup, so a broken build fails fast instead of on the first request.
    private val appInfo =
        AppInfoResponse(
            name = checkNotNull(buildProperties.name) { "build-info.properties has no name" },
            version = checkNotNull(buildProperties.version) { "build-info.properties has no version" },
        )

    @GetMapping("${ApiPaths.API}/app-info", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getAppInfo(): AppInfoResponse = appInfo
}

data class AppInfoResponse(
    val name: String,
    val version: String,
)
