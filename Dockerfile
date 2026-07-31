FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JARファイルをコピー
COPY build/libs/discord-mcp-server-*.jar app.jar

# STDIO トランスポートで起動
ENTRYPOINT ["java", "-jar", "app.jar"]
