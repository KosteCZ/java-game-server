# ==============================================================================
# Multi-stage Dockerfile for Spring Boot 3 + Java 21 (Optimized for 512MB RAM)
# ==============================================================================

# STAGE 1: Build stage
FROM maven:3-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Cache dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build executable jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# STAGE 2: Production runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Security: run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy executable jar from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Expose server port (mapped to ${PORT:8080} via application.yml)
EXPOSE 8080

# JVM memory tuning optimized for Koyeb Free Tier (512MB RAM ceiling):
# - UseContainerSupport: Auto-detects container cgroup limits
# - MaxRAMPercentage=70.0: Limits heap to ~350MB, reserving ~150MB for OS, thread stacks, Metaspace & NIO
# - UseSerialGC: Minimal GC footprint (saves ~20-40MB RAM compared to G1GC)
# - ExitOnOutOfMemoryError: Fail fast so Koyeb orchestrator immediately reboots a clean instance
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# Run Spring Boot application (picks up all Koyeb/Supabase environment variables dynamically)
ENTRYPOINT ["java", "-jar", "app.jar"]
