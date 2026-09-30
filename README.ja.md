<div align="center">

<img src="assets/brand/mbrain-logo.svg" width="88" height="88" alt="MBrain ロゴ" />

# MBrain

**AI エージェントから Android の機能を利用できる MCP ゲートウェイ。**

端末情報 · アプリ管理 · ファイル操作 · Root / Shizuku · MCP サービスの集約

<p>
  <img src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 9.0 以降" />
  <img src="https://img.shields.io/badge/MCP-HTTP-5E6AD2?style=flat-square" alt="HTTP MCP" />
  <a href="https://github.com/powercess/mbrain/releases/latest"><img src="https://img.shields.io/github/v/release/powercess/mbrain?style=flat-square&amp;color=14756A" alt="最新リリース" /></a>
</p>

[简体中文](README.zh_CN.md) | [繁體中文](README.zh_TW.md) | [English](README.md) | [Français](README.fr.md) | **日本語**

[クイックスタート](#クイックスタート) · [エージェントの接続](#ai-エージェントを接続する) · [MCP サービスの追加](#mcp-サービスを追加する) · [よくある質問](#よくある質問) · [問題を報告](https://github.com/powercess/mbrain/issues)

</div>

---

MBrain は、Android 上で動作する Model Context Protocol（MCP）ゲートウェイです。AI エージェントは、単一のエンドポイントを通じて端末情報の取得、アプリ管理、ファイル操作、コマンド実行、外部 MCP サービスの利用を行えます。公開する機能は端末側で選択でき、ゲートウェイはいつでも停止できます。

## スクリーンショット

<table>
  <tr><th>ホーム</th><th>機能管理</th><th>設定</th></tr>
  <tr>
    <td><img src="assets/screenshots/home.png" width="240" alt="ホーム：ツール、サービス、ゲートウェイの起動と停止" /></td>
    <td><img src="assets/screenshots/capabilities.png" width="240" alt="Root、Shizuku、アプリ管理の機能設定" /></td>
    <td><img src="assets/screenshots/settings.png" width="240" alt="接続情報、外観、実行履歴の設定" /></td>
  </tr>
</table>

<sub>実際のアプリ画面です。利用可能なツールは、有効な機能と接続済みサービスによって変わります。ライト、ダーク、システムに合わせるテーマに対応しています。</sub>

## 主な機能

| 機能 | 内容 |
| --- | --- |
| **端末情報** | 端末の詳細、バッテリー、ストレージ、ネットワークの状態を取得 |
| **アプリ管理** | アプリの一覧・詳細の取得と起動。特権アクセスではインストール、アンインストール、停止も可能 |
| **コマンドとファイル** | Root または Shizuku でコマンドを実行し、ファイルの参照、読み書き、コピー、移動を実施 |
| **MCP サービスの集約** | 端末内の HTTP MCP サービスを接続、または stdio MCP プロセスを起動・管理 |
| **単一の接続先** | ツールの検索、説明の確認、接続情報のコピー、最近の実行履歴の確認 |
| **リモートアクセス** | 内蔵 frpc で TCP トンネルを作成し、MBrain や端末内の別サービスへ接続 |

- **必要な機能だけ有効化：** Root と Shizuku は個別のスイッチと権限確認を備えています。
- **すぐに停止：** ホームから起動・停止でき、常駐通知からも停止できます。
- **任意のサービスを追加：** サービス名と設定を指定でき、特定のサードパーティーアプリに依存しません。

## クイックスタート

### 1. MBrain をインストールする

**Android 9.0 以降**が必要です。

[GitHub Releases](https://github.com/powercess/mbrain/releases/latest) から最新の `mbrain-v<version>-universal.apk` をダウンロードし、Android 端末にインストールしてください。ユニバーサル APK は ARM64、ARM32、x86_64、x86 に対応しています。

リリースには変更内容とチェックサムファイル `SHA256SUMS.txt` が含まれます。ソースからのビルドや開発版については、[開発ガイド](docs/development.md)（簡体字中国語）をご覧ください。

### 2. 公開する機能を選ぶ

下部の**機能**タブを開きます。

- 端末情報の取得には Root は不要です。
- 必要に応じてアプリ管理を有効にします。
- 特権操作には **Root** または **Shizuku** を有効にし、権限を許可します。

Root を使うには、端末が root 化されており、**MBrain 自体**に権限が付与されている必要があります。Shizuku を使う場合は、先に [Shizuku](https://shizuku.rikka.app/) をインストールして起動してください。両方を同時に有効にする必要はありません。

### 3. ゲートウェイを起動する

**ホーム**の右下にある起動ボタンを押します。起動後、**接続先と認証情報**を開き、MCP URL と Bearer トークンをコピーします。

## AI エージェントを接続する

**HTTP MCP と Bearer 認証**に対応したクライアントでサービスを追加します。

| 項目 | 値 |
| --- | --- |
| 名前 | `MBrain`、または任意の名前 |
| MCP URL | `http://127.0.0.1:8765/mcp` |
| 認証方式 | Bearer トークン |
| トークン | MBrain の認証情報画面からコピー |

HTTP ヘッダーで認証を設定する場合：

```http
Authorization: Bearer <your-token>
```

**同じ端末上のクライアント**は、この URL をそのまま使えます。**パソコン上のクライアント**は、ADB で端末に接続してポートを転送します。

```bash
adb forward tcp:8765 tcp:8765
```

既定のポートが使用中の場合は、空いているポートが選択されます。アプリ内に表示される実際の URL と ADB コマンドを使用してください。トークンは端末上で暗号化して保存され、再起動後も維持されます。認証情報画面からリセットできます。

まずは読み取り専用の簡単なリクエストを試してください。

> この端末のバッテリー残量とストレージの空き容量を確認してください。

## MCP サービスを追加する

**MCP → サービスを追加**で接続方式を選びます。

| 方式 | 用途 | 設定項目 |
| --- | --- | --- |
| **HTTP サービス** | 端末上の別アプリやプロセスですでにサービスが動作している場合 | 名前、ローカル URL、任意のトークン |
| **管理対象プロセス** | MBrain が stdio MCP サービスを起動・管理する場合 | 名前、実行ファイル、個別の引数、実行ユーザー |

保存後、詳細画面で**サービスに接続**を選択します。接続が成功すると、そのサービスのツールがゲートウェイの一覧に追加され、同じ MCP エンドポイントから利用できます。

HTTP サービスは現在、ループバックアドレスのみ対応しています。管理対象プロセスに必要な実行ファイルやランタイムは、事前に端末に用意してください。MBrain に Node.js や Python は含まれていません。

## リモートアクセス

内蔵 **frpc** クライアントを使い、自分で運用する **frps** サーバー経由で MBrain や端末内の別サービスに接続できます。**設定 → リモートアクセス → サーバー設定**でアドレス、ポート、トークンを入力し、TCP トンネルを追加します。

すべてのトンネルは同じサーバー設定を共有します。トンネルごとに起動、停止、再試行が可能です。MCP トンネルはゲートウェイの起動・停止に連動し、カスタムサービスのトンネルは独立して動作します。現在は基本的な TCP 転送のみ対応しています。[リモートアクセスガイド](docs/remote-access.md)（簡体字中国語）で設定例と動作を確認できます。

## 権限とセキュリティ

- ゲートウェイは端末のループバックインターフェースだけで待ち受けます。パソコンからは ADB 転送、遠隔地からは設定済みトンネルを利用します。
- Bearer トークンを持つクライアントは、**有効なすべてのツール**を呼び出せます。クライアントごとの権限設定には未対応です。
- Root や Shizuku のツールは端末を実際に変更できます。信頼できるクライアントだけを接続し、トークンを共有しないでください。
- 公開する機能は端末で選び、不要になったらゲートウェイを停止してください。

## よくある質問

<details>
<summary><strong>Root なしでも使えますか？</strong></summary>

はい。端末情報の取得、通常のアプリ情報の取得・起動、ローカル HTTP MCP サービスの接続には Root は不要です。特権操作には Shizuku も使えますが、操作できる範囲は起動方法によって異なります。

</details>

<details>
<summary><strong>クライアントが接続できません。</strong></summary>

ゲートウェイが起動していることと、認証情報画面のトークンを使っていることを確認してください。パソコンからの場合は、実際のポートへの ADB 転送も確認します。待ち受けはループバックのみのため、リモート接続には設定済みの TCP トンネルが必要です。

</details>

<details>
<summary><strong>必要なツールが見つかりません。</strong></summary>

ツール一覧は、機能スイッチ、権限、外部サービスの接続状態で変わります。機能タブと MCP タブを確認し、クライアント側で一覧を更新してください。

</details>

<details>
<summary><strong>ゲートウェイを停止するとどうなりますか？</strong></summary>

リクエストの受付と外部 MCP サービスへの接続を停止し、直接管理している子プロセスを終了します。端末の起動時にゲートウェイが自動起動することはありません。実行履歴は現在、メモリ内だけに保持されます。

</details>

## コントリビューション

不具合報告、機能提案、ドキュメント改善、コードの貢献を歓迎します。[Issue テンプレート](https://github.com/powercess/mbrain/issues/new/choose)を使い、バージョンと再現手順を記載してください。スクリーンショットやログからトークンと個人情報を取り除いてください。

環境構築、ビルド、検証、プルリクエストの流れは[貢献ガイド](CONTRIBUTING.md)（英語）を参照してください。最新の `dev` から専用ブランチを作り、プルリクエストの対象は `dev` にしてください。端末でのテストは[開発ガイド](docs/development.md)（簡体字中国語）に記載しています。

## 謝辞

- [droid-mcp](https://github.com/stixez/droid-mcp)：MBrain が使用する Android MCP の基盤。[Apache-2.0 ライセンス](vendor/droid-mcp/LICENSE)と[由来の記録](vendor/droid-mcp/UPSTREAM.md)を保持しています。
- [Shizuku](https://github.com/RikkaApps/Shizuku) と [libsu](https://github.com/topjohnwu/libsu)：Android の特権アクセス。
- [RikkaHub](https://github.com/rikkahub/rikkahub)：設定リストと操作設計の参考。

## Star History

MBrain が役に立ったら、ぜひスターで応援してください。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date&amp;theme=dark" />
  <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date" />
  <img alt="MBrain の GitHub スター数の推移" src="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date" />
</picture>

<sub>グラフ提供：<a href="https://www.star-history.com/#powercess/mbrain&amp;Date">Star History</a>。</sub>
