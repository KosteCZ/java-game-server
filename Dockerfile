# ==============================================================================
# Multi-stage Dockerfile optimized for Koyeb Free Tier (512MB RAM ceiling)
# ==============================================================================

# STAGE 1: Build the artifact
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Cache dependency layer first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and compile packaged jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# STAGE 2: Lightweight, secure, low-memory runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-privileged user for container security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy executable jar from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Expose default port
EXPOSE 8080

# Environment and JVM Tuning for 512MB RAM limit:
# - UseContainerSupport: Auto-detects container cgroup limits
# - MaxRAMPercentage=70.0: Caps heap around ~350MB, reserving ~150MB for Metaspace, thread stacks, direct buffers & OS
# - UseSerialGC: Lowers GC footprint significantly compared to G1GC (~20-40MB RAM saved)
# - ExitOnOutOfMemoryError: Fail fast so orchestrator (Koyeb) restarts immediately
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "app.jar"]
