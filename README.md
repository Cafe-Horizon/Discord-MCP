# Discord-MCP

Discord REST API (244エンドポイント) および Gateway (WebSocket) に対応した Kotlin 製 MCP (Model Context Protocol) サーバー。

## 特徴

* **REST API 全網羅**: OpenAPI 仕様から自動生成した 244 個の REST エンドポイントを MCP ツール化。
* **Gateway 対応**: WebSocket によるリアルタイムイベント受給・送信に対応。
* **マルチトランスポート**: STDIO モード（既定）および HTTP モード (Stateless Streamable HTTP — MCP 仕様 2026-07-28) に対応。
* **自動リトライ**: HTTP 429 レートリミット時の自動リトライに対応。

---

## クイックスタート

### 1. ビルド
```bash
./gradlew shadowJar
```

### 2. 実行 (STDIO モード)
```bash
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" java -jar build/libs/Discord-MCP-1.0.12.jar
```

### 3. 実行 (HTTP モード)
```bash
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" MCP_TRANSPORT=http java -jar build/libs/Discord-MCP-1.0.12.jar
```

---

## ドキュメント一覧

詳細な設定方法や仕様については、以下の各ドキュメントを参照。

* **[セットアップ & デプロイガイド](docs/SETUP.md)**
  * 必要環境、環境変数一覧、Claude Desktop / Claude Code 設定例、Docker Compose 設定
* **[アーキテクチャ & ツール仕様](docs/ARCHITECTURE.md)**
  * REST ツール仕様、Gateway ツール仕様、レートリミット、対象外機能
* **[開発 & メンテナンスガイド](docs/DEVELOPMENT.md)**
  * プロジェクト構造、エンドポイント定義自動再生成スクリプト、CI/CD ワークフロー
