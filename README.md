# world-is-also-hardcore (wiah)

Minecraft Paper 用プラグイン。ハードコアワールドで**参加中のプレイヤーが誰か1人でも死亡したら、
その場で全員をキックしてワールドをリセットする**。

| モジュール | 中身 |
| --- | --- |
| `plugin/` | Paper プラグイン `WorldIsAlsoHardcore` |

死亡したときに何をするかは `on-death` で選ぶ。

| `on-death` | 死亡したら |
| --- | --- |
| `reset` (既定) | ワールドを削除して作り直す。以下の「動作の仕組み」 |
| `finale` | ワールドを残したまま**ハードコアを終える**。[ハードコアの終わり方](#ハードコアの終わり方) |

`finale` は企画の最後のワールド向けで、一度きり。

再起動を担う常駐アプリは [`mstore`](https://github.com/4rna-y/mstore) として別リポジトリに分離してある
(もとは本リポジトリの `supervisor/` モジュール)。mstore はサーバー監視に加えて HTTP の
key-value ストアも提供する。ワールドをまたいで残したい値はそこに置ける。

## 動作の仕組み

稼働中のサーバーからは主ワールドのフォルダを削除できないため、リセットは3段階で行う。

1. **死亡検知時 (プラグイン)** — チャットへ流れる死亡メッセージを打ち消し、代わりに全員へ
   タイトル「サーバーは10秒後に削除されます」を出す。同時に削除対象ワールドを解決して予約ファイル
   (`plugins/WorldIsAlsoHardcore/pending-reset.txt`) に書き出し、`server.properties` の
   `level-seed` を新しい乱数へ更新する。猶予 (既定10秒) が過ぎたら全員をキックして
   サーバーを停止する。

   > 予約と seed の更新はキックより先に済ませる。猶予の途中でサーバーが落ちても
   > リセットは次回起動時に実行される。
2. **停止検知時 (mstore)** — 予約ファイルが残っていればリセットによる停止と判断して
   サーバーを起動し直す。予約ファイルが無い正常終了 (`stop` コマンド等) なら自分も終了する。
3. **次回起動時 (プラグイン)** — `PluginBootstrap#bootstrap()` で予約されたフォルダを削除する。
   削除されたワールドはサーバーが自動的に再生成する。

> 削除は `onLoad()` ではなく `bootstrap()` で行う必要がある。Minecraft 26.x のサーバーは
> プラグインの `onLoad()` より前に `level.dat` を読んでワールド設定 (seed / hardcore /
> ワールドタイプ) を確定させるため、`onLoad()` で消すと再生成されるワールドが
> **削除前の seed と設定をそのまま引き継いでしまう**。

## ハードコアの終わり方

`on-death: finale` にすると、死亡しても**ワールドを消さない**。予約ファイルも書かず、
seed も変えず、キックもせず、サーバーも止めない。代わりに企画を締める。

1. **死亡検知時** — チャットへ流れる死亡メッセージを打ち消し、落ちた持ち物と経験値も消す。
   終わったことを `plugins/WorldIsAlsoHardcore/finale-done.txt` に書き、対象ワールドの
   ハードコアを解除する。全員へタイトル「ハードコア終了」を出して猶予 (既定10秒) を待つ。
2. **猶予のあと** — 死んでいる人を復活させ、参加中の全員の持ち物を空にして
   **アドベンチャーモード**へ移す。そのうえで成績表を配る。
3. **終了後にログインした人** — アドベンチャーへ移して成績表を渡す。持ち物には触らない
   (`finale.apply-on-join`)。

### 一度きりであること

終わったことは `finale-done.txt` に残す。発火済みの目印をメモリにだけ持つと、再起動した後に
もう一度誰かが死んだときに**全員の持ち物をもう一度消してしまう**。プラグインのデータフォルダは
ワールドと違って消えないので、ここに置けば再起動をまたいで「もう終わっている」と分かる。

このファイルがある間、対象ワールドでの死亡は普通の死亡として扱う — 死亡メッセージもそのまま
流すし、`on-death` を `reset` に戻してもワールドは消えない。もう一度ハードコアを始めるなら
このファイルを消すこと。

### ハードコアを解除する理由

ハードコアのワールドでは、復活しようとしたプレイヤーは観戦者にされる。解除しないと
**企画を終わらせた当人だけが幽霊のまま取り残される**。そのため復活させる前に
`World#setHardcore(false)` で解き、`server.properties` の `hardcore` も `false` へ揃える
(`finale.disable-hardcore`、既定 `true`)。ハートの見た目が普通のものに変わる。

### 成績表

死亡回数を持っているのは [`death_counter`](../death_counter) なので、本を組むのも配るのも
向こうに任せる。繋ぎはコマンド1本 (`finale.result-command`) で、既定では
DeathCounter の `/result` を叩く。

```yaml
result-command: "result ハードコア終了|<player> が死亡|経過 <elapsed>"
```

`<player>` は最後に死亡した人、`<elapsed>` はワールドの経過時間に置き換わる。`|` は本の
改行になる。空文字にすれば本を配らない。DeathCounter が入っていない場合はログに残して先へ進む
(アドベンチャーへの切り替えは済ませる)。

## Discord への通知

リセットが起きると Discord の Webhook へ Embed を1件送る。載せるのは**死亡したプレイヤー**、
**ワールドの経過リアル時間**、**そのプレイヤーの頭 (HeadFace) の画像**、それに打ち消した死亡メッセージ。

- **経過時間** — ワールドが作り直された時刻を `plugins/WorldIsAlsoHardcore/world-started-at.txt`
  に残しておき、そこからの差で出す。打ち直すのは `bootstrap()` が実際にワールドを削除した
  ときだけなので、普通の再起動をまたいでも積み上がる。つまり**サーバーが止まっていた時間も
  含む実時間**で、ワールド内部の tick 数 (`World#getFullTime()`) ではない。
- **頭の画像** — スキンの描画は外部のアバターサービス (既定は mc-heads.net) に任せ、
  Embed のサムネイルに URL を置くだけ。`head-image-url` を書き換えれば別のサービスにできる。
  コンソールからの `/wiahc reset` のように UUID が無い相手のときは頭を出さない。
- **送り損ねの扱い** — 死亡の数秒後にはサーバーが止まるため、送信の途中でプロセスが消えうる。
  そこで組み立てた JSON を先に `plugins/WorldIsAlsoHardcore/pending-webhook.json` へ書いてから
  送り、届いたら消す。停止時は `flush-timeout-seconds` まで送信の終了を待ち、それでも
  送れなければ次回起動時に送り直す。Discord に拒否された (URL が無効など) 場合だけは
  抱え込まずに破棄する。

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
| `on-death` | `reset` | 死亡したときの振る舞い。`reset` または `finale` |
| `worlds` | `[]` | 監視かつリセット対象のワールド名。空なら主ワールドとその `_nether` / `_the_end` |
| `reset-delay-seconds` | `10` | 死亡から全員キックまでの猶予 (秒)。この間タイトルを出す |
| `title` | (下記) | 猶予中に全員へ出すタイトル。`<player>` と `<seconds>` が置換される |
| `subtitle` | (下記) | タイトルの下に出る小さい方。空文字で無効 |
| `broadcast-message` | `""` | 猶予の開始と同時に流すチャット。空文字で無効 |
| `kick-message` | (下記) | キック理由。MiniMessage 形式、`<player>` が死亡者名に置換される |
| `shutdown-delay-ticks` | `20` | キックからサーバー停止までの待ち時間 (tick) |
| `shutdown-mode` | `shutdown` | `shutdown` または `restart` |
| `randomize-seed` | `true` | リセット時に `level-seed` を新しい乱数へ書き換える |

`reset-delay-seconds` 以下の5つは `on-death: reset` のときだけ効く。

### ハードコアの終わり方 (`finale.*`)

`on-death: finale` のときだけ効く。

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `delay-seconds` | `10` | 死亡から締めに入るまでの猶予 (秒)。この間タイトルを出す |
| `title` | `ハードコア終了` | 猶予中に全員へ出すタイトル。`<player>` と `<seconds>` が置換される |
| `subtitle` | (下記) | その下に出る小さい方。空文字で無効 |
| `broadcast-message` | `""` | 猶予の開始と同時に流すチャット。空文字で無効 |
| `message` | (下記) | 締めが済んだときに全員へ流すチャット。空文字で無効 |
| `result-command` | (上記) | 成績表を配るコマンド。空文字で無効 |
| `apply-on-join` | `true` | 終了後にログインした人もアドベンチャーへ移して成績表を渡す |
| `join-result-command` | (上記) | その人へ渡すコマンド。`<joiner>` がその人の名前 |
| `disable-hardcore` | `true` | 対象ワールドと `server.properties` のハードコアを解除する |
| `embed-title` | `ハードコアが終了しました` | 終了を知らせる Embed の題。`discord.embed-title` の代わりに使う |

### Discord への通知 (`discord.*`)

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `enabled` | `true` | false にすると一切送らない |
| `webhook-url` | `""` | 送り先。**空の間は通知しない**ので、使うならサーバーごとに設定する。Webhook URL は事実上の認証情報 (知っていれば誰でもそのチャンネルへ投稿できる) なので、リポジトリへコミットしないこと |
| `username` | `""` | Webhook の表示名の上書き。空なら Discord 側の設定のまま |
| `embed-title` | `ワールドがリセットされました` | Embed の題 |
| `embed-color` | `15158332` | Embed の色 (10進数。既定は 0xE74C3C) |
| `footer` | `""` | Embed の下端に出す小さい文字。空文字で無効 |
| `head-image-url` | `https://mc-heads.net/avatar/<uuid>/128` | サムネイルに出す頭の画像。`<uuid>` と `<player>` が置換される |
| `timeout-seconds` | `5` | 1回の送信で待つ時間 |
| `flush-timeout-seconds` | `8` | 停止時に送信の終了を待つ時間 |

## コマンド

`/wiahc <status|reset|finale|reload|testwebhook>` — 権限 `worldisalsohardcore.admin` (既定: OP)

- `status` — 監視対象ワールドと主要設定、`on-death`、終了済みかどうか、ワールド経過時間を表示
- `reset` — 死亡と同じリセット処理を手動で実行
- `finale` — 死亡と同じ終了処理を手動で実行 (`on-death` の値に関わらず動く)。**一度きり**
- `reload` — `config.yml` を再読み込み
- `testwebhook` — ワールドを消さずに Discord へテスト通知だけ送る (題に `[テスト]` が付く)

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

## テスト

| 実行するもの | かかる時間 | 見るもの |
| --- | --- | --- |
| `gradle :plugin:test` | 数秒 | 予約ファイルを読んでワールドを削除する部分 (`ResetManager`) |
| `gradle :e2e:e2eTest` | 数分 | 実際に Paper を起動し、mstore と組み合わせた一周 |

```console
$ nix develop
$ gradle :plugin:test      # 速い方。gradle build にも含まれる
$ gradle :e2e:e2eTest      # 実サーバーを起動する。build には含まれない
```

`:plugin:test` が見ているもの:

- `ResetManagerTest` — 予約ファイルを読んでワールドを削除する部分。`level.dat` が無いフォルダを
  削除しない安全弁を含む
- `DeathListenerTest` — 死亡時にチャットの死亡メッセージを打ち消し、その文面を死因として
  持たせたまま応答を起動すること。応答が終わっている (ハードコア終了済み) なら普通の死亡として
  扱うこと、終了する応答なら落ちた持ち物も消すこと。イベントとプレイヤーはモックなので
  クライアントは要らない
- `FinaleManagerTest` — 終了の記録ファイルの読み書き (壊れていても終了済みとして扱う) と、
  成績表を配るコマンドの組み立て
- `DefaultConfigTest` — 同梱 config.yml の既定値。タイトルが「サーバーは10秒後に削除されます」と
  表示されること、猶予が10秒であることを固定する
- `MarkerContractTest` — 予約ファイルのパスが mstore の既定値と一致すること
- `WorldClockTest` — ワールドの開始時刻の記録と、経過時間の書式 (「3日 4時間 12分」)。
  普通の再起動では起点を打ち直さないことを含む
- `DiscordNotifierTest` — 送る Embed の中身 (死亡者・経過時間・死因・頭の画像) と、
  送り損ねた通知の保留と送り直し。HTTP は張らず偽の `WebhookClient` を挿す

`:e2e:e2eTest` は使い捨てのサーバーディレクトリを `e2e/build/e2e-run` に作り、mstore 経由で
Paper を起動する。コンソールへ `wiahc reset` を送り、次の順で確かめる。

1. プラグインが監視対象ワールドを認識している
2. `wiahc reset` で予約と `level-seed` の更新が先に済み、猶予を待ってから全員がキックされ、
   サーバーが停止する (猶予を無視して即キックしていないことも確認する)
3. mstore が予約を検出してサーバーを起動し直す
4. bootstrap が予約されたワールドを削除する (起動完了より前であること)
5. ワールドフォルダが作り直されている (事前に置いた目印が消えている)
6. 予約ファイルが消費されている
7. `stop` なら再起動せず mstore も一緒に終了する

コンソールの全出力は `e2e/build/e2e-run-console.log` に残る。失敗時は直近40行が
アサーションのメッセージにも付く。

前提が揃わない場合はスキップする (失敗にはしない)。

- mstore の実行ファイル — 隣の checkout を `installDist` してから使う。`MSTORE_DIR` で変更可
- Paper の jar — `-Dwiah.paperJar` / `PAPER_JAR` / `run/paper-*.jar` / PATH の `paper-jar` の順で探す

> e2e は使い捨てサーバーの `eula.txt` に `eula=true` を書く。同意したくない場合は
> `:e2e:e2eTest` を実行しないこと。

### 検証していないこと

- **実クライアントでの死亡** — 実際に人が死ぬ経路は e2e に含めていない。分岐は
  `DeathListenerTest` が、その先のリセット一周は e2e (`/wiahc reset` 経由) が見ており、
  どちらも同じ `ResetManager#trigger` を通る。繋ぐには MCProtocolLib のヘッドレス bot が
  使えるが、対応が 26.1 までなので e2e のサーバーを 26.2 から下げる必要がある。
- **タイトルの見え方** — 表示 API を呼んだことは確認できるが、実際に画面へどう出るかは
  クライアントが要る。
- **`shutdown-mode: restart`** — Spigot の restart-script を使う経路。e2e は既定の
  `shutdown` だけを見ている。
- **`on-death: finale` の一周** — 実際に全員をアドベンチャーへ移して成績表が配られるところ。
  記録ファイルとコマンドの組み立ては `FinaleManagerTest` が見ているが、持ち物を空にする・
  死んだ本人を復活させる・`/result` が本を配る、は本物のサーバーとクライアントが要る。
  `/wiahc finale` で手元のサーバーを使って確かめること。
