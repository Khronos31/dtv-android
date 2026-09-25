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
| チューナー | PLEX PX-S1UD、同じ Siano チップの USB チューナー、または PLEX PX-Q3U4 |
| カードリーダー | CCID 対応の USB カードリーダー（Identive/SCM SCR33xx v2.0 で動作確認） |
| カード | B-CAS カード |
| その他 | USB ハブ（本体のポートが1つしかないため）、録画用の USB ストレージ（任意・exFAT） |

カードリーダーとカードが無くても 1seg 用の経路はありますが、画質は 320x180 です。
12seg のフル HD にはカードが要ります。なお、PX-S1UD の 12seg 動作はこの移行で
再検証済みとはしていません。

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

PX-Q3U4を使う場合、初回起動時にアプリが公式PLEX HTTPS配布物から必要なSYSを
取得し、同梱のGPL-2.0 `px4_drv/fwtool`（v0.2.1固定）で
`it930x-firmware.bin`を生成します。ZIP、SYS、生成済みfirmwareはAPKに含めず、
アプリ専用外部ファイル領域へ検証後に原子的にキャッシュします。既存の有効な
キャッシュはネットワークなしで再利用できます。取得や生成に失敗した場合はPX4
だけを無効化し、Sianoとmirakcは継続します。手動のfirmware provisionは不要です。

## 使い方

### 1. mirakc を起動して USB を許可する

**mirakc** を開くと1枚の画面が出ます。USB 権限、B-CAS リーダー、待ち受け、
上流 mirakc/PX4 の状態、直近のエラーを確認できます。

**Request USB permission** を押すと、Android の許可ダイアログが出ます。
チューナーとカードリーダーの分が続けて出るので、両方許可してください。
`B-CAS:` の行にリーダー名が出れば認識できています。

許可後のサービス起動と番組表更新は、上流 mirakc が設定したジョブで行います。
手動の EPG スキャン操作はありません。

### 2. EPGStation Server を起動する

**EPGStation Server** を開くと QR コードが出ます。初回は中身の展開に少し
時間がかかります。

`Mirakurun / mirakc base URL` は同じ端末の mirakc を指す
`http://127.0.0.1:40772/` が既定値です。テレビではなく別の場所で動いている
mirakc や Mirakurun を使いたい場合だけ書き換えて **Save base URL** を押します。

`Recording storage` に、録画の保存先の候補が空き容量つきで並びます。USB
ストレージを挿していればそれも出るので、押して選ぶと `✓` が付きます。
選ばなければ本体の内蔵ストレージに録りますが、容量に余裕が無いことが多いので
USB ストレージをおすすめします。

### APKを手動で更新する

各アプリの画面に **CHECK UPDATE** ボタンがあります。押したときだけ、そのアプリ
専用のGitHub Releasesを確認し、更新があればAPKをダウンロードしてAndroidの
インストール確認画面を開きます。初回だけ、設定からそのアプリに「不明なアプリの
インストール」を許可してください。起動時やバックグラウンドでは確認しません。

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

どちらの APK も自動では起動しません。テレビを再起動したあとは、mirakc と
EPGStation Server をもう一度開いてください。一度開けば常駐します。

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

`connectedDevice` のフォアグラウンドサービスが本体です。操作は通常の
フォーカス可能な Android のボタンなので、十字キーの移動が Leanback の
行構造に依存しません。

USB 権限を要求する Siano の ID は次の3つです。

* `3275:0080`
* `187f:0600`
* `187f:0302`

Siano の USB ディスクリプタは Android 側の broker が世代ごとに管理し、上流 mirakc
の tuner command には専用の Siano adapter 経由で渡します。PX-Q3U4 は同様に
`px4d` と PX4 adapter が管理します。

```text
siano-ts --channel N --firmware <filesDir>/isdbt_rio.inp --fd 3
```

待ち受けは上流 mirakc の `0.0.0.0:40772` で認証はありません。地上波の
チャンネルは T16、T21〜T27、T30、T31、T32 を設定してあり、HAOS の mirakc
アドオンと揃えてあります。
ファームウェアは linux-firmware の `isdbt_rio.inp`
（MD5 `9b762c1808fd8da81bbec3e24ddb04a3`）をビルド時に取得してチェックサムを
検証したもので、`LICENCE.siano` を隣に置いて同梱しています。`.so` には
焼き込んでいません。

#### 上流 mirakc の HTTP・EPG・ストリーム

HTTP API、チャンネル・サービス・番組情報、ライブストリーム、イベント通知は
上流 mirakc `3.4.86` が提供します。EPG のサービススキャン、時刻同期、番組表更新、
ストリームのサービス／番組フィルターは、固定した mirakc-arib `0.24.38` の
コマンドを上流ジョブから呼び出します。APK 独自の旧 HTTP サーバー、TS の SI
パーサー、手動スキャン処理は含みません。

#### B-CAS による復号

12seg の MPEG-2 は MULTI2 でスクランブルされています。CCID カードリーダーに
B-CAS カードを挿して USB 権限を与えると、Siano broker は外付けリーダーの fd を
native libarib25 filter に渡します。PX4 adapter のカード経路も同じ
[libarib25](https://github.com/stz2012/libarib25)（stz2012 版・Apache-2.0）を
共有します。pcscd は使わず、Android の USB 権限を得たネイティブ処理系がカード
リーダーを扱います。外付けリーダー1台につき Siano stream は同時に1本です。
PX-S1UD の 12seg 復号は、実機での再検証を完了しています。

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
[siano-userland](https://github.com/Khronos31/siano-userland) にあります。
PX4 firmware生成器`fwtool`は`nns779/px4_drv` v0.2.1
(commit `2b3f79b5bc5db56e8556bb28397f7d8f74b2adeb`)由来のGPL-2.0-onlyです。
対応するLICENSEとsource provenanceはAPKの`px4-fwtool/`に含まれます。

## ビルド

`local.properties` は追跡していないので、自分の環境の SDK を指すものを置きます。

```text
sdk.dir=/path/to/android-sdk
```

JDK 17 と Android NDK r26 以降が要ります。Gradle タスクは SDK の `ndk/` 配下に
ある最も新しい NDK を自動で選ぶので、別の場所のものを使うなら
`ANDROID_NDK_HOME` を指定します。

`siano-ts` は別リポジトリ
[siano-userland](https://github.com/Khronos31/siano-userland) からビルドします。
その場所は `-PsianoUserlandDir` で渡します（既定値は作者の環境の
`/config/GitHub/siano-userland`）。ビルドは両 ABI について
`scripts/build-android.sh` を呼び、検証済みの実行ファイルを mirakc の APK に
入れます。

上流 mirakc `3.4.86` と mirakc-arib `0.24.38` も、それぞれ固定した commit の
ソースから Android ABI ごとにビルドします。mirakc のビルドには
`tools/mirakc/build-android.sh`、mirakc-arib には `tools/mirakc-arib/build-android.sh`
を使います。

PX-Q3U4（`0511:084a`、2 デバイス）と、PX-MLT5PE（`0511:024e`）および DTV02A-5TS-P（`0511:924e`、いずれも 1 デバイス）の `px4d` は pinned な
[px4-userland](https://github.com/Khronos31/px4-userland) v0.1.4
（commit `7ec828578db47db4ed2a1b247d4c24fb722aedde`）を使います。PX4 firmware
生成器のビルドには [nns779/px4_drv](https://github.com/nns779/px4_drv) v0.2.1
（commit `2b3f79b5bc5db56e8556bb28397f7d8f74b2adeb`）の detached checkout も必要です。
それぞれ `-Ppx4UserlandDir` と `-Ppx4DrvDir` で渡せます（既定値は作者の環境の
`/config/GitHub/px4-userland` と `/config/GitHub/px4_drv`）。両方とも指定した
commit の clean checkout にしてください。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.0.12077973
./gradlew -PsianoUserlandDir=/path/to/siano-userland \
    -Ppx4UserlandDir=/path/to/px4-userland-v0.1.4 \
    -Ppx4DrvDir=/path/to/px4_drv-v0.2.1 \
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
