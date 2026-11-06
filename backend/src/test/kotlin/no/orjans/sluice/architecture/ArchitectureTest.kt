package no.orjans.sluice.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/** Enforces the slice rules from ADR 0001 on production code. */
class ArchitectureTest {
    private val productionFiles = Konsist.scopeFromProduction().files

    @Test
    fun `a slice does not use another slice's internals`() {
        filesIn(FEATURES).assertTrue { file ->
            val ownSlice = sliceOf(file.packageName)
            file.imports.none { import ->
                val importedSlice = sliceOf(import.name.substringBeforeLast('.'))
                importedSlice != null && importedSlice != ownSlice
            }
        }
    }

    @Test
    fun `features do not depend on infrastructure, infrastructure implements their ports`() {
        filesIn(FEATURES).assertTrue { file -> file.imports.none { it.name.startsWith(INFRASTRUCTURE) } }
    }

    @Test
    fun `shared does not depend on features or infrastructure`() {
        filesIn(SHARED).assertTrue { file ->
            file.imports.none { it.name.startsWith(FEATURES) || it.name.startsWith(INFRASTRUCTURE) }
        }
    }

    @Test
    fun `shared is plain Kotlin without framework, web, database or HTTP client dependencies`() {
        filesIn(SHARED)
            .assertTrue { file -> file.imports.none { import -> FRAMEWORK_PREFIXES.any { import.name.startsWith(it) } } }
    }

    @Test
    fun `production code uses jackson 3, jackson 2 is only a springdoc dependency`() {
        productionFiles.assertTrue { file -> file.imports.none { import -> JACKSON_2_PREFIXES.any { import.name.startsWith(it) } } }
    }

    private fun filesIn(packagePrefix: String): List<KoFileDeclaration> =
        productionFiles.filter { it.packageName.startsWith(packagePrefix) }

    private val KoFileDeclaration.packageName: String get() = packagee?.name.orEmpty()

    /** `...features.jobs.retryjob.x` → `jobs.retryjob`. Area-level packages (`...features.jobs`) are shared within the area. */
    private fun sliceOf(packageName: String): String? {
        if (!packageName.startsWith("$FEATURES.")) return null
        val segments = packageName.removePrefix("$FEATURES.").split('.')
        return if (segments.size >= 2) "${segments[0]}.${segments[1]}" else null
    }

    private companion object {
        const val BASE = "no.orjans.sluice"
        const val FEATURES = "$BASE.features"
        const val INFRASTRUCTURE = "$BASE.infrastructure"
        const val SHARED = "$BASE.shared"

        // com.fasterxml.jackson.annotation is not listed: Jackson 3 still uses those annotations.
        val JACKSON_2_PREFIXES = listOf("com.fasterxml.jackson.databind", "com.fasterxml.jackson.core", "com.fasterxml.jackson.module")
        val FRAMEWORK_PREFIXES = listOf("org.springframework", "jakarta.", "java.sql", "javax.sql", "java.net.http")
    }
}
