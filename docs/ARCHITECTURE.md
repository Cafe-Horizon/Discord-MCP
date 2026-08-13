# アーキテクチャおよびツール仕様

## トランスポート

MCP 仕様 **2026-07-28**（ステートレス）に準拠。

* **STDIO**（既定）: クライアントが子プロセスとして起動する形式（Claude Desktop 等）。
* **HTTP**: Ktor 組み込みサーバーで Stateless Streamable HTTP を `/mcp` に公開。  
  セッション不要・ステートレス設計により、標準の HTTP ロードバランサーで水平スケールが可能。

## ツール構成

### 1. REST API ツール (`discord_*`)

* Discord REST API の 244 エンドポイントが `discord_<operationId>` という名称で個別の MCP ツールとして自動登録されます（`RestToolRegistrar`）。
* 入力パラメータ:
  * パス / クエリ パラメータ
  * `body`: JSON オブジェクト (リクエストボディ。必要に応じて Known / Required フィールド情報が JSON Schema プロパティとして展開)
  * `files`: 添付ファイル配列 (multipart/form-data 対応。Base64 文字列 `contentBase64` に加え、ローカルファイルパス `filePath` による直接アタッチに対応。`DISCORD_MCP_ALLOW_FILE_PATH` や `DISCORD_MCP_ALLOWED_FILE_DIR` により範囲・可否を制限可能)
  * `auditLogReason`: `X-Audit-Log-Reason` ヘッダ
  * `authOverride`: `Authorization` ヘッダの上書き (`DISCORD_MCP_ALLOW_AUTH_OVERRIDE=true` 設定時のみ開放)
* 実際にどのエンドポイントを登録するかは `EndpointFilter` が `AppConfig` のツールサーフェス設定
  (`DISCORD_MCP_TOOL_CATEGORIES` / `INCLUDE_TOOLS` / `EXCLUDE_TOOLS` / `READONLY`) に基づいて絞り込みます。
  各 `EndpointSpec` はパスの先頭セグメントから導出される `category`（例: `guilds`, `channels`）を持ちます。
* リクエストの検証・実行・結果整形のロジックは `EndpointExecutor` に切り出されており、通常登録モードと
  下記の動的検索モードの両方から共有されます。

### 1b. 動的ツール検索モード (`DISCORD_MCP_LAZY_TOOLS=true`)

個別登録の代わりに `LazyToolRegistrar` が `discord_search_tools` / `discord_call_tool` の2ツールのみを登録し、
`EndpointFilter` 通過後の集合に対してキーワード検索・動的呼び出しを行います。クライアント起動時のコンテキスト
占有をツール約250個分から2個分へ削減する目的の機能です。詳細は [SETUP.md](SETUP.md) を参照。

### 2. Gateway ツール

WebSocket リアルタイム通信用に 5 つの管理用ツールが登録されます。
* `discord_gateway_connect` : Gateway 接続を開始。
* `discord_gateway_status` : 現在の接続状態を確認。
* `discord_gateway_events` : 受信バッファ (最大 2,000 件) からイベントを取得。
* `discord_gateway_send` : 任意の Gateway オペコードペイロードを送信。
* `discord_gateway_disconnect` : 接続を切断。

---

## エラーハンドリングとレートリミット

* **レートリミット (HTTP 429)**:
  レスポンスボディの `retry_after` を解析し、自動的にスリープ後リトライ (最大 3 回) を行います。
* **エラーレスポンス**:
  `DiscordResult.Error` にてレスポンスステータスおよびエラーメッセージを抽象化し、MCP ツールのエラー形式へ変換します。

---

## 対象外事項 (Scope Limitations)

* **実音声 (Voice RTP/UDP) 通信**:
  Gateway 経由の Voice State Update イベント制御のみ対応し、実音声データの送受信機能は実装していません。
* 非公開・非標準の内部 API。
