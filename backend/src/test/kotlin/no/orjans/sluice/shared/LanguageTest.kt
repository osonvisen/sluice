package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LanguageTest {
    @Test
    fun `english and norwegian bokmal are supported, english is the default`() {
        assertThat(Language.SUPPORTED.map { it.tag }).containsExactly("en", "nb")
        assertThat(Language.DEFAULT).isEqualTo(Language.ENGLISH)
    }

    @Test
    fun `parse accepts exact supported tags only`() {
        assertThat(Language.parse("nb", "language").valueOrNull()).isEqualTo(Language.NORWEGIAN_BOKMAL)
        listOf("de", "nb-NO", "NB", "", "en ").forEach { tag ->
            assertThat(Language.parse(tag, "language")).describedAs(tag).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `an unknown stored value counts as no choice`() {
        assertThat(Language.ofStoredOrNull("nb")).isEqualTo(Language.NORWEGIAN_BOKMAL)
        assertThat(Language.ofStoredOrNull("sv")).isNull()
        assertThat(Language.ofStoredOrNull(null)).isNull()
    }
}
