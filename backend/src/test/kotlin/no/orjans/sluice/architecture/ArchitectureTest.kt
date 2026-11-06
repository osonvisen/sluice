package no.orjans.sluice.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/** Enforces the package rules from ADR 0001 and ADR 0008 on production code. */
class ArchitectureTest {
    private val productionFiles = Konsist.scopeFromProduction().files

    @Test
    fun `every class lives in features, web, shared or infrastructure`() {
        productionFiles
            .filterNot { it.packageName == BASE }
            .assertTrue { file -> TOP_LEVEL_PACKAGES.any { file.packageName.startsWith("$it.") || file.packageName == it } }
    }

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
    fun `web does not depend on features or infrastructure`() {
        filesIn(WEB).assertTrue { file ->
            file.imports.none { it.name.startsWith(FEATURES) || it.name.startsWith(INFRASTRUCTURE) }
        }
    }

    @Test
    fun `shared does not depend on features, web or infrastructure`() {
        filesIn(SHARED).assertTrue { file ->
            file.imports.none { import -> listOf(FEATURES, WEB, INFRASTRUCTURE).any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun `shared is plain Kotlin without framework, web, database or HTTP client dependencies`() {
        filesIn(SHARED)
            .assertTrue { file -> file.imports.none { import -> FRAMEWORK_PREFIXES.any { import.name.startsWith(it) } } }
    }

    @Test
    fun `rest controllers are endpoint classes named after their use case`() {
        productionFiles
            .flatMap { it.classes() }
            .filter { it.hasAnnotationWithName("RestController") }
            .assertTrue { it.name.endsWith("Endpoint") }
    }

    // getOrThrow() turns an expected error into an exception. That is only acceptable on the HTTP edge
    // (ADR 0008): handlers and domain code must pass Result on, never throw it.
    @Test
    fun `only endpoint classes call getOrThrow`() {
        productionFiles
            .filter { it.text.contains(GET_OR_THROW_CALL) }
            .filterNot { it.packageName == WEB && it.name == "ResultResponses" }
            .assertTrue { file ->
                val classes = file.classes()
                classes.isNotEmpty() && classes.all { it.name.endsWith("Endpoint") && it.hasAnnotationWithName("RestController") }
            }
    }

    @Test
    fun `production code uses jackson 3, jackson 2 is only a springdoc dependency`() {
        productionFiles.assertTrue { file -> file.imports.none { import -> JACKSON_2_PREFIXES.any { import.name.startsWith(it) } } }
    }

    private fun filesIn(packagePrefix: String): List<KoFileDeclaration> =
        productionFiles.filter { it.packageName == packagePrefix || it.packageName.startsWith("$packagePrefix.") }

    private val KoFileDeclaration.packageName: String get() = packagee?.name.orEmpty()

    /**
     * `...features.jobs.retryjob.x` → `jobs.retryjob`. An area's root package (`...features.jobs`) is not
     * a slice: it is the area's public API and may be used from anywhere in features.
     */
    private fun sliceOf(packageName: String): String? {
        if (!packageName.startsWith("$FEATURES.")) return null
        val segments = packageName.removePrefix("$FEATURES.").split('.')
        return if (segments.size >= 2) "${segments[0]}.${segments[1]}" else null
    }

    private companion object {
        const val BASE = "no.orjans.sluice"
        const val FEATURES = "$BASE.features"
        const val WEB = "$BASE.web"
        const val SHARED = "$BASE.shared"
        const val INFRASTRUCTURE = "$BASE.infrastructure"
        const val GET_OR_THROW_CALL = ".getOrThrow()"
        val TOP_LEVEL_PACKAGES = listOf(FEATURES, WEB, SHARED, INFRASTRUCTURE)

        // com.fasterxml.jackson.annotation is not listed: Jackson 3 still uses those annotations.
        val JACKSON_2_PREFIXES = listOf("com.fasterxml.jackson.databind", "com.fasterxml.jackson.core", "com.fasterxml.jackson.module")
        val FRAMEWORK_PREFIXES = listOf("org.springframework", "jakarta.", "java.sql", "javax.sql", "java.net.http")
    }
}
