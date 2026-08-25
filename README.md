# dtv-android

Khronos31 の地デジ環境を Android TV とスマートフォンに載せるための APK 群。
常駐チューナー（`mirakc`）と常駐 EPGStation サーバーの2本。番組表は
EPGStation の Web UI をスマートフォンや PC から開いて使う。

| 表示名 | Gradle モジュール | applicationId |
| --- | --- | --- |
| mirakc | `:mirakc` | `dev.khronos31.mirakc` |
| EPGStation Server | `:epgstation-server` | `dev.khronos31.epgstation.server` |

## ビルド

`local.properties` は追跡していないので、自分の環境の SDK を指すものを置く。

```text
sdk.dir=/path/to/android-sdk
```

JDK 17 と Android NDK r26 以降が要る。Gradle タスクは SDK の `ndk/` 配下にある
最も新しい NDK を自動で選ぶので、別の場所のものを使うなら `ANDROID_NDK_HOME` を
指定する。

`siano-ts` は別リポジトリ
[siano-userland](https://github.com/Khronos31/siano-userland) からビルドする。
その場所は `-PsianoUserlandDir` で渡す（既定値は作者の環境の
`/config/GitHub/siano-userland`）。ビルドは両 ABI について
`scripts/build-android.sh` を呼び、検証済みの実行ファイルを mirakc の APK に
入れる。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.0.12077973
./gradlew -PsianoUserlandDir=/path/to/siano-userland \
    :mirakc:assembleDebug :epgstation-server:assembleDebug
```

NDK か siano-userland のパスが見つからない場合、ネイティブのステップはどちらが
足りないかを明示して失敗する。生成される debug APK は
`mirakc/build/outputs/apk/debug/` と
`epgstation-server/build/outputs/apk/debug/` に置かれる。

EPGStation 側のタスクは上流の [l3tnun/EPGStation](https://github.com/l3tnun/EPGStation)
v2.10.0 を固定して取得し、サーバーとクライアントをビルドし、Android の ABI ごとに
`sqlite3` をビルドして、JS の payload を初回起動時にアプリ専有ストレージへ展開する。
Node.js-mobile v16.17.0 のランチャ（`libepgstation-node.so`）、`libnode.so`、
`libc++_shared.so`、ネイティブアドオンは `jniLibs` として同梱し、
`nativeLibraryDir` から実行する。**Android 10 以降は `filesDir` にコピーした ELF を
exec できない**ためである。payload を最初に用意するときだけ、ネットワーク接続と
ホスト側の Node/npm、NDK、`patchelf` が必要になる。`patchelf` は Node のアドオンに
`libnode.so` への依存を追加するためのもので、これがないと Android のリンカが
N-API を解決できない。

APK は `armeabi-v7a` と `arm64-v8a` を両方含む。前者は Google TV Streamer の
ユーザーランドが 32bit のみであるため。`siano-ts` は Android Bionic の PIE 実行
ファイルとしてビルドし、`/system/bin/linker` または `/system/bin/linker64` を
参照していることを検証している（musl/glibc のバイナリではない）。これも
`libsiano-ts.so` として同梱し、`nativeLibraryDir` から exec する
（`extractNativeLibs=true`）。

## バージョンとリリース

リポジトリ直下の `VERSION` が唯一の正本で、両モジュールの `versionName` は
そこから読む。`versionCode` は `major*10000 + minor*100 + patch` で導出する
（`0.1.0` なら `100`）。**モジュールのビルドスクリプトにバージョンを直書きしない。**

`tools/scripts/release_version.py` が同期を検査する。

```sh
python3 tools/scripts/release_version.py check                  # 各ファイルの整合
python3 tools/scripts/release_version.py check --tag v0.1.0     # タグとの一致も見る
python3 tools/scripts/release_version.py check --apk path/to.apk # 生成物の中身も見る
python3 tools/scripts/release_version.py print --code           # 導出した versionCode
```

`check` は、直書きへの逆戻り・タグとの食い違い・ビルドした APK に埋まった
`versionName` / `versionCode` の食い違いを、それぞれ別々に落とす。CI は push
ごとにこれを実行する。

リリースは GitHub Actions の **Release** ワークフローを `main` から
`workflow_dispatch` で起動する。入力は `v` を付けないバージョン（`0.1.1` など）。
ワークフローが `VERSION` の書き換え・コミット・タグ付け・`main` とタグの
atomic push までを一手に行うので、タグと中身がずれた状態を作れない。既存の
タグが同じコミットに付いていて Release だけ無い場合は、その続きから再開する。

APK には署名が要る。リポジトリの Secrets に以下を設定しておく。未設定なら
リリースは失敗する（未署名の APK を公開しないため）。

| Secret | 中身 |
|---|---|
| `KEYSTORE_BASE64` | リリース用 keystore を base64 にしたもの |
| `KEYSTORE_PASSWORD` | keystore のパスワード |
| `KEY_ALIAS` | 鍵の別名 |
| `KEY_PASSWORD` | 鍵のパスワード |

## mirakc

`mirakc` を起動すると `connectedDevice` のフォアグラウンドサービスが立ち上がる。
画面は1枚だけで、USB 権限・待ち受けアドレス・現在のストリーム・直近のエラーを
表示する。操作は通常のフォーカス可能な Android のボタンなので、TV の十字キー
操作が Leanback の行構造に依存しない。

サービスは以下の Siano の ID について Android の USB 権限を要求する。

* `3275:0080`
* `187f:0600`
* `187f:0302`

ストリームが動いている間、サービスは複製した USB の `ParcelFileDescriptor` を
保持し続ける。アプリ内の小さな JNI ランチャがそのディスクリプタを fd 3 として
次のように渡す。

```text
siano-ts --channel N --firmware <filesDir>/isdbt_rio.inp --fd 3
```

待ち受けは `0.0.0.0:40772` で認証はない。地上波のチャンネルは T16、T21〜T27、
T30、T31、T32 を設定してあり、HAOS の mirakc アドオンと揃えてある。ファーム
ウェアは linux-firmware の `isdbt_rio.inp`（MD5 `9b762c1808fd8da81bbec3e24ddb04a3`）
をビルド時に取得してチェックサムを検証したもので、`LICENCE.siano` を隣に置いて
同梱している。`.so` に焼き込んではいない。

### 実装済みの HTTP API

EPGStation から使うぶんに必要な範囲だけを mirakc 互換で実装している
（Mirakurun の `/api/config` まで真似たクローンではない）。

* `GET /api/version` — Mirakurun 形式の `current` と `latest`
* `GET /api/status` — `{}`
* `GET /api/docs` — `mirakurun.Client` が読む OpenAPI
* `GET /api/channels` — 設定した GR の一覧と、発見済みのサービス
* `GET /api/services`、`GET /api/services/{id}`
* `GET /api/programs`、`GET /api/programs/{id}`
* `GET /api/services/{id}/programs`
* `GET /events` — SSE の `epg.programs-updated` と `onair.program-changed`
* `GET /api/tuners` — Siano チューナーの状態
* `GET /api/channels/GR/{channel}/stream` — `siano-ts` の生 MPEG-TS
* `GET /api/services/{id}/stream`、`GET /api/programs/{id}/stream`

USB 権限が下りるとサービスは設定済みの GR チャンネルを1つずつ約16秒ずつ走査し、
TS から SDT/EIT を解析する。ライブのストリームも同じパーサに通る。名前の解釈は
ARIB STD-B24 に従う。`/api/services/{id}/stream` と
`/api/programs/{id}/stream` は1番組だけを残す。recisdb と ffmpeg はこの APK には
入れていない。録画の UI は EPGStation の担当のまま。

### B-CAS による復号

12seg の MPEG-2 は MULTI2 でスクランブルされている。Identive などの CCID
カードリーダに B-CAS カードを挿し、USB 権限を与えると、`siano-ts` の出力を
libarib25（stz2012 版・Apache-2.0）に通してから配信する。pcscd は使わず、
UsbManager から渡された fd を usbfs の ioctl で直接叩いている。

手元のリーダ（Identive/SCM SCR33xx v2.0）は `dwFeatures=0x000100ba` で交換
レベルが TPDU だったため、生の APDU は通らない。T=1 のブロック層
（NAD/PCB/LEN/INF/LRC、シーケンス番号、双方向のチェイニング、S-block の
WTX/IFS 応答、Time Extension 待ち）は自前で実装している。IFSC は
GetParameters から読み、IFSD は 254 を交渉する。

カードが無い、あるいは復号に失敗した場合は、同じ物理チャンネルに乗っている
スクランブルなしの 1seg H.264 に差し替える。差し替えるかどうかは PMT の CA
記述子の有無ではなく、実測したスクランブルビットで判断している（復号が
成功しても CA 記述子は PMT に残るため）。

## EPGStation Server

上流の [l3tnun/EPGStation](https://github.com/l3tnun/EPGStation) v2.10.0 を
固定した、非公式の Android 移植。APK は `dataSync` のフォアグラウンドサービスを
起動し、上流のサーバーとクライアントのビルド成果をアプリ専有の `filesDir` に
展開して、8888 番ポートで待ち受ける。
ステータス画面には `http://<LAN-IP>:8888/` の QR コードを出すので、スマート
フォンや PC から素の EPGStation の UI を開ける。あの SPA を十字キーで操作する
ことは想定していない。ユーザーが設定できるのは他に Mirakurun/mirakc のベース
URL だけで、既定値は以下。

```text
http://127.0.0.1:40772/
```

サービスを起動するたびに、上流の `config/config.yml.template` をそのまま
`config/config.yml` へコピーし、Mirakurun の URL、`port`、
`clientSocketioPort`、録画とサムネイルの保存先だけを書き換える。録画先は
内蔵ストレージか、取り外し可能な USB ボリューム（exFAT）を選べる。SQLite の
データベースは内蔵の `filesDir` に置いたまま。ステータス画面は各ボリュームを
空き容量つきで一覧する。USB 側のアプリ専用ディレクトリは
`/storage/<UUID>/Android/data/dev.khronos31.epgstation.server/files/recorded`。
`subDirectory` は足していない。ログの YAML サンプルと上流の `enc.js` の
テンプレートは同梱している。ffmpeg は意図的に含めていないので、無変換での
運用が想定する経路になる。

常駐する supervisor は partial wake lock を保持し、ABI の合う
`libepgstation-node.so` を `nativeLibraryDir` から exec し、クラッシュ後は
backoff をかけて再起動し、稼働/停止を通知に出す。sqlite3 と `@node-rs/crc32`
のアドオンも同じ抽出済みネイティブライブラリで、`node_modules` 配下の
シンボリックリンクを通して Node から見えるようにしている。APK には
EPGStation の MIT ライセンス、Node のライセンス、生成した
`licenses/NOTICE.npm.txt` の依存一覧を含む。`siano-ts`、ファームウェア、
recisdb は入っていない。

TV 側でのライブ視聴・ワンタップ録画・録画再生は
[epcltvapp](https://github.com/daig0rian/epcltvapp) を使っている。ここの APK は
それを置き換えるものではない。
