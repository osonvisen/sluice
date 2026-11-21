package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.web.ApiPaths
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.LocaleResolver

/**
 * Login as Backend-for-Frontend (ADR 0004): Spring Boot holds the session and the tokens, the browser
 * only gets an HttpOnly session cookie and a CSRF token.
 *
 * - `/api` needs a logged-in user and answers **401** (a ProblemDetail, never a redirect), so Angular
 *   decides when to send the browser to the login page. What a user may do is checked per endpoint
 *   with `@PreAuthorize` on a permission (`EndpointAuthorizationIT` fails for an `/api` endpoint without one).
 * - `/webhooks` needs no login and no CSRF token: the sender is a server, and the HMAC signature protects it.
 * - `/health`, the Angular app (any other path, so deep links work) and the login flow itself are open.
 *
 * Two chains, one per profile: Keycloak login normally, a fixed dev user with `local-noauth`.
 * Both share [applyCommonRules], so the rules that matter (401, CSRF, open paths) are the same in both.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {
    @Bean
    fun problemDetailSecurityErrors(
        @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
        locales: LocaleResolver,
    ) = ProblemDetailSecurityErrors(resolver, locales)

    @Bean
    @Profile("!${Profiles.LOCAL_NOAUTH}")
    fun oidcLoginFilterChain(
        http: HttpSecurity,
        errors: ProblemDetailSecurityErrors,
        registrations: ClientRegistrationRepository,
    ): SecurityFilterChain {
        requireClientSecrets(registrations)
        applyCommonRules(http, errors)
        http
            .oauth2Login { login ->
                login
                    .authorizationEndpoint { it.authorizationRequestResolver(withPkce(registrations)) }
                    .userInfoEndpoint { it.userAuthoritiesMapper(RealmRolesAuthoritiesMapper()) }
            }.logout { it.logoutSuccessHandler(spaOidcLogout(registrations)) }
        return http.build()
    }

    @Bean
    @Profile(Profiles.LOCAL_NOAUTH)
    fun localNoAuthFilterChain(
        http: HttpSecurity,
        errors: ProblemDetailSecurityErrors,
    ): SecurityFilterChain {
        LoggerFactory
            .getLogger(javaClass)
            .warn("Profile {}: login is off, every request is a dev user with all permissions", Profiles.LOCAL_NOAUTH)
        applyCommonRules(http, errors)
        http
            .addFilterBefore(LocalNoAuthFilter(), AnonymousAuthenticationFilter::class.java)
            // There is no identity provider session to end; the client just reloads.
            .logout { it.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)) }
        return http.build()
    }

    private fun applyCommonRules(
        http: HttpSecurity,
        errors: ProblemDetailSecurityErrors,
    ) {
        http
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("${ApiPaths.HEALTH}/**", "${ApiPaths.WEBHOOKS}/**")
                    .permitAll()
                    .requestMatchers("${ApiPaths.API}/**")
                    .authenticated()
                    // Everything else is the Angular app (any path, for deep links), the login flow and,
                    // outside prod, Swagger UI. None of it holds data: data is only ever served under /api.
                    .anyRequest()
                    .permitAll()
            }.csrf { csrf ->
                // spa(): the token in a readable XSRF-TOKEN cookie, checked against the X-XSRF-TOKEN header.
                // Those are the names Angular's HttpClient uses on its own for same-origin requests.
                csrf
                    .spa()
                    .csrfTokenRepository(
                        CookieCsrfTokenRepository.withHttpOnlyFalse().apply { setCookieCustomizer { it.sameSite(SAME_SITE) } },
                    ).ignoringRequestMatchers("${ApiPaths.WEBHOOKS}/**")
            }.addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
            .exceptionHandling { exceptions ->
                exceptions
                    .defaultAuthenticationEntryPointFor(errors, PathPatternRequestMatcher.pathPattern("${ApiPaths.API}/**"))
                    .accessDeniedHandler(errors)
            }
    }

    /**
     * PKCE also for this confidential client: a stolen authorization code is useless without the verifier
     * (OAuth 2.0 Security BCP, RFC 9700). Spring only adds it to public clients by default; the realm requires it.
     */
    private fun withPkce(registrations: ClientRegistrationRepository) =
        DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI).apply {
            setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        }

    /** Fails at startup instead of at the first login, where a missing secret only shows as a failed token request. */
    private fun requireClientSecrets(registrations: ClientRegistrationRepository) {
        (registrations as? Iterable<*>)?.filterIsInstance<ClientRegistration>()?.forEach {
            check(!it.clientSecret.isNullOrBlank()) { "No client secret for '${it.registrationId}'. Set SLUICE_OIDC_CLIENT_SECRET." }
        }
    }

    /**
     * RP-initiated logout (ends the Keycloak session too), shaped for a single-page app: Angular logs out
     * with an XHR, which cannot follow a redirect to another origin. So the logout URL is returned in
     * `Location` with 202, and Angular navigates the whole page there.
     */
    private fun spaOidcLogout(registrations: ClientRegistrationRepository) =
        OidcClientInitiatedLogoutSuccessHandler(registrations).apply {
            setPostLogoutRedirectUri("{baseUrl}")
            setRedirectStrategy { _, response, url ->
                response.status = HttpStatus.ACCEPTED.value()
                response.setHeader(LOCATION, url)
            }
        }

    private companion object {
        const val SAME_SITE = "Lax"
        const val LOCATION = "Location"

        // Spring Security's default; Angular sends the browser to /oauth2/authorization/keycloak on a 401.
        const val AUTHORIZATION_BASE_URI = "/oauth2/authorization"
    }
}
