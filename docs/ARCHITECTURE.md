# アーキテクチャおよびツール仕様

## トランスポート

MCP 仕様 **2026-07-28**（ステートレス）に準拠

* **STDIO**（既定）: クライアントが子プロセスとして起動する形式（Claude Desktop 等）
* **HTTP**: Ktor 組み込みサーバーで Stateless Streamable HTTP を `/mcp` に公開。  
  セッション不要・ステートレス設計により、標準の HTTP ロードバランサーで水平スケールが可能。

## ツール構成

### 1. REST API ツール (`discord_*`)

* Discord REST API の 244 エンドポイントが `discord_<operationId>` という名称で個別の MCP ツールとして自動登録されます（`RestToolRegistrar`）
* 入力パラメータ:
  * パス / クエリ パラメータ
  * `body`: JSON オブジェクト (リクエストボディ。必要に応じて Known / Required フィールド情報が JSON Schema プロパティとして展開)
  * `files`: 添付ファイル配列 (multipart/form-data 対応。Base64 文字列 `contentBase64` に加え、ローカルファイルパス `filePath` による直接アタッチに対応。`DISCORD_MCP_ALLOW_FILE_PATH` や `DISCORD_MCP_ALLOWED_FILE_DIR` により範囲・可否を制限可能)
  * `auditLogReason`: `X-Audit-Log-Reason` ヘッダ
  * `fields`: 応答JSONから指定されたキー/フィールドのみを選択抽出（カンマ区切りまたは配列）。トークン消費量を削減
  * `summaryMode`: `true` の場合、レスポンスをJSONではなく人間/LLM可読な要約テキスト形式に自動整形
  * `profile`: マルチBot環境変数 (`DISCORD_BOT_TOKENS`) で設定されたプロファイル名を指定し動的トークン切り替え
  * `authOverride`: `Authorization` ヘッダの上書き (`DISCORD_MCP_ALLOW_AUTH_OVERRIDE=true` 設定時のみ開放)
* 実際にどのエンドポイントを登録するかは `EndpointFilter` が `AppConfig` のツールサーフェス設定
  (`DISCORD_MCP_TOOL_CATEGORIES` / `INCLUDE_TOOLS` / `EXCLUDE_TOOLS` / `READONLY`) に基づいて絞り込みます。
  各 `EndpointSpec` はパスの先頭セグメントから導出される `category`（例: `guilds`, `channels`）を持ちます。
* リクエストの検証・実行・結果整形のロジックは `EndpointExecutor` に切り出されており、通常登録モードと
  下記の動的検索モードの両方から共有されます。

### 1b. 動的ツール検索モード (`DISCORD_MCP_LAZY_TOOLS=true`)

個別の登録の代わりに `LazyToolRegistrar` が `discord_search_tools` / `discord_call_tool` の2ツールのみを登録し、
`EndpointFilter` 通過後の集合に対してキーワード検索・動的呼び出しを行います。クライアント起動時のコンテキスト
占有をツール約250個分から2個分へ削減する目的の機能です。詳細は [SETUP.md](SETUP.md) を参照

### 1c. 動的AIマクロエンジン (`MacroEngine`)

AIが複数のアトミックツールの呼び出し手順・フィルタリング条件を組み合わせた「宣言型マクロ」を動的に生成・登録・削除する機構です。

* 管理用メタツール:
  * `discord_register_macro`: マクロ定義（名前、説明、パラメータ、ステップ定義、プロファイル指定）を受け取り、新規ツールとしてアタッチ
  * `discord_unregister_macro`: 指定された名前のマクロを削除
  * `discord_list_macros`: 登録済みマクロの一覧と詳細仕様を取得（任意パラメータ `profile` で対象プロファイルのアクセス可能マクロのみを抽出可能）
* プロファイル対応ハイブリッドスコープ:
  * `profiles` (リスト): 許可されたプロファイル（Botアカウント）名を指定。未指定（`null`）の場合は全プロファイルで共有されるグローバルマクロ。
  * `defaultProfile` (文字列): ステップ実行時にプロファイル引数が省略された場合に自動適用されるデフォルトプロファイル。
* 動的マクロツール (`discord_macro_<name>`):
  * 登録されたマクロは `discord_macro_<name>` というMCPツールとして自動アタッチされ、入力パラメータを受け取ってステップを順次自動実行します。
* 永続化と通知:
  * マクロは `data/macros.json` に永続化され、追加・削除時には MCP 仕様の `sendToolListChanged` 通知を発行してクライアントのツールリストを即座に更新します。

### 1d. Interaction / Component ツール (`discord_interaction_*`)

Discordのインタラクティブ要素（ボタン、モーダル、スラッシュコマンド等）に対するコールバック応答・ビルダーツールを提供します。

* `discord_interaction_reply`: Interaction に対するコールバック応答（`type`: 4=Message, 5=Deferred, 7=UpdateMessage, 9=Modal）を送信
* `discord_build_component_button`: ボタンコンポーネント (Type 2) の JSON 構造体を安全に構築

### 2. Gateway & Voice ツール

WebSocket リアルタイム通信およびボイスチャンネル操作用に各種管理ツールが提供されます。

#### Gateway 接続管理
* `discord_gateway_connect` : Gateway 接続を開始
* `discord_gateway_status` : 現在の接続状態を確認
* `discord_gateway_events` : 受信バッファ (最大 2,000 件) からイベントを取得
* `discord_gateway_send` : 任意の Gateway オペコードペイロードを送信
* `discord_gateway_disconnect` : 接続を切断

#### Voice チャンネル & TTS
* `discord_voice_join`: Gateway Opcode 4 (Voice State Update) を送信し、ボイスチャンネルへ接続
* `discord_voice_leave`: ボイスチャンネルから離脱
* `discord_voice_send_tts_message`: テキスト/ボイスチャンネルへ Text-To-Speech (TTS) 音声読み上げメッセージを送信

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
* 非公開・非標準の内部 API
