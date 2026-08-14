# セットアップと設定ガイド

## 必要環境

* JDK 26 以上
* (ビルド用) Gradle 9.6 系 (`./gradlew`)

---

## 環境変数一覧

| 変数名 | 必須/任意 | 型 | 既定値 | 説明 |
|---|---|---|---|---|
| **Bot認証・基本設定** | | | | |
| `DISCORD_BOT_TOKEN` | 条件付き必須 | `String` | - | 単一Bot用トークン（`DISCORD_BOT_TOKENS` 未指定時は必須） |
| `DISCORD_BOT_TOKENS` | 任意 | `JSON / CSV` | - | マルチBot用プロファイル辞書（JSON `{"default": "...", "admin": "..."}` または CSV `admin=token1,user=token2`）。各ツールの `profile` パラメータで指定可能 |
| `DISCORD_CLIENT_ID` | 任意 | `String` | - | OAuth2 ツール参照用 |
| `DISCORD_CLIENT_SECRET` | 任意 | `String` | - | OAuth2 ツール参照用 |
| `DISCORD_API_BASE_URL` | 任意 | `String` | `https://discord.com/api/v10` | REST API ベース URL |
| `DISCORD_GATEWAY_URL` | 任意 | `String` | `wss://gateway.discord.gg/?v=10&encoding=json` | Gateway WebSocket URL |
| **MCP / ネットワーク設定** | | | | |
| `MCP_TRANSPORT` | 任意 | `String` | `stdio` | `stdio` または `http` |
| `MCP_HTTP_HOST` | 任意 | `String` | `0.0.0.0` | HTTP バインドホスト |
| `MCP_HTTP_PORT` | 任意 | `Int` | `8080` | HTTP バインドポート |
| `MCP_ALLOWED_HOSTS` | 任意 | `CSV` | - | DNS リバインディング対策の許可 Host ヘッダ（カンマ区切り、ポート込み） |
| `MCP_ALLOWED_ORIGINS` | 任意 | `CSV` | - | 許可 Origin ヘッダ（カンマ区切り） |
| **ツール制御・セキュリティ** | | | | |
| `DISCORD_MCP_TOOL_CATEGORIES` | 任意 | `CSV` | - | 登録するツールをカテゴリで絞り込む（カンマ区切り、例: `guilds,channels,messages`）。カテゴリはエンドポイントパスの先頭セグメント |
| `DISCORD_MCP_INCLUDE_TOOLS` | 任意 | `Regex` | - | ツール名に対する正規表現。マッチしたものだけ登録 |
| `DISCORD_MCP_EXCLUDE_TOOLS` | 任意 | `Regex` | - | ツール名に対する正規表現。マッチしたものは登録から除外（include/category 適用後に評価） |
| `DISCORD_MCP_READONLY` | 任意 | `Boolean` | `false` | `true` の場合、GET エンドポイントのみ登録（書き込み系ツールを完全に排除） |
| `DISCORD_MCP_LAZY_TOOLS` | 任意 | `Boolean` | `false` | `true` の場合、個別ツールの代わりに `discord_search_tools` / `discord_call_tool` の2ツールのみを登録し、実際のエンドポイントは呼び出し時に動的解決する（コンテキスト使用量を大幅削減） |
| `DISCORD_MCP_ENABLE_GATEWAY` | 任意 | `Boolean` | `true` | `false` の場合、Gateway 系5ツール（`discord_gateway_*`）を登録しない |
| `DISCORD_MCP_ALLOW_AUTH_OVERRIDE` | 任意 | `Boolean` | `false` | `true` の場合、リクエスト単位での Authorization ヘッダの上書き (`authOverride` パラメータ) を許可・スキーマ開示する。無効時はセキュリティ保護のため除外 |
| `DISCORD_MCP_ALLOW_FILE_PATH` | 任意 | `Boolean` | `true` | `false` の場合、`filePath` によるローカルファイルの読み込みを完全に拒否する（リモート運用時の任意ファイル読み込み防止） |
| `DISCORD_MCP_ALLOWED_FILE_DIR` | 任意 | `String` | - | `filePath` で読み込みを許可するベースディレクトリ（例: `/var/mcp/uploads`）。指定時、正規化パスによりディレクトリ外への参照 (Path Traversal) をブロックする |

---

## コンテキスト圧迫（大量ツール）対策

このサーバーはデフォルトで Discord REST API の全 244 エンドポイントを個別の MCP ツールとして登録するため、
接続するクライアント（Claude 等）に対して起動直後から約250個分のツール名・説明・入力スキーマがコンテキストへ
注入されます。多くの MCP クライアントは 30〜50 ツールを超えるとツール選択精度が落ち、かつ大量のトークンを
消費するため、必要に応じて以下のいずれか（併用可）で絞り込んでください。

### 1. カテゴリ / 正規表現フィルタ（推奨・軽量）

用途が決まっている場合はこちらが簡単です。例: ギルドとチャンネル・メッセージ関連だけ使いたい場合

```bash
DISCORD_MCP_TOOL_CATEGORIES="guilds,channels" \
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" \
java -jar build/libs/Discord-MCP-1.2.0.jar
```

閲覧用途のみなら読み取り専用に絞ることもできます。

```bash
DISCORD_MCP_READONLY=true DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" java -jar build/libs/Discord-MCP-1.2.0.jar
```

### 2. 動的ツール検索モード（最大の削減効果）

`DISCORD_MCP_LAZY_TOOLS=true` を設定すると、個別の `discord_*` ツールを登録する代わりに、次の2ツールのみを
登録します。

* `discord_search_tools` — キーワード・カテゴリ・HTTP メソッドで利用可能な操作を検索する。引数なしで
  呼び出すとカテゴリ一覧と件数を返す
* `discord_call_tool` — `discord_search_tools` で見つけたツール名を指定して、任意の Discord REST 操作を
  実行する（`pathParams` / `queryParams` / `body` / `files` / `auditLogReason` / `authOverride` を指定）

```bash
DISCORD_MCP_LAZY_TOOLS=true DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" java -jar build/libs/Discord-MCP-1.2.0.jar
```

起動時に登録されるツール数が約250個から2個へ減り、モデルが操作前に対象操作を検索してから呼び出す形に
なります。
`DISCORD_MCP_TOOL_CATEGORIES` 等と併用した場合、検索・呼び出しの対象もそのフィルタ後の集合に限定されます。

### 3. レスポンスフィールド選択制限 (`fields` & `summaryMode`)

API呼び出し時の返却データサイズ・トークン消費量を削減するために、全RESTツールおよび `discord_call_tool` に共通のオプション引数が追加されています。

* `fields`: 返却JSONから指定キーのみを抽出（例: `"id,content,author"` または `["id", "content"]`）
* `summaryMode`: `true` に設定すると、JSONツリーの代わりに簡略化された要約テキスト（配列要素数や特定識別子）を返却

### 4. 動的AIマクロエンジン (`discord_register_macro` 他)

AI自身が繰り返し使用する複雑な複数手順（アトミックツールの呼び出しおよびデータ抽出・フィルタリング）をマクロとして保存・自動生成できます。

* `discord_register_macro`: マクロ定義（名前、パラメータ、ステップ配列、許可プロファイルリスト `profiles`、`defaultProfile`）を登録
* `discord_list_macros`: 登録済みマクロを取得（`profile` パラメータで指定プロファイル用のアクセス可能マクロのみ抽出可能）
* プロファイル制限機能 (`profiles`): 特定のBotプロファイル専用マクロとしてアクセス制限が可能。`defaultProfile` 設定により未指定時の自動補完に対応
* 登録完了後、`notifications/tools/list_changed` 通知が送信され、即時に `discord_macro_<name>` がMCPツールとして利用可能になります
* 定義データは `data/macros.json` に自動保存され、サーバー再起動後も保持されます

### 5. Interaction 応答 & Voice チャンネル制御ツール

* **Interaction 応答**: `discord_interaction_reply` を使用し、ボタンクリックやモーダル送信に対するコールバック応答を直接送信可能
* **Voice 接続 & TTS**: `discord_voice_join` / `discord_voice_leave` でボイスチャンネルへ入退室し、`discord_voice_send_tts_message` で音声アナウンステキストを送信可能

---

## 起動・設定方法

### 1. STDIO モード (Claude Desktop 等)

`claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "discord": {
      "command": "java",
      "args": ["-jar", "/path/to/Discord-MCP-1.2.0.jar"],
      "env": {
        "DISCORD_BOT_TOKEN": "YOUR_BOT_TOKEN"
      }
    }
  }
}
```

### 2. HTTP モード (常駐サーバー)

`MCP_TRANSPORT=http` を指定して起動

```bash
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" MCP_TRANSPORT=http java -jar build/libs/Discord-MCP-1.2.0.jar
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

### 3. マルチBot (プロファイル) 設定例

`DISCORD_BOT_TOKENS` を使用して複数の Bot トークンをプロファイルとして登録し、呼び出し時に `profile` パラメータで切り替えることができます。

#### 環境変数の設定形式
* **JSON 形式**:
  `DISCORD_BOT_TOKENS='{"default": "TOKEN_DEFAULT", "admin": "TOKEN_ADMIN"}'`
* **CSV 形式**:
  `DISCORD_BOT_TOKENS="default=TOKEN_DEFAULT,admin=TOKEN_ADMIN"`

#### `claude_desktop_config.json` での設定例
```json
{
  "mcpServers": {
    "discord": {
      "command": "java",
      "args": ["-jar", "/path/to/Discord-MCP-1.2.0.jar"],
      "env": {
        "DISCORD_BOT_TOKENS": "{\"default\": \"TOKEN_DEFAULT\", \"admin\": \"TOKEN_ADMIN\"}"
      }
    }
  }
}
```

#### ツール呼び出し時の `profile` 指定例
MCP ツール呼び出し時（または `discord_call_tool` 使用時）に `profile` パラメータを指定します。

```json
{
  "profile": "admin"
}
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
