# Stage 1: Builder stage
FROM gradle:jdk26 AS builder

WORKDIR /build

COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src

RUN gradle shadowJar --no-daemon

# Stage 2: Runtime stage
FROM ghcr.io/cafe-horizon/horiz-os:latest

# Copy OpenJDK from builder stage
COPY --from=builder /opt/java/openjdk /opt/java/openjdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY --from=builder /build/build/libs/discord-mcp-*.jar /app/app.jar

USER horiz
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
