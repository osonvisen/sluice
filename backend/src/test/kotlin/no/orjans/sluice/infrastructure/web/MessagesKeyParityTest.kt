package no.orjans.sluice.infrastructure.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/** A key missing in one language would silently show English (or nothing) to some users. */
class MessagesKeyParityTest {
    @Test
    fun `english and norwegian messages have the same keys`() {
        val english = load("i18n/messages.properties")
        val norwegian = load("i18n/messages_nb.properties")

        assertThat(norwegian.keys).describedAs("keys in nb compared to en").containsExactlyInAnyOrderElementsOf(english.keys)
    }

    @Test
    fun `no message is blank`() {
        listOf("i18n/messages.properties", "i18n/messages_nb.properties").forEach { file ->
            assertThat(load(file).values.map { it.toString() }).describedAs(file).allSatisfy { assertThat(it).isNotBlank() }
        }
    }

    private fun load(path: String): Properties =
        Properties().apply {
            val stream = checkNotNull(MessagesKeyParityTest::class.java.classLoader.getResourceAsStream(path)) { "Missing $path" }
            stream.reader(Charsets.UTF_8).use { load(it) }
        }
}
