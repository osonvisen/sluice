package no.orjans.sluice.web

/** URL prefixes (ADR 0005). Everything outside these is served by the Angular app. */
object ApiPaths {
    const val API = "/api"
    const val WEBHOOKS = "/webhooks"
    const val MCP = "/mcp"
    const val HEALTH = "/health"
}
