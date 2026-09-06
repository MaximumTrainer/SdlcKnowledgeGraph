# syntax=docker/dockerfile:1.7

# ---- Build stage: compile and package the Spring Boot fat jar -------------------------------------
FROM gradle:8.12-jdk17 AS build
WORKDIR /workspace

# Dependency-affecting files first so the Gradle cache layer survives source-only changes.
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
# buildSrc holds the ontology generator the build script registers its tasks from, so the build
# script does not compile without it.
COPY buildSrc ./buildSrc
COPY config ./config
COPY src ./src

# The cache mount keeps downloaded dependencies between local builds (uid/gid 1000 = the image's gradle user).
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    gradle bootJar --no-daemon

# ---- Runtime stage: minimal JRE, non-root user -----------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl \
    && addgroup -S app \
    && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
