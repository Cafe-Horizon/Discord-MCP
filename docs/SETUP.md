# セットアップと設定ガイド

## 必要環境

* JDK 21 以上
* (ビルド用) Gradle 9.6 系 (`./gradlew`)

---

## 環境変数一覧

| 変数 | 必須/任意 | 既定値 | 説明 |
|---|---|---|---|
| `DISCORD_BOT_TOKEN` | 実質必須 | - | Bot トークン。全 REST 呼び出しおよび Gateway IDENTIFY で使用。 |
| `DISCORD_CLIENT_ID` | 任意 | - | OAuth2 ツール参照用。 |
| `DISCORD_CLIENT_SECRET` | 任意 | - | OAuth2 ツール参照用。 |
| `DISCORD_API_BASE_URL` | 任意 | `https://discord.com/api/v10` | REST API ベース URL。 |
| `DISCORD_GATEWAY_URL` | 任意 | `wss://gateway.discord.gg/?v=10&encoding=json` | Gateway WebSocket URL。 |
| `MCP_TRANSPORT` | 任意 | `stdio` | `stdio` または `http`。 |
| `MCP_HTTP_HOST` | 任意 | `0.0.0.0` | HTTP バインドホスト。 |
| `MCP_HTTP_PORT` | 任意 | `8080` | HTTP バインドポート。 |
| `MCP_ALLOWED_HOSTS` | 任意 | - | DNS リバインディング対策の許可 Host ヘッダ（カンマ区切り、ポート込み）。 |
| `MCP_ALLOWED_ORIGINS` | 任意 | - | 許可 Origin ヘッダ（カンマ区切り）。 |

---

## 起動・設定方法

### 1. STDIO モード (Claude Desktop 等)

`claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "discord": {
      "command": "java",
      "args": ["-jar", "/path/to/Discord-MCP-1.0.11.jar"],
      "env": {
        "DISCORD_BOT_TOKEN": "YOUR_BOT_TOKEN"
      }
    }
  }
}
```

### 2. HTTP モード (常駐サーバー)

`MCP_TRANSPORT=http` を指定して起動する。

```bash
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" MCP_TRANSPORT=http java -jar build/libs/Discord-MCP-1.0.11.jar
```

起動後、以下のエンドポイントが有効化される（MCP 仕様 2026-07-28 ステートレス Streamable HTTP）。
* `http://localhost:8080/mcp` : Stateless Streamable HTTP トランスポート

#### クライアント接続例

* **Claude Code**:
  ```bash
  claude mcp add --transport http discord http://localhost:8080/mcp
  ```
* **MCP Inspector**:
  ```bash
  npx -y @modelcontextprotocol/inspector --connect http://localhost:8080/mcp
  ```

---

## Docker での運用

MCP Kotlin SDK は DNS リバインディング対策として Host ヘッダ検証を行います。コンテナサービス名で接続する場合、`MCP_ALLOWED_HOSTS` にサービス名とポートを指定してください。

`docker-compose.yml`:
```yaml
services:
  discord-mcp:
    image: discord-mcp
    environment:
      DISCORD_BOT_TOKEN: "YOUR_BOT_TOKEN"
      MCP_TRANSPORT: "http"
      MCP_HTTP_PORT: "8085"
      MCP_ALLOWED_HOSTS: "discord-mcp:8085,localhost:8085"
    ports:
      - "8085:8085"
```
