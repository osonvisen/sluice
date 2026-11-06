import dev.detekt.gradle.plugin.getSupportedKotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    jacoco
}

group = "no.orjans"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.springdoc.webmvc.ui)
    // swagger-core (inside springdoc) still uses Jackson 2. With its Kotlin module, non-null Kotlin
    // properties become `required` in the spec, so the generated TypeScript types are not all optional.
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.konsist)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        allWarningsAsErrors = true
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Coverage is measured on every test run and uploaded by CI (testing standard). No threshold yet:
// with almost no domain code, a percentage would say little.
tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// --- Quality --------------------------------------------------------------------------

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

// The Spring dependency-management plugin would otherwise force Boot's Kotlin version onto
// detekt's own classpath, and detekt refuses to run with a Kotlin version it was not built with.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(getSupportedKotlinVersion())
        }
    }
}

ktlint {
    version = libs.versions.ktlint.cli
}

// --- Frontend inside the jar (ADR 0005) ----------------------------------------------

val frontendDir = layout.projectDirectory.dir("../frontend")
val frontendDist = frontendDir.dir("dist/browser")

// Docker builds the frontend in its own stage and copies dist/ in, so it can skip npm here.
val skipFrontendBuild = providers.gradleProperty("skipFrontendBuild").isPresent

val npmCi =
    tasks.register<Exec>("npmCi") {
        group = "frontend"
        description = "Installs frontend dependencies."
        onlyIf { !skipFrontendBuild }
        workingDir(frontendDir)
        commandLine("npm", "ci")
        inputs.file(frontendDir.file("package-lock.json"))
        outputs.dir(frontendDir.dir("node_modules"))
    }

val frontendBuild =
    tasks.register<Exec>("frontendBuild") {
        group = "frontend"
        description = "Builds the Angular app (including the generated API client)."
        onlyIf { !skipFrontendBuild }
        dependsOn(npmCi)
        workingDir(frontendDir)
        commandLine("npm", "run", "build")
        inputs.dir(frontendDir.dir("src"))
        inputs.dir(frontendDir.dir("public"))
        inputs.dir(frontendDir.dir("openapi"))
        inputs.files(frontendDir.files("package.json", "package-lock.json", "angular.json", "tsconfig.json", "tsconfig.app.json"))
        outputs.dir(frontendDist)
    }

tasks.bootJar {
    dependsOn(frontendBuild)
    into("BOOT-INF/classes/static") {
        from(frontendDist)
    }
}

// --- OpenAPI contract ----------------------------------------------------------------

// OpenApiContractIT fails when the live spec differs from frontend/openapi/sluice-api.json.
// Regenerate it with: ./gradlew test --tests '*OpenApiContractIT' -PupdateOpenApi
tasks.test {
    systemProperty("sluice.openapi.contract", frontendDir.file("openapi/sluice-api.json").asFile.absolutePath)
    systemProperty("sluice.openapi.update", providers.gradleProperty("updateOpenApi").isPresent)
    // files() rather than file(): the spec may not exist yet before the first -PupdateOpenApi run.
    inputs.files(frontendDir.file("openapi/sluice-api.json")).withPropertyName("openApiContract")
}

// --- Build info (shown by GET /api/app-info) ------------------------------------------

springBoot {
    buildInfo {
        // Leaving out the timestamp keeps the build reproducible and cacheable.
        excludes = setOf("time")
    }
}
