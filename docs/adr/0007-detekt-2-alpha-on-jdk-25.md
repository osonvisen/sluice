# 0007 – detekt 2.0 alpha, because detekt 1.x does not run on JDK 25

Status: Accepted · 2026-11-06

Supplements ADR 0006 (which lists detekt as a quality tool, and says versions are picked as the newest compatible combination).

## Context
ADR 0006 puts detekt in the quality toolchain, and the project runs on JDK 25 (LTS). The latest stable detekt, 1.23.8, does not work on JDK 25:
- it only accepts `--jvm-target` up to 22, and
- even with the target forced to 22 and the Gradle daemon pinned to JDK 25, it crashes while parsing the JVM version string (`25.0.4.1`).

The detekt 1.x line is in maintenance; detekt 2.0 (new plugin ID `dev.detekt`) is where JDK and Kotlin support is being added.

## Options
1. detekt 2.0 alpha (`2.0.0-alpha.6`)
2. Drop detekt and rely on ktlint, Konsist and the compiler (`allWarningsAsErrors`)
3. Run detekt 1.23.8 on a separate JDK 21 toolchain (`JavaExec` + extra JDK locally and in CI)

## Decision
Option 1.
- The exact version (`2.0.0-alpha.6`) is pinned in `backend/gradle/libs.versions.toml`, never a range.
- Kotlin compiler warnings are errors (`allWarningsAsErrors = true`), so the compiler covers part of what detekt would otherwise catch.
- We move to detekt 2.0 stable as soon as it is released.
- **Plan B:** if the alpha crashes, or produces so much noise that it gets in the way, we drop detekt (option 2) with a new ADR, and add it back when 2.0 is stable.

## Consequences
- Static analysis runs on the same JDK as the rest of the build, with no extra toolchain.
- A pre-release tool in the build: breaking changes in rule names or configuration between alphas are possible. The configuration is kept minimal (`buildUponDefaultConfig`, only a few overrides) to make upgrades cheap.
- Option 3 was rejected because a second JDK in CI and on an 8 GB laptop costs more than the stability it buys.
- Because this ADR takes number 0007, the OIDC-in-the-cloud decision planned for milestone 6 in `PLAN.md` becomes ADR 0008.
