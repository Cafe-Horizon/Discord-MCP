# 開発およびメンテナンスガイド

## プロジェクト構造

```
src/main/kotlin/com/discordmcp/
  Main.kt                      # エントリポイント (STDIO / HTTP トランスポート起動)
  config/Config.kt             # 設定データモデルおよび管理
  discord/EndpointModels.kt    # エンドポイント定義モデル
  discord/EndpointRegistry.kt  # discord_endpoints.json の読み込み
  discord/EndpointFilter.kt    # カテゴリ・正規表現・Readonly フィルタ
  discord/EndpointExecutor.kt  # リクエスト検証・実行・結果整形ロジック
  discord/DiscordHttpClient.kt # HTTPクライアント (認証・レートリミット・multipart対応)
  discord/DiscordResult.kt     # REST呼び出し結果型
  discord/RestToolRegistrar.kt # 244 エンドポイントの MCP ツール登録
  discord/LazyToolRegistrar.kt # 動的ツール検索モード (discord_search_tools / discord_call_tool) の登録
  gateway/GatewayModels.kt     # Gateway ペイロード/イベントモデル
  gateway/GatewayClient.kt     # WebSocket 接続・イベントループ管理
  gateway/GatewayTools.kt      # Gateway 操作用 MCP ツール
src/main/resources/
  discord_endpoints.json       # 自動生成されたエンドポイント定義データ
scripts/
  generate_discord_endpoints.py # OpenAPI 仕様からの定義自動生成スクリプト
.github/workflows/
  update-endpoints.yml         # エンドポイント定義の自動更新ワークフロー
```

---

## エンドポイント定義の自動再生成

本サーバーは `discord_endpoints.json` によるデータ駆動設計のため、Discord REST API の追加・変更時に Kotlin コードの変更は不要です。

### 再生成スクリプトの実行

Python 3.10+ 環境でスクリプトを実行し、`discord_endpoints.json` を更新します。

```bash
python3 scripts/generate_discord_endpoints.py
```

* 公式仕様 [discord/discord-api-spec](https://github.com/discord/discord-api-spec) から `openapi.json` を取得し、エンドポイント定義を抽出します。
* オプション:
  * `--spec <path>` : ローカルの OpenAPI 仕様ファイルを使用。
  * `--out <path>` : 出力先ファイルを指定。

---

## CI/CD ワークフロー

`.github/workflows/update-endpoints.yml` により、定期的に Discord 公式仕様の変更を検知し、自動的に Pull Request を作成します。
