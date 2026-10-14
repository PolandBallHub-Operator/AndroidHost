# AndroidHostApp
<p align="center">
  <img src="icon.png" width="300" alt="ロゴ">
</p>
Android端末を**gnirehtetのホスト／リレー側**として動かすテンプレートです。受信側Androidには従来のgnirehtetクライアントAPKを別途インストールして使います。

# DNSは「固定」にしないと、接続が不安定になります。

## 役割と接続方式

```text
送信側Android（本アプリ）                           受信側Android（別APK）
┌────────────────────────────┐       USB OTG       ┌────────────────────────┐
│ Flashbot ADB host           │◄──────────────────►│ adbd + gnirehtet client │
│  ・USB ADB認証              │   ADB reverse      │  ・VpnService / TUN      │
│  ・relay 127.0.0.1:31416    │◄───────────────────│  ・LocalSocket           │
│  ・通常ネットワークで外部接続│   TCP 31416         └────────────────────────┘
└────────────────────────────┘
```

アプリは送信側端末にリレーサーバーを起動し、USB ADBで接続した受信側端末に `adb reverse localabstract:gnirehtet tcp:31416` 相当の転送とクライアント起動要求を行います。受信側APKは変更せず、初回起動時に表示されるVPN許可を受信側で承認してください。

**このホストアプリ自身には `VpnService` を実装していません。** TUNを作ってパケットを捕捉するのは受信側のgnirehtetクライアントです。ホスト側へVPN機能を重ねると役割が逆になり、今回の要件であるホスト／リレー動作と一致しません。

リレーはgnirehtet Java実装をベースにしており、受信側クライアントと同じクライアントID・IPパケットプロトコルを使います。ライブラリは送信側Androidの通常のネットワーク経路から外向き接続を行います。待受はループバックのままにし、外部LANには公開しません。

## 動作環境

- minSdk 28、targetSdk 34、compileSdk 35
- Kotlin + Java
- Android frameworkの `Theme.Material.Light` と標準 `Switch` / `TextView` / `Button` のみ。Material Components / Material 3依存なし。
- USB OTG／USB hostに対応した送信側端末
- 相手機（受信側）でUSBデバッグが有効になっており、送信側端末からのADB RSA鍵を許可していること
- 相手機にGenymobile gnirehtetのクライアントAPKがインストールされていること

## ビルド

JDK 17以上、Android SDK Command-line Tools、Android Platform 35、Build Tools 35.0.0 が必要です。SDKをセットアップした後、次を実行します。

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools"

./gradlew assembleDebug
```

生成物は `app/build/outputs/apk/debug/app-debug.apk` です。送信側端末へインストールします。

## 実機での起動

1. 相手機（受信側）にgnirehtetクライアントAPKをインストールします。
2. 相手機で「開発者向けオプション」→「USBデバッグ」を有効にします。
3. USB OTG対応の送信側端末と相手機をデータ対応ケーブルで接続します。必要なら相手機のUSB設定を「ファイル転送」等に切り替え、ADBインターフェースが提示されるようにします。
4. 送信側端末でAndroidHostAppを開き、「USB端末を再検索」を押します。
5. 「Hostリレーを開始」をONにし、USBデバイス権限を許可します。
6. 相手機にADBデバッグ許可ダイアログが出たら、接続元を確認したうえで許可します。
7. 受信側でVPN接続確認ダイアログが出たら許可します。以後、Host画面に状態と双方向のリレーバイト数が表示されます。

終了時はスイッチをOFFにします。ADB接続を維持できる間は受信側クライアントを停止し、reverse設定を解除します。USBが抜かれた場合はホストサービスも停止します。

## 対応範囲と注意

- 上流gnirehtetの仕様を継承し、ここではIPv4のTCP/UDP転送を対象にしています。IPv6中継は含みません。
- 対象のgnirehtetは積極的に保守されていないため、受信側Androidの新しいOSでは別途動作確認が必要です。
- Flashbot/AdbLibは古いADB実装です。APKのビルド成功はUSB実機接続、RSA承認、`reverse:forward` 応答、VPN画面遷移の実機検証を意味しません。端末・OSベンダーによるADB USB descriptor差異も考慮してください。
- このテンプレートはUSBホスト端末1台と受信側端末1台を想定しています。複数クライアント管理、ADB再接続、画面回転を跨ぐ選択保持、接続監視の自動復旧は最小実装の範囲外です。
- ADBは相手機の開発者向け機能です。信頼できるUSB接続先だけを許可してください。ADB鍵はアプリの内部ストレージに保存されます。

## OSS

アプリ内の「オープンソースライセンス」からライセンス本文を表示できます。ビルドにも以下の原文を同梱しています。

- gnirehtet relay: Apache License 2.0（Genymobile）
- flashbot: BSD 3-Clause（wuxudong）
- flashbotに含まれるAdbLib由来部分: BSD（Cameron Gutman）

`licenses/` と `third_party/` のライセンス／由来情報を配布時にも維持してください。
