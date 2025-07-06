# Multi-stage build for optimal image size
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy JAR files and custom native library
COPY libs/ ./libs/
COPY gradle/ ./gradle/
COPY src/ ./src/

RUN cd gradle && gradle build --no-daemon

# Runtime stage - Debian Trixie Slim (matching your TDLib build environment)
FROM debian:trixie-slim

# Install OpenJDK 21 and OpenSSL 3.5 (exact match for your custom TDLib)
RUN apt-get update && apt-get install -y \
    openjdk-21-jre-headless \
    openssl \
    libssl3t64 \
    curl \
    libc6 \
    zlib1g \
    ca-certificates \
    libc++1 \
    libc++abi1 \
    libstdc++6 \
    sqlite3 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 🔧 SECURITY FIX: Create non-root user and group FIRST
RUN groupadd -g 1001 appgroup && \
    useradd -u 1001 -g appgroup -m -s /bin/bash appuser

# Copy the built JAR from builder stage
COPY --from=builder /app/gradle/build/libs/*-all.jar app.jar

# Copy your custom TDLib native library
COPY --from=builder /app/libs/natives/libtdjni.so /app/natives/libtdjni.so

# Copy utility scripts
COPY scripts/ /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh

# 🔧 SECURITY FIX: Create directories and set ownership for non-root user
RUN mkdir -p /app/data /app/logs /app/natives && \
    chown -R appuser:appgroup /app && \
    chown -R appuser:appgroup /usr/local/bin/

# 🔧 SECURITY FIX: Ensure native library is owned by appuser
RUN chown appuser:appgroup /app/natives/libtdjni.so && \
    chmod 755 /app/natives/libtdjni.so

# Set library path for your custom native library
ENV LD_LIBRARY_PATH=/app/natives:$LD_LIBRARY_PATH
ENV JAVA_LIBRARY_PATH=/app/natives

# Expose health check port
EXPOSE 8080

# Health check for Docker and monitoring
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# JVM optimization for containers - include custom library path
ENV JAVA_OPTS="-Xms64m -Xmx280m -XX:MaxMetaspaceSize=96m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+UseContainerSupport -Djava.library.path=/app/natives"

# 🔧 SECURITY FIX: Switch to non-root user BEFORE running the application
USER appuser

# Start the application with your custom TDLib
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]