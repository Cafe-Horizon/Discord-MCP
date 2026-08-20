# Discord-MCP

Discord REST API (244エンドポイント) および Gateway (WebSocket) に対応した Kotlin 製 MCP (Model Context Protocol) サーバー

## 特徴

* **REST API 全網羅**: OpenAPI 仕様から自動生成した 244 個の REST エンドポイントを MCP ツール化
* **動的AIマクロエンジン**: AI自らがアトミックツールの複合操作をマクロとして定義・保存・削除可能 (`discord_register_macro`, `discord_macro_*`)。`data/macros.json` への永続化、プロファイル別アクセス制御・デフォルトプロファイル自動補完、および MCP `sendToolListChanged` 即時通知に対応
* **ボイスチャンネル & TTS サポート**: ボイスチャンネルへの入退室制御 (`discord_voice_join`, `discord_voice_leave`) および TTS 音声読み上げメッセージ送信 (`discord_voice_send_tts_message`) に対応
* **Interaction / Component 応答**: ボタン・モーダル選択などのインタラクティブUI応答ツール (`discord_interaction_reply`, `discord_build_component_button`) を提供
* **レスポンス & トークン最適化**: レスポンスフィールド制限 (`fields`) および簡略テキスト要約 (`summaryMode`) により、LLMのコンテキスト・トークン消費を大幅削減
* **マルチBotプロファイル管理**: 複数のBotトークンを事前定義し、プロファイル名でリクエスト単位の動的切り替え (`DISCORD_BOT_TOKENS`)
* **Gateway 対応**: WebSocket によるリアルタイムイベント受給・送信に対応
* **マルチトランスポート**: STDIO モード（既定）および HTTP モード (Stateless Streamable HTTP — MCP 仕様 2026-07-28) に対応
* **自動リトライ**: HTTP 429 レートリミット時の自動リトライに対応
* **高精度なツール定義**: リクエストボディ内のフィールド情報展開およびローカルファイルパス (`filePath`) によるトークン消費を抑えたファイル添付に対応
* **柔軟なコンテキスト & セキュリティ制御**: カテゴリ/正規表現フィルタ、動的ツール検索 (`DISCORD_MCP_LAZY_TOOLS`)、および認可トークン上書き制御 (`DISCORD_MCP_ALLOW_AUTH_OVERRIDE`) に対応（[詳細](docs/SETUP.md)）

---

## クイックスタート

### 1. ビルド
```bash
./gradlew shadowJar
```

### 2. 実行 (STDIO モード)
```bash
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" java -jar build/libs/Discord-MCP-1.2.1.jar
```

### 3. 実行 (HTTP モード)
```bash
DISCORD_BOT_TOKEN="YOUR_BOT_TOKEN" MCP_TRANSPORT=http java -jar build/libs/Discord-MCP-1.2.1.jar
```

---

## ドキュメント一覧

詳細な設定方法や仕様については、以下の各ドキュメントを参照

* **[セットアップ & デプロイガイド](docs/SETUP.md)**
  * 必要環境、環境変数一覧、Claude Desktop / Claude Code 設定例、Docker Compose 設定
* **[アーキテクチャ & ツール仕様](docs/ARCHITECTURE.md)**
  * REST ツール仕様、Gateway ツール仕様、レートリミット、対象外機能
* **[開発 & メンテナンスガイド](docs/DEVELOPMENT.md)**
  * プロジェクト構造、エンドポイント定義自動再生成スクリプト、CI/CD ワークフロー
