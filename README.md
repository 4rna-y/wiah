# world-is-also-hardcore (wiah)

Minecraft Paper 用プラグイン。ハードコアワールドで**参加中のプレイヤーが誰か1人でも死亡したら、
その場で全員をキックしてワールドをリセットする**。

| モジュール | 中身 |
| --- | --- |
| `plugin/` | Paper プラグイン `WorldIsAlsoHardcore` |

再起動を担う常駐アプリは [`mstore`](https://github.com/4rna-y/mstore) として別リポジトリに分離してある
(もとは本リポジトリの `supervisor/` モジュール)。mstore はサーバー監視に加えて HTTP の
key-value ストアも提供する。ワールドをまたいで残したい値はそこに置ける。

## 動作の仕組み

稼働中のサーバーからは主ワールドのフォルダを削除できないため、リセットは3段階で行う。

1. **死亡検知時 (プラグイン)** — 削除対象ワールドを解決して予約ファイル
   (`plugins/WorldIsAlsoHardcore/pending-reset.txt`) に書き出し、`server.properties` の
   `level-seed` を新しい乱数へ更新し、全員をキックしてサーバーを停止する。
2. **停止検知時 (mstore)** — 予約ファイルが残っていればリセットによる停止と判断して
   サーバーを起動し直す。予約ファイルが無い正常終了 (`stop` コマンド等) なら自分も終了する。
3. **次回起動時 (プラグイン)** — `PluginBootstrap#bootstrap()` で予約されたフォルダを削除する。
   削除されたワールドはサーバーが自動的に再生成する。

> 削除は `onLoad()` ではなく `bootstrap()` で行う必要がある。Minecraft 26.x のサーバーは
> プラグインの `onLoad()` より前に `level.dat` を読んでワールド設定 (seed / hardcore /
> ワールドタイプ) を確定させるため、`onLoad()` で消すと再生成されるワールドが
> **削除前の seed と設定をそのまま引き継いでしまう**。

## 前提

- `server.properties` の `hardcore=true`。設定されていない場合は起動時に警告を出す。
- サーバーの起動は [`mstore`](https://github.com/4rna-y/mstore) 経由で行う。プラグイン単体ではリセット時に停止するだけで、
  起動し直す主体がいない。
  systemd の `Restart=always` などで代用してもよいが、その場合は `stop` コマンドでも
  再起動してしまう点に注意。
  Spigot の `restart-script` を設定済みなら、プラグイン側を `shutdown-mode: restart` にして
  mstore を使わない構成も選べる。

```console
$ mstore --server-dir /srv/minecraft --java-arg -Xmx4G
```

オプションと systemd の例は [mstore の README](https://github.com/4rna-y/mstore#readme) を参照。

## プラグイン設定 (`plugins/WorldIsAlsoHardcore/config.yml`)

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `worlds` | `[]` | 監視かつリセット対象のワールド名。空なら主ワールドとその `_nether` / `_the_end` |
| `kick-message` | (下記) | キック理由。MiniMessage 形式、`<player>` が死亡者名に置換される |
| `broadcast-message` | (下記) | キック直前の全体通知。空文字で無効 |
| `shutdown-delay-ticks` | `20` | キックからサーバー停止までの待ち時間 (tick) |
| `shutdown-mode` | `shutdown` | `shutdown` または `restart` |
| `randomize-seed` | `true` | リセット時に `level-seed` を新しい乱数へ書き換える |

## コマンド

`/wiahc <status|reset|reload>` — 権限 `worldisalsohardcore.admin` (既定: OP)

- `status` — 監視対象ワールドと主要設定を表示
- `reset` — 死亡と同じリセット処理を手動で実行
- `reload` — `config.yml` を再読み込み

## 開発

`flake.nix` に JDK 25 / Gradle 9 / Paper テストサーバーを用意してある。

```console
$ nix develop
$ wiah-dev                 # ビルド → ./run/plugins へ配置 → mstore 経由で起動
```

`wiah-dev` は mstore もビルドして使うので、隣に clone しておく。

```console
$ git clone https://github.com/4rna-y/mstore ../mstore
```

別の場所に置いている場合は `MSTORE_DIR` で指定する。

個別に動かす場合:

```console
$ gradle :plugin:build
$ deploy-plugin            # ビルド成果物を ./run/plugins へコピー
$ paper-server             # mstore を挟まず単体起動
$ paper-jar 1.21.4         # jar を取得してパスだけ表示
```

`paper-jar` は PaperMC の v3 API から最新の安定ビルドを取得し、SHA256 を検証する。
バージョンを固定したい場合は引数で指定する (`wiah-dev 1.21.4` のように `wiah-dev` にも渡せる)。
