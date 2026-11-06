# syntax=docker/dockerfile:1
# One image with the Angular app inside the Spring Boot jar (ADR 0005).

# --- 1. Frontend --------------------------------------------------------------------------
FROM node:24.22.2-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- 2. Backend ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.5_8-jdk-alpine AS backend
WORKDIR /app/backend
# Wrapper and build scripts first, so the dependency layer is cached between code changes.
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY backend/ ./
COPY --from=frontend /app/frontend/dist /app/frontend/dist
RUN ./gradlew bootJar --no-daemon -PskipFrontendBuild \
    && java -Djarmode=tools -jar build/libs/sluice-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /app/extracted

# --- 3. Runtime ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.5_8-jre-alpine
RUN addgroup -S sluice && adduser -S sluice -G sluice
WORKDIR /app
# Layered jar: dependencies change rarely, application code often.
COPY --from=backend /app/extracted/dependencies/ ./
COPY --from=backend /app/extracted/spring-boot-loader/ ./
COPY --from=backend /app/extracted/snapshot-dependencies/ ./
COPY --from=backend /app/extracted/application/ ./
USER sluice
# Structured JSON logs. Run migrations first with SPRING_PROFILES_ACTIVE=prod,migrate (exits when done).
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
