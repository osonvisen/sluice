package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PagingTest {
    @Test
    fun `a page request in range is accepted and knows its offset`() {
        val request = PageRequest.of(page = 3, size = 20).valueOrNull()

        assertThat(request?.offset).isEqualTo(60L)
    }

    @Test
    fun `negative page, empty or oversized page size are validation errors per field`() {
        assertThat(violations(-1, 20)).containsExactly("page:min")
        assertThat(violations(0, 0)).containsExactly("size:min")
        assertThat(violations(0, PageRequest.MAX_SIZE + 1)).containsExactly("size:max")
        assertThat(violations(-1, 0)).containsExactly("page:min", "size:min")
    }

    @Test
    fun `an absurd page number is rejected before it reaches the database`() {
        assertThat(violations(Int.MAX_VALUE, PageRequest.MAX_SIZE)).containsExactly("page:max")
    }

    @Test
    fun `the largest allowed page cannot overflow the offset`() {
        assertThat(PageRequest.of(10_000, PageRequest.MAX_SIZE).valueOrNull()?.offset).isEqualTo(1_000_000L)
    }

    private fun violations(
        page: Int,
        size: Int,
    ): List<String> =
        when (val result = PageRequest.of(page, size)) {
            is Result.Success -> emptyList()
            is Result.Failure -> (result.error as AppError.Validation).violations.map { "${it.field}:${it.code}" }
        }
}
