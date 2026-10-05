# dtv-android

Android TV / Google TV に USB チューナーを挿して、地デジの受信と録画を
その1台で完結させるための APK です。母艦の PC も NAS も要りません。

APK は2本あります。

| 表示名 | 役割 | applicationId |
| --- | --- | --- |
| mirakc | チューナーを掴んで TS を配信する | `dev.khronos31.mirakc` |
| EPGStation Server | 番組表と録画予約を受け持つサーバー | `dev.khronos31.epgstation.server` |

各APKは独立してバージョン管理しています。Releasesも
`mirakc-vX.Y.Z` と `epgstation-server-vX.Y.Z` に分かれているため、必要なAPKの
リリースから対応するファイルを選んでください。

mirakc APK は上流の mirakc `3.4.86` を Android 向けに移植して組み込み、番組表・
ストリーム・HTTP API はその実装を使います。ジョブとフィルターのコマンドには、
上流の mirakc-arib `0.24.38` を固定して同梱しています。

番組表や録画予約の画面は、スマートフォンや PC のブラウザから開きます。
テレビの画面で録画を見るときは
[epcltvapp](https://github.com/daig0rian/epcltvapp) がよくできているので、
そちらをおすすめします（下の「[あわせて使いたいもの](#あわせて使いたいもの)」）。

## 必要なもの

| | |
| --- | --- |
| 本体 | Android TV / Google TV（Google TV Streamer で動作確認） |
| チューナー | PLEX PX-S1UD／同じ Siano チップの USB チューナー、下表のPX4系チューナー |
| カードリーダー | CCID 対応の USB カードリーダー（Identive/SCM SCR33xx v2.0 で動作確認） |
| カード | B-CAS カード |
| その他 | USB ハブ（本体のポートが1つしかないため）、録画用の USB ストレージ（任意・exFAT） |

mirakc APK は、px4-userland v0.1.9 が扱う16種類のUSB製品IDに対応します。ここに載っていることは、その機種の受信やカード利用を実機で確認済みという意味ではありません。実機未検証の機種は、Android でも未検証です。

| 機種 | USB ID | USBブリッジ数 | 受信機数 | 地上波/衛星 |
|---|---|---:|---:|---|
| PX-Q3U4 | `0511:084a` | 2 | 8 | 固定: 0,1,4,5 がBS/CS、2,3,6,7 が地上波 |
| PX-W3U4 | `0511:083f` | 1 | 4 | 固定: 0,1 がBS/CS、2,3 が地上波 |
| PX-MLT5PE / DTV02A-5TS-P | `0511:024e` / `0511:924e` | 1 | 5 | 各受信機で地上波/BS/CS |
| PX-W3PE4 / PX-W3PE5 | `0511:023f` / `0511:073f` | 1 | 4 | 固定: 0,1 がBS/CS、2,3 が地上波 |
| PX-Q3PE4 / PX-Q3PE5 | `0511:024a` / `0511:074a` | 2 | 8 | 固定: 0,1,4,5 がBS/CS、2,3,6,7 が地上波 |
| PX-MLT8PE3 | `0511:0252` | 1 | 3 | 各受信機で地上波/BS/CS |
| PX-MLT8PE5 | `0511:0253` | 1 | 5 | 各受信機で地上波/BS/CS |
| DTV02A-4TS-P | `0511:0254` | 1 | 4 | 各受信機で地上波/BS/CS |
| PX-M1UR | `0511:0854` | 1 | 1 | 地上波/BS/CS; 衛星はLNB 0V |
| PX-S1UR | `0511:0855` | 1 | 1 | 地上波のみ |
| DTV03A-1TU | `0511:0052` | 1 | 1 | 地上波のみ |
| DTV02-1T1S-U | `0511:004b` | 1 | 1 | 地上波/BS/CS; 衛星はLNB 0V |
| DTV02A-1T1S-U | `0511:084b` | 1 | 1 | 地上波/BS/CS; 衛星はLNB 0V |

カードリーダーとカードが無くても 1seg 用の経路はありますが、画質は 320x180 です。
12seg のフル HD にはカードが要ります。PX-S1UD の 12seg 復号は以前の実装で確認済みですが、
0.4.0 の移行後に実機で再確認していません。

## 導入

1. [Releases](https://github.com/Khronos31/dtv-android/releases) から
   `mirakc-*.apk` と `epgstation-server-*.apk` を入手します。
2. テレビに入れます。開発者オプションから USB デバッグを有効にして、
   PC から `adb` で入れるのが確実です。

   ```sh
   adb connect <テレビのIPアドレス>:5555
   adb install -r mirakc-X.Y.Z.apk
   adb install -r epgstation-server-X.Y.Z.apk
   ```

   ファイルマネージャー系のアプリから入れても構いません。提供元不明のアプリの
   インストールを許可する必要があります。
3. チューナーとカードリーダーを USB ハブ経由でテレビに挿します。

PX4 を使う場合、チャンネルスキャン時の準備処理で、未キャッシュならアプリが公式PLEX HTTPS配布物を取得します。固定した
ZIPとSYSのサイズ・SHA-256を確認し、PX-W3U4 BDA 1.0のsegment tableからKotlinで
firmware領域を独立抽出します。segment種別・長さ・CRC32と生成物のサイズ・SHA-256も
検証します。ZIP、SYS、生成済みfirmwareはAPKに含めず、アプリ専用外部ファイル領域へ
検証後に原子的にキャッシュします。既存の有効なキャッシュはネットワークなしで
再利用できます。取得や生成に失敗した場合はPX4
だけを無効化し、Sianoとmirakcは継続します。手動のfirmware provisionは不要です。
PX4対応機種のうち、M1UR/S1UR、MLT5系および複数筐体の同時利用はソフトウェア上の
識別・adapter試験を行った候補段階で、受入完了を意味しません。接続実機での
受信・復号・同時利用試験は未実施です。

## 使い方

### 1. チャンネルを準備して mirakc を起動する

**mirakc** を開くと Android TV の十字キーで操作できる画面が出ます。画面を開いただけでは
公開サーバーは起動しません。初回は **チャンネルスキャン** を選び、表示されたチューナーと
B-CAS カードリーダーの USB 許可を行ってください。必要な許可が揃うと、スキャン操作が
自動的に続きます。PX4 を使う場合は必要なファームウェアを先に準備します。

地上波チューナーがある場合、アプリはスキャン専用の mirakc を loopback
`127.0.0.1:40773` だけで起動し、実際の探索状況を表示します。探索結果はチャンネル設定として
保存されます。空振り、失敗、キャンセルでは以前に保存したチャンネル設定を残します。
手動スキャンは利用可能な地上波 receiver 数に応じて最大8並列で探索します。PX-Q3U4では
地上波用4 receiverを使い、スキャン専用のため視聴用receiverの予約は行いません。
初回に地上波チューナーがなく衛星チューナーがある場合は、検出結果を偽装せず、同梱の
BS 26 チャンネルと CS 12 チャンネルを準備します。

チャンネル設定の準備ができたら **mirakc を起動** を選びます。通常の公開サーバーは
`0.0.0.0:40772` で起動します。停止するときは同じ画面の停止操作を使います。設定済みチャンネルは
EPG間隔（1〜1440分）の更新ジョブと一緒に保存され、アプリから再編集できます。

「詳細設定」の **USB権限を要求** は、チャンネルスキャンを始めずに、接続済みチューナーや
カードリーダーのUSB許可だけを要求するときに使います。接続機器一覧には対応チューナーと
その許可状態が表示されます。

### 2. EPGStation Server を起動する

**EPGStation Server** を開くと、ブラウザーから接続する URL と対応する QR コードが
表示されます。初回はサーバーの展開に少し時間がかかります。

接続先の `Mirakurun / mirakc` URL は同じ端末の mirakc を指す
`http://127.0.0.1:40772/` が既定値です。別の場所で動いている mirakc や
Mirakurun を使う場合は、接続先 URL を開いて編集してください。保存すると設定が永続化され、
サーバーが再起動します。

録画ストレージには保存先候補と空き容量が表示されます。利用可能な USB
ストレージを選ぶと録画先が切り替わり、サーバーが再起動します。保存先を選んでいない場合は、
利用可能な USB ストレージを優先し、なければ内蔵ストレージを使います。アップデートの確認は画面のボタンを押したときだけ行います。アプリ情報では
アプリと同梱 EPGStation のバージョン、リポジトリ、ライセンス文書を確認できます。

### APKを手動で更新する

mirakc の「アプリ情報」にある **アップデートを確認** を押したときだけ GitHub Releases を
確認し、新しい版があるか、最新か、確認に失敗したかを通知します。APKのダウンロードや
インストールは自動では行いません。起動時やバックグラウンドでは確認しません。

EPGStation Server の更新操作は同アプリの画面から行います。自動更新はしません。

### 3. スマホや PC から番組表を開く

テレビに出ている QR コードを読むか、`http://<テレビのIPアドレス>:8888/` を
ブラウザで開きます。EPGStation の画面がそのまま出るので、番組表を見る、
録画を予約する、録ったものを再生する、といった操作はここで行います。

この画面はマウスとタッチ向けに作られているので、テレビのリモコンの十字キーで
操作するのは向いていません。手元のスマートフォンから触るのが快適です。

### 4. テレビの画面で見る

録画の再生とライブ視聴をテレビの大画面で行うなら
[epcltvapp](https://github.com/daig0rian/epcltvapp) を入れてください。
リモコン操作のために作られたアプリで、接続先にこの EPGStation Server
（`http://127.0.0.1:8888/`）を指定すれば、そのまま使えます。

### 再起動したら

テレビの再起動後にどちらの APK も自動では開きません。mirakc の画面を開いてください。
mirakc の公開サーバーはチャンネル設定の準備後に、ユーザーが明示的に起動した場合だけ動きます。
EPGStation Server も必要なときに開いてください。

## あわせて使いたいもの

この2本だけでは、地デジ環境として片手落ちです。以下のプロジェクトのおかげで
成り立っています。

* **[EPGStation](https://github.com/l3tnun/EPGStation)**（l3tnun さん）——
  番組表・録画予約・録画管理。この APK が載せているのは、まさにこの
  EPGStation そのものです。Web の画面もサーバーも、上流の v2.10.0 を
  そのまま動かしています。
* **[epcltvapp](https://github.com/daig0rian/epcltvapp)**（daig0rian さん）——
  Android TV / Fire TV 向けの EPGStation クライアント。リモコンの十字キーだけで
  快適に録画を見られます。テレビ側の視聴体験はこのアプリにお任せするのが一番です。
* **[mirakc](https://github.com/mirakc/mirakc)** —— Mirakurun 互換の PVR
  バックエンド。この APK は上流 `3.4.86` を Android 向けに移植して使っています。
* **[mirakc-arib](https://github.com/mirakc/mirakc-arib)** —— mirakc の EPG ジョブと
  ストリームフィルターが使う上流コマンド群。APK には `0.24.38` を固定して
  同梱しています。
* **[libarib25](https://github.com/stz2012/libarib25)**（stz2012 さん）——
  B-CAS による復号。この APK に組み込んで使わせていただいています。
* **[siano-userland](https://github.com/Khronos31/siano-userland)** ——
  PX-S1UD をカーネルドライバなしで扱う CLI。この APK が同梱している
  `siano-ts` の本体です。

## 仕組み

### mirakc

`connectedDevice` のフォアグラウンドサービスが実行状態を管理し、画面はCompose製の
Android TV 向けUIです。スキャン用の一時実行と公開サーバーは別の runtime/cache を使います。
スキャン中は上流の自動EPGジョブを無効にし、loopbackだけで探索します。スキャンの完了後に
そのプロセスを終了してから、保存済み設定で公開サーバーを起動します。

USB 権限を要求する Siano の ID は次の3つです。

* `3275:0080`
* `187f:0600`
* `187f:0302`

Siano の USB ディスクリプタは Android 側の broker が世代ごとに管理し、上流 mirakc
の tuner command には専用の Siano adapter 経由で渡します。上表の各 PX4 product profile
は機器ごとの `px4d` instance と PX4 adapter が管理します。2ブリッジ機種は同じ14桁baseの
USBシリアル末尾 `1`/`2` のペア、1ブリッジ機種は15桁シリアルの単一USBデバイスとして
識別します。各 enclosure に1つの `px4d` と1本または2本の Android 管理FDを割り当てます。

対応チューナーの接続・切断では、生成済みの tuner 構成を反映するため上流
mirakc を再起動します。この間は配信や録画ジョブが中断する場合があります。
同一性が変わらない PX4 筐体の `px4d` とUSB所有権は維持しますが、他筐体を含む
上流処理の無停止は保証しません。B-CASカードリーダーの接続・切断やUSB権限変更も
reader世代を再取得するためmirakcを再起動します。権限未許可で接続された機器は、
許可後に再構成します。対応チューナー/カードリーダー以外のUSB機器では再起動しません。
PX4切断通知では再構成を待たずに該当筐体の旧USB世代を無効化し、同じUSBパスが
再利用されても旧FDを引き継ぎません。

```text
siano-ts --channel N --firmware <filesDir>/isdbt_rio.inp --fd 3
```

公開待ち受けは上流 mirakc の `0.0.0.0:40772` で認証はありません。地上波チャンネルは
スキャンで見つかったものを保存します。衛星の BS 26 チャンネルと CS 12 チャンネルは
同梱設定を使います。初期設定として固定の関東地上波チャンネルを勝手に有効化しません。
ファームウェアは linux-firmware の `isdbt_rio.inp`
（MD5 `9b762c1808fd8da81bbec3e24ddb04a3`）をビルド時に取得してチェックサムを
検証したもので、`LICENCE.siano` を隣に置いて同梱しています。`.so` には
焼き込んでいません。

#### 上流 mirakc の HTTP・EPG・ストリーム

HTTP API、チャンネル・サービス・番組情報、ライブストリーム、イベント通知は
上流 mirakc `3.4.86` が提供します。EPG のサービススキャン、時刻同期、番組表更新、
ストリームのサービス／番組フィルターは、固定した mirakc-arib `0.24.38` の
コマンドを上流ジョブから呼び出します。手動の初期GR探索も上流 mirakc の tuner scan APIを
使うため、APK独自の旧 HTTP サーバーや TS の SI パーサーは含みません。

#### B-CAS による復号

12seg の MPEG-2 は MULTI2 でスクランブルされています。CCID カードリーダーに
B-CAS カードを挿して USB 権限を与えると、Siano broker は外付けリーダーの fd を
native libarib25 filter に渡します。PX4 adapter のカード経路も同じ
[libarib25](https://github.com/stz2012/libarib25)（stz2012 版・Apache-2.0）を
共有します。pcscd は使わず、Android の USB 権限を得たネイティブ処理系がカード
リーダーを扱います。外付けリーダー1台につき Siano stream は同時に1本です。
PX-S1UD の 12seg 復号は以前の実装で実機確認済みですが、mirakc 0.4.0 では
移行後の再検証をしていません。

手元のリーダー（Identive/SCM SCR33xx v2.0）は `dwFeatures=0x000100ba` で交換
レベルが TPDU だったため、ネイティブ CCID transport は T=1 のブロック層
（NAD/PCB/LEN/INF/LRC、シーケンス番号、チェイニング、S-block の WTX/IFS 応答、
Time Extension 待ち）を扱います。これはカード transport の説明であり、サービス
の番組選択・ストリーム処理は上流 mirakc の責務です。

### EPGStation Server

[l3tnun/EPGStation](https://github.com/l3tnun/EPGStation) v2.10.0 を固定した、
非公式の Android 移植です。`dataSync` のフォアグラウンドサービスを起動し、
上流のサーバーとクライアントのビルド成果をアプリ専有の `filesDir` に展開して、
8888 番ポートで待ち受けます。

サービスを起動するたびに、上流の `config/config.yml.template` をそのまま
`config/config.yml` へコピーし、Mirakurun の URL、`port`、
`clientSocketioPort`、録画とサムネイルの保存先だけを書き換えます。SQLite の
データベースは内蔵の `filesDir` に置いたままです。USB 側のアプリ専用
ディレクトリは
`/storage/<UUID>/Android/data/dev.khronos31.epgstation.server/files/recorded`
になります。`subDirectory` は足していません。ログの YAML サンプルと上流の
`enc.js` のテンプレートは同梱しています。ffmpeg は含めていないので、無変換での
運用が想定する経路です。

常駐する supervisor は partial wake lock を保持し、ABI の合う
`libepgstation-node.so` を `nativeLibraryDir` から exec し、クラッシュ後は
backoff をかけて再起動し、稼働と停止を通知に出します。sqlite3 と
`@node-rs/crc32` のアドオンも同じ抽出済みネイティブライブラリで、
`node_modules` 配下のシンボリックリンクを通して Node から見えるようにしています。
APK には EPGStation の MIT ライセンス、Node のライセンス、生成した
`licenses/NOTICE.npm.txt` の依存一覧を含みます。`siano-ts`、ファームウェア、
recisdb は入っていません。

## ライセンス

このリポジトリで新規に作成したコードは Apache-2.0 (`LICENSE`) で提供します。
同梱している外部コンポーネントはそれぞれのライセンスに従います。特に mirakc
APKに別プロセスとして同梱する `siano-userland` の `siano-ts` は
GPL-2.0-or-later であり、対応するソースとライセンスは
[siano-userland](https://github.com/Khronos31/siano-userland) v0.1.9
（commit `d1f4e42810d5a2023ff4a6c31f798cb381026693`）にあります。
PX4 tuner codeはGPL-2.0-onlyの [px4-userland](https://github.com/Khronos31/px4-userland)
由来で、同プロジェクトのprovenance記録には `nns779/px4_drv` からの派生元と
ライセンスが記載されています。PX4 firmware抽出器はアプリのKotlin実装です。

## ビルド

`local.properties` は追跡していないので、自分の環境の SDK を指すものを置きます。

```text
sdk.dir=/path/to/android-sdk
```

JDK 17 と Android NDK r26 以降が要ります。Gradle タスクは SDK の `ndk/` 配下に
ある最も新しい NDK を自動で選ぶので、別の場所のものを使うなら
`ANDROID_NDK_HOME` を指定します。

`siano-ts` は別リポジトリ
[siano-userland](https://github.com/Khronos31/siano-userland) v0.1.9
（commit `d1f4e42810d5a2023ff4a6c31f798cb381026693`）からビルドします。
その場所は `-PsianoUserlandDir` で渡します（既定値は作者の環境の
`/config/GitHub/siano-userland`）。ビルドは両 ABI について
`scripts/build-android.sh` を呼び、検証済みの実行ファイルを mirakc の APK に
入れます。

上流 mirakc `3.4.86` と mirakc-arib `0.24.38` も、それぞれ固定した commit の
ソースから Android ABI ごとにビルドします。mirakc のビルドには
`tools/mirakc/build-android.sh`、mirakc-arib には `tools/mirakc-arib/build-android.sh`
を使います。

px4-userland v0.1.9 にある全16種類のUSB製品IDをAndroid側でも扱い、
固定したソースから `px4d` / `px4-ts` / `px4ctl` を作ります。ブリッジを2つ使うQ3系はUSB機器を2つ、
それ以外は1つ渡して起動します。固定したソースは
[px4-userland](https://github.com/Khronos31/px4-userland) commit
`cf38742618bb02db41a95def619fbff50e9eb0f3` です。製品IDの対応付け、オフラインテスト、APKへの同梱は、
その機種での受信・カード利用・複数台同時利用を実証するものではありません。実機での確認状況は
px4-userland の README と検証表に従い、Androidで確認していない機種の動作は未確認として扱います。

px4-userland の pinned source checkout は `-Ppx4UserlandDir` で渡せます（既定値は作者の環境の
`/config/GitHub/px4-userland`）。指定したcommitの clean checkoutにしてください。
PX4 firmware抽出に `px4_drv/fwtool` のビルドは不要です。Android APKで許可しているのは
上表の16 product IDsです。
複数の異なる筐体は、それぞれ独立した `--instance` token・runtime directory・`px4d` processを持ちます。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.0.12077973
./gradlew -PsianoUserlandDir=/path/to/siano-userland \
    -Ppx4UserlandDir=/path/to/px4-userland-v0.1.9 \
    :mirakc:assembleDebug :epgstation-server:assembleDebug
```

NDK か siano-userland のパスが見つからない場合、ネイティブのステップはどちらが
足りないかを明示して失敗します。生成される debug APK は
`mirakc/build/outputs/apk/debug/` と
`epgstation-server/build/outputs/apk/debug/` に置かれます。

EPGStation 側のタスクは上流の EPGStation v2.10.0 を固定して取得し、サーバーと
クライアントをビルドし、Android の ABI ごとに `sqlite3` をビルドして、JS の
payload を初回起動時にアプリ専有ストレージへ展開します。Node.js-mobile
v16.17.0 のランチャ（`libepgstation-node.so`）、`libnode.so`、
`libc++_shared.so`、ネイティブアドオンは `jniLibs` として同梱し、
`nativeLibraryDir` から実行します。**Android 10 以降は `filesDir` にコピーした
ELF を exec できない**ためです。payload を最初に用意するときだけ、ネットワーク
接続とホスト側の Node/npm、NDK、`patchelf` が必要になります。`patchelf` は
Node のアドオンに `libnode.so` への依存を追加するためのもので、これがないと
Android のリンカが N-API を解決できません。

APKのバージョンは各モジュールの `VERSION` が正本です。リリースタグは
`mirakc-vX.Y.Z` または `epgstation-server-vX.Y.Z` とし、タグを付けたモジュール
だけをCIがビルドして、そのAPKだけをReleaseに添付します。

APK は `armeabi-v7a` と `arm64-v8a` を両方含みます。前者は Google TV Streamer の
ユーザーランドが 32bit のみであるためです。`siano-ts` は Android Bionic の PIE
実行ファイルとしてビルドし、`/system/bin/linker` または `/system/bin/linker64` を
参照していることを検証しています（musl/glibc のバイナリではありません）。これも
`libsiano-ts.so` として同梱し、`nativeLibraryDir` から exec します
（`extractNativeLibs=true`）。
