# 코인 UTXO

안드로이드용 비트코인 지갑입니다. 화면 언어는 한국어이고, 키·디스크립터·PSBT는 [Bitcoin Dev Kit](https://bitcoindevkit.org/) (`bdk-android`)만 사용합니다.

## 감사받지 않은 소프트웨어

이 앱은 보안 감사를 받지 않았습니다. 메인넷은 실제 비트코인이며, 잘못된 거래·잘못된 백업·소프트웨어 결함으로 코인을 영구히 잃을 수 있습니다. 개발자는 그 손실에 책임지지 않습니다. 복구 문구는 종이에 적고, 잃어도 되는 금액만 쓰세요. 기본 네트워크는 시그넷입니다. 메인넷은 설정에서 경고를 확인한 뒤에만 켤 수 있습니다.

## 기능

- 주소, BIP84 계정 xpub/zpub/vpub, 공개 디스크립터 QR을 읽어 잔액과 UTXO를 조회합니다. 개인키는 저장하지 않습니다.
- WIF 또는 BIP39 니모닉 QR·붙여넣기로 가져옵니다. 니모닉은 BIP84 네이티브 세그윗입니다.
- 새 지갑은 12단어 BIP39와 BIP84입니다. 백업 화면에는 복사 버튼이 없고, 두 단어를 맞춰야 저장됩니다.
- 지갑 화면에서 UTXO(txid:vout, 금액, 컨펌)를 고르고, 그 입력만으로 보냅니다. 주소는 직접 입력하거나 QR로 읽고, 금액 또는 전액, 수수료율을 지정하면 수수료와 거스름을 보여 준 뒤 기기에서 서명하고 방송합니다.
- 니모닉과 WIF는 Android Keystore(`EncryptedSharedPreferences`)로 암호화합니다. 평문 저장소에는 공개 디스크립터만 둡니다. 비밀 화면은 화면 캡처를 막습니다.
- 체인 데이터는 Esplora입니다. 기본값은 시그넷 `https://mempool.space/signet/api`이고, 설정에서 서버를 바꿀 수 있습니다.

## 빌드

- JDK 17 이상 (바이트코드 타깃은 17)
- Android SDK 35, build-tools 35.0.0
- `ANDROID_HOME`에 SDK 경로

```bash
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew test
./gradlew assembleDebug
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 만들어집니다.

기기에 설치:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서 이 디렉터리를 열고 Run 해도 됩니다. `minSdk`는 26입니다.

단위 테스트는 UTXO 선택이 고른 집합 밖으로 늘어나지 않는지, 수수료·거스름·더스트, 금액 파싱, 주소 검증을 확인합니다. `./gradlew test`가 그 테스트를 실행합니다.
