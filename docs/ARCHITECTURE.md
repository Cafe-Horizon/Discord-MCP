# アーキテクチャおよびツール仕様

## ツール構成

### 1. REST API ツール (`discord_*`)

* Discord REST API の 244 エンドポイントが `discord_<operationId>` という名称で個別の MCP ツールとして自動登録されます。
* 入力パラメータ:
  * パス / クエリ パラメータ
  * `body`: JSON オブジェクト (リクエストボディ)
  * `files`: 添付ファイル配列 (multipart/form-urlencoded 対応)
  * `auditLogReason`: `X-Audit-Log-Reason` ヘッダ
  * `authOverride`: `Authorization` ヘッダの上書き (OAuth2 Bearer トークン等)

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
