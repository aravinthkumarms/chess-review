# ── Stage 1: Build the JAR ─────────────────────────────────────────────
FROM gradle:8.12-jdk21 AS builder

WORKDIR /app

# Copy gradle config first (layer cache for dependency downloads)
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./

# Download dependencies (cached unless build.gradle changes)
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

# Copy source and build (excluding the Windows stockfish.exe from resources)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Install Stockfish from the Ubuntu apt repository (native Linux binary)
RUN apt-get update && \
    apt-get install -y --no-install-recommends stockfish && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy the built JAR from the build stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose the Spring Boot default port
EXPOSE 8080

# Pass Stockfish binary path to the app via environment variable
ENV STOCKFISH_PATH=/usr/games/stockfish

ENTRYPOINT ["java", "-jar", "app.jar"]
