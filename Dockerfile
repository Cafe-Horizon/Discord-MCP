FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JARファイルをコピー
COPY build/libs/discord-mcp-*.jar app.jar

# 既定は STDIO トランスポート。HTTP(Streamable HTTP: /mcp, SSE: /sse)で常駐させたい場合は
# `docker run -e MCP_TRANSPORT=http -p 8080:8080 ...` のように環境変数とポート公開を指定してください。
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
