# Multi-stage build for HFT Trading Platform
# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy POM files first for dependency caching
COPY pom.xml .
COPY hft-common/pom.xml hft-common/
COPY hft-sbe/pom.xml hft-sbe/
COPY hft-aeron/pom.xml hft-aeron/
COPY hft-engine/pom.xml hft-engine/
COPY hft-market-data/pom.xml hft-market-data/
COPY hft-fix-gateway/pom.xml hft-fix-gateway/
COPY hft-api/pom.xml hft-api/
COPY hft-persistence/pom.xml hft-persistence/
COPY hft-app/pom.xml hft-app/

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY hft-common/src hft-common/src
COPY hft-sbe/src hft-sbe/src
COPY hft-aeron/src hft-aeron/src
COPY hft-engine/src hft-engine/src
COPY hft-market-data/src hft-market-data/src
COPY hft-fix-gateway/src hft-fix-gateway/src
COPY hft-api/src hft-api/src
COPY hft-persistence/src hft-persistence/src
COPY hft-app/src hft-app/src

# Build the application
RUN mvn clean package -DskipTests -B

# Extract layered JAR
RUN java -Djarmode=layertools -jar hft-app/target/hft-app-1.0.0-SNAPSHOT.jar extract --destination extracted

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy AS runtime

# Install performance tools
RUN apt-get update && apt-get install -y \
    procps \
    net-tools \
    iputils-ping \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r hft && useradd -r -g hft hft

# Create directories
RUN mkdir -p /app /data/fix/store /data/fix/log /logs \
    && chown -R hft:hft /app /data /logs

WORKDIR /app

# Copy layered JAR
COPY --from=builder --chown=hft:hft /app/extracted/dependencies/ ./
COPY --from=builder --chown=hft:hft /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=hft:hft /app/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=hft:hft /app/extracted/application/ ./

# Set user
USER hft

# JVM tuning for low-latency with Aeron compatibility (Java 21 LTS)
ENV JAVA_OPTS="-server \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=10 \
    -XX:+UseStringDeduplication \
    -XX:+AlwaysPreTouch \
    -XX:+DisableExplicitGC \
    -Xms2g \
    -Xmx4g \
    -Djava.security.egd=file:/dev/./urandom \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"

# Expose ports
# 8080 - REST API
# 9500 - TCP Market Data
EXPOSE 8080 9500

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
