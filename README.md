# discord-mcp-server

Discord の HTTP API をほぼ全面的にカバーする MCP (Model Context Protocol) サーバーです。Kotlin + [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) 製で、STDIO トランスポートで動作します(Claude Desktop などから子プロセスとして起動する想定)。

## 特徴

- **REST API 244 エンドポイントをすべてツール化**。[discord/discord-api-spec](https://github.com/discord/discord-api-spec)(Discord 公式の OpenAPI 3.1 仕様、`https://docs.discord.com/developers` の裏付け)から自動生成した `src/main/resources/discord_endpoints.json` を実行時に読み込み、1 エンドポイント = 1 MCP ツールとして登録します。Guild / Channel / Message / Member / Role / Emoji / Sticker / Webhook / Invite / Thread / Forum / AutoMod / AuditLog / ScheduledEvent / Soundboard / ApplicationCommand(スラッシュコマンド)/ Interaction / SKU・Entitlement / Lobby / OAuth2 など、公式仕様に載っている操作を網羅しています。
- **Gateway(WebSocket)のリアルタイムイベント購読にも対応**。`discord_gateway_connect` で接続し、受信したイベントは直近 2000 件までバッファされ `discord_gateway_events` で読み出せます。IDENTIFY / HEARTBEAT / RESUME / 再接続を自前実装しています。
- 汎用ツール `discord_gateway_send` で任意の Gateway オペコード(Presence Update, Voice State Update, Request Guild Members など)を送信可能。
- すべてのツールに共通のオプション引数 `auditLogReason`(`X-Audit-Log-Reason` ヘッダ)と `authOverride`(`Authorization` ヘッダの上書き。OAuth2 の Bearer トークンを使う操作にも対応)を用意。
- 429 レートリミット時は `retry_after` を見て自動リトライ(最大 3 回)します。

### 対応していないもの(スコープ外)

- **音声(ボイス)の実音声通信**。Gateway 経由の Voice State Update イベントの送受信はできますが、UDP/RTP による音声そのもの(発話・受信)は実装していません。専用の音声 SDK 相当の実装が必要な領域のため対象外としています。
- Discord の OpenAPI 仕様に含まれない一部の内部/非公開 API。

## セットアップ

### 必要環境

- JDK 21 以上
- (ビルド用)Gradle 9.6 系。同梱の `gradlew` / `gradlew.bat` はラッパー本体の jar (`gradle/wrapper/gradle-wrapper.jar`) を含んでいません。初回のみ以下のいずれかでラッパー jar を用意してください。
  - Gradle か IntelliJ IDEA が既にインストール済みなら、プロジェクトルートで `gradle wrapper --gradle-version 9.6.1` を一度実行する
  - IntelliJ IDEA (Community 可) でこのフォルダを開き、Gradle 連携を自動セットアップさせる

### 環境変数

| 変数 | 必須 | 説明 |
|---|---|---|
| `DISCORD_BOT_TOKEN` | 実質必須 | Bot Token。`Authorization: Bot <token>` として全 REST 呼び出しと Gateway IDENTIFY に使われます。未設定でもサーバーは起動しますが、各ツール呼び出し時に `authOverride` を渡さない限りエラーになります。 |
| `DISCORD_CLIENT_ID` / `DISCORD_CLIENT_SECRET` | 任意 | OAuth2 系ツールで参考情報として利用(未使用でも動作に支障なし)。 |
| `DISCORD_API_BASE_URL` | 任意 | 既定値 `https://discord.com/api/v10`。 |
| `DISCORD_GATEWAY_URL` | 任意 | 既定値 `wss://gateway.discord.gg/?v=10&encoding=json`。 |

### ビルド

```powershell
.\gradlew.bat build
```

`build/libs/discord-mcp-server-1.0.0.jar`(Shadow プラグインによる fat jar)が生成されます。

### Claude Desktop への登録例

`claude_desktop_config.json` に以下を追加してください(パスは実際の場所に置き換えてください)。

```json
{
  "mcpServers": {
    "discord": {
      "command": "java",
      "args": ["-jar", "D:\\Dev\\Discord-MCP\\build\\libs\\discord-mcp-server-1.0.0.jar"],
      "env": {
        "DISCORD_BOT_TOKEN": "あなたのBotトークン"
      }
    }
  }
}
```

## ツールの構成

- `discord_<operationId>` という名前で REST API のツールが 244 個登録されます(例: `discord_create_message`, `discord_get_guild_member`, `discord_ban_user_from_guild`, `discord_create_guild_application_command` など)。
- 各ツールの入力スキーマはパス/クエリパラメータ + JSON ボディ(`body` オブジェクト)+ 共通オプション(`auditLogReason`, `authOverride`)から自動生成されます。ボディの厳密なフィールド定義までは埋め込んでいないため、詳細フィールドは [Discord 公式ドキュメント](https://docs.discord.com/developers/docs)を参照しながら `body` に JSON を渡してください。
- ファイル添付が必要な 3 エンドポイント(`upload_application_attachment` / `create_guild_sticker` / `update_invite_target_users` 等の multipart 系)は `files` 配列(`{filename, contentType, contentBase64}`)で添付できます。
- Gateway 管理用に 5 ツール: `discord_gateway_connect` / `discord_gateway_status` / `discord_gateway_events` / `discord_gateway_send` / `discord_gateway_disconnect`。

## プロジェクト構成

```
src/main/kotlin/com/discordmcp/
  Main.kt                      # エントリポイント(STDIO トランスポート起動)
  config/Config.kt             # 環境変数読み込み
  discord/EndpointModels.kt    # エンドポイント定義のデータクラス
  discord/EndpointRegistry.kt  # discord_endpoints.json のロード
  discord/DiscordHttpClient.kt # 認証・レートリミット・multipart/form-urlencoded 対応の汎用HTTPクライアント
  discord/RestToolRegistrar.kt # 244エンドポイント→MCPツール登録
  gateway/GatewayModels.kt     # Gatewayペイロード/バッファイベントのデータクラス
  gateway/GatewayClient.kt     # WebSocket接続・HELLO/HEARTBEAT/IDENTIFY/RESUME実装
  gateway/GatewayTools.kt      # Gateway操作用MCPツール
src/main/resources/
  discord_endpoints.json       # Discord公式OpenAPI仕様から生成したエンドポイント定義(244件)
scripts/
  generate_discord_endpoints.py  # discord_endpoints.json の自動生成スクリプト(下記参照)
.github/workflows/
  update-endpoints.yml         # 定期実行して仕様ドリフトをPRで通知するワークフロー
```

## エンドポイント定義の再生成(エンドポイントの増減への自動対応)

このサーバーはツール一覧を「1 エンドポイント = 1 JSON エントリ」というデータ駆動の設計にしているため、Discord 側で REST エンドポイントが増減・変更されても **Kotlin コードは一切変更不要** です。`discord_endpoints.json` を差し替えるだけでツール一覧が追従します。

その JSON 自体の更新も手作業ではなく `scripts/generate_discord_endpoints.py` で自動生成します。

```bash
python3 scripts/generate_discord_endpoints.py
```

- 既定では [discord/discord-api-spec](https://github.com/discord/discord-api-spec) の `specs/openapi.json` を直接ダウンロードし、`operationId` / `method` / `path` / パスパラメータ / クエリパラメータ / リクエストボディのスキーマ名・必須フィールドを抽出して `src/main/resources/discord_endpoints.json` を上書き生成します(スキーマの解釈ロジックは `EndpointModels.kt` のデータ構造に合わせてあります)。
- ローカルにダウンロード済みの spec を使う場合は `--spec path/to/openapi.json`、出力先を変えたい場合は `--out path/to/output.json` を指定できます。
- 標準ライブラリのみで動作するため、追加の pip install は不要です(Python 3.10+)。
- Discord 公式 OpenAPI 仕様に含まれない 2 つの OAuth2 トークン系エンドポイント(`oauth2_token_exchange` / `oauth2_token_revoke`)は、スクリプト内に手動定義として残り、生成のたびに末尾へ追加されます。

### 定期的な自動更新(GitHub Actions)

`.github/workflows/update-endpoints.yml` が毎週月曜 03:00 UTC(および手動実行)にこのスクリプトを実行し、Discord の仕様が変わって差分が出た場合のみ自動で Pull Request を作成します。マージすればビルドし直すだけで新しいツール一覧が反映されます。人手でのスキーマ読み込み・突き合わせ作業は不要です。

## 制限・注意事項

- 未検証の破壊的操作(ロールの一括削除、Guild の削除操作、大量メッセージの一括削除など)も他の操作と同様にツール化されているため、実行前に内容をよく確認してください。
- レートリミットの共有バケット管理はエンドポイント単位の簡易リトライのみで、Discord の `X-RateLimit-Bucket` を用いた高度な事前スロットリングは行っていません。
