# セットアップと設定ガイド

## 必要環境

* JDK 26 以上
* (ビルド用) Gradle 9.6 系 (`./gradlew`)

---

## 環境変数一覧

| 変数 | 必須/任意 | 既定値 | 説明 |
|---|---|---|---|
| `DISCORD_BOT_TOKEN` | 必須 | - | Bot トークン。全 REST 呼び出しおよび Gateway IDENTIFY で使用。 |
| `DISCORD_CLIENT_ID` | 任意 | - | OAuth2 ツール参照用。 |
| `DISCORD_CLIENT_SECRET` | 任意 | - | OAuth2 ツール参照用。 |
| `DISCORD_API_BASE_URL` | 任意 | `https://discord.com/api/v10` | REST API ベース URL。 |
| `DISCORD_GATEWAY_URL` | 任意 | `wss://gateway.discord.gg/?v=10&encoding=json` | Gateway WebSocket URL。 |
| `MCP_TRANSPORT` | 任意 | `stdio` | `stdio` または `http`。 |
| `MCP_HTTP_HOST` | 任意 | `0.0.0.0` | HTTP バインドホスト。 |
| `MCP_HTTP_PORT` | 任意 | `8080` | HTTP バインドポート。 |
| `MCP_ALLOWED_HOSTS` | 任意 | - | DNS リバインディング対策の許可 Host ヘッダ（カンマ区切り、ポート込み）。 |
| `MCP_ALLOWED_ORIGINS` | 任意 | - | 許可 Origin ヘッダ（カンマ区切り）。 |
| `DISCORD_MCP_TOOL_CATEGORIES` | 任意 | - | 登録するツールをカテゴリで絞り込む（カンマ区切り、例: `guilds,channels,messages`）。カテゴリはエンドポイントパスの先頭セグメント（`/guilds/...` → `guilds` 等）。 |
| `DISCORD_MCP_INCLUDE_TOOLS` | 任意 | - | ツール名に対する正規表現。マッチしたものだけ登録。 |
| `DISCORD_MCP_EXCLUDE_TOOLS` | 任意 | - | ツール名に対する正規表現。マッチしたものは登録から除外（include/category 適用後に評価）。 |
| `DISCORD_MCP_READONLY` | 任意 | `false` | `true` の場合、GET エンドポイントのみ登録（書き込み系ツールを完全に排除）。 |
| `DISCORD_MCP_LAZY_TOOLS` | 任意 | `false` | `true` の場合、個別ツールの代わりに `discord_search_tools` / `discord_call_tool` の2ツールのみを登録し、実際のエンドポイントは呼び出し時に動的解決する（コンテキスト使用量を大幅削減）。 |
| `DISCORD_MCP_ENABLE_GATEWAY` | 任意 | `true` | `false` の場合、Gateway 系5ツール（`discord_gateway_*`）を登録しない。 |
| `DISCORD_MCP_ALLOW_AUTH_OVERRIDE` | 任意 | `false` | `true` の場合、リクエスト単位での Authorization ヘッダの上書き (`authOverride` パラメータ) を許可・スキーマ開示する。無効時はセキュリティ保護のため除外。 |
| `DISCORD_MCP_ALLOW_FILE_PATH` | 任意 | `true` | `false` の場合、`filePath` によるローカルファイルの読み込みを完全に拒否する（リモート運用時の任意ファイル読み込み防止）。 |
| `DISCORD_MCP_ALLOWED_FILE_DIR` | 任意 | - | `filePath` で読み込みを許可するベースディレクトリ（例: `/var/mcp/uploads`）。指定時、正規化パスによりディレクトリ外への参照 (Path Traversal) をブロックする。 |

---

## コンテキスト圧迫（大量ツール）対策

このサーバーはデフォルトで Discord REST API の全 244 エンドポイントを個別の MCP ツールとして登録するため、
接続するクライアント（Claude 等）に対して起動直後から約250個分のツール名・説明・入力スキーマがコンテキストへ
注入されます。多くの MCP クライアントは 30〜50 ツールを超えるとツール選択精度が落ち、かつ大量のトークンを
消費するため、必要に応じて以下のいずれか（併用可）で絞り込んでください。

### 1. カテゴリ / 正規表現フィルタ（推奨・軽量）

用途が決まっている場合はこちらが簡単です。例: ギルドとチャンネル・メッセージ関連だけ使いたい場合。

```bash
DISCORD_MCP_TOOL_CATEGORIES="guilds,channels" \
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" \
java -jar build/libs/Discord-MCP-1.0.15.jar
```

閲覧用途のみなら読み取り専用に絞ることもできます。

```bash
DISCORD_MCP_READONLY=true DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" java -jar build/libs/Discord-MCP-1.0.15.jar
```

### 2. 動的ツール検索モード（最大の削減効果）

`DISCORD_MCP_LAZY_TOOLS=true` を設定すると、個別の `discord_*` ツールを登録する代わりに、次の2ツールのみを
登録します。

* `discord_search_tools` — キーワード・カテゴリ・HTTP メソッドで利用可能な操作を検索する。引数なしで
  呼び出すとカテゴリ一覧と件数を返す。
* `discord_call_tool` — `discord_search_tools` で見つけたツール名を指定して、任意の Discord REST 操作を
  実行する（`pathParams` / `queryParams` / `body` / `files` / `auditLogReason` / `authOverride` を指定）。

```bash
DISCORD_MCP_LAZY_TOOLS=true DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" java -jar build/libs/Discord-MCP-1.0.15.jar
```

起動時に登録されるツール数が約250個から2個へ減り、モデルが操作前に対象操作を検索してから呼び出す形に
なります（このリポジトリの開発環境で使われている ToolSearch の遅延ロードパターンと同じ発想です）。
`DISCORD_MCP_TOOL_CATEGORIES` 等と併用した場合、検索・呼び出しの対象もそのフィルタ後の集合に限定されます。

---

## 起動・設定方法

### 1. STDIO モード (Claude Desktop 等)

`claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "discord": {
      "command": "java",
      "args": ["-jar", "/path/to/Discord-MCP-1.0.15.jar"],
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
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" MCP_TRANSPORT=http java -jar build/libs/Discord-MCP-1.0.15.jar
```

起動後、以下のエンドポイントが有効化される（MCP 仕様 2026-07-28 ステートレス Streamable HTTP）
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
