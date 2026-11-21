package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.SluiceApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.StandardEnvironment

class LocalNoAuthGuardTest {
    @Test
    fun `local-noauth together with prod is refused`() {
        assertThatThrownBy { guard(Profiles.PROD, Profiles.LOCAL_NOAUTH) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(Profiles.LOCAL_NOAUTH)
    }

    @Test
    fun `either profile alone is fine`() {
        assertThatCode { guard(Profiles.LOCAL_NOAUTH) }.doesNotThrowAnyException()
        assertThatCode { guard(Profiles.PROD) }.doesNotThrowAnyException()
        assertThatCode { guard() }.doesNotThrowAnyException()
    }

    /**
     * The real application, so the test also proves the guard is registered (`META-INF/spring.factories`).
     * No web server and a database that does not exist: if the guard did not stop it, startup would fail
     * later for another reason, and the message check below would catch that.
     */
    @Test
    fun `the application does not start with both profiles`() {
        val args =
            arrayOf(
                "--spring.profiles.active=${Profiles.PROD},${Profiles.LOCAL_NOAUTH}",
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:postgresql://localhost:1/none",
            )

        val failure = runCatching { SpringApplication.run(SluiceApplication::class.java, *args).close() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("must never run with")
    }

    private fun guard(vararg profiles: String) =
        LocalNoAuthGuard().postProcessEnvironment(StandardEnvironment().apply { setActiveProfiles(*profiles) }, SpringApplication())
}
