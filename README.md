# NodeMap

개인용 Android 위치 타임라인 기록 앱입니다. 위치와 압정 이벤트는 기기 안에 저장하고, 필요한 시점에만 사용자가 직접 암호화 온라인 백업을 만들 수 있습니다.

## 핵심 기능

- 백그라운드 위치 기록: Foreground Service + LocationManager, 정밀/균형/절전 프리셋
- 날짜별 타임랩스: 선택 시각 기준 최근 2시간은 선명하게, 2~12시간은 옅게 표시
- 기록 공백은 추정 연결 대신 지도에서 구분해 표시
- 현재 시각/위치 수동 텍스트 압정
- SMS/MMS, 통화 기록/상대방, 접근 가능한 통화녹음 MediaStore 파일 연결
- NotificationListenerService + 다중 대상 앱 + 포함 regex + 제외 regex 규칙
- Galaxy 모드 및 루틴: 사용자 압정 템플릿을 Dynamic App Shortcut으로 노출
- MapLibre 오프라인 지도 영역 저장
- Reverse Geocoding opt-in + 기기 내 암호화 캐시
- 생체인증/기기 잠금
- 비밀번호 기반 AES-256-GCM `.nodemap` 로컬 백업/복원
- Google 계정 기반 온라인 임시 백업: 계정당 1개, 약 14일 보관 후 15일째 00:00 KST 자동 삭제

## 온라인 백업

온라인 백업은 동기화 기능이 아닙니다. 사용자가 백업 버튼을 누를 때만 기존 `.nodemap` 포맷으로 기기에서 먼저 암호화한 뒤 서버에 업로드합니다. 서버에는 백업 비밀번호와 평문 데이터가 전달되지 않습니다.

Google 로그인은 Android Credential Manager를 사용합니다. 서버는 Google ID Token의 검증된 `sub`로 백업 소유자를 식별합니다. 앱 서명키가 바뀌더라도 동일한 Google 계정과 동일한 서버/Web OAuth Client ID를 사용하면 새 설치에서 같은 온라인 백업을 찾을 수 있습니다.

빌드 시 아래 값을 Gradle property 또는 환경변수로 설정해야 온라인 백업 탭이 활성화됩니다.

```text
NODEMAP_BACKUP_SERVER_URL=https://backup.example.com
NODEMAP_GOOGLE_SERVER_CLIENT_ID=xxxxxxxxxxxx-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
```

예:

```bash
./gradlew assembleRelease \
  -PNODEMAP_BACKUP_SERVER_URL=https://backup.example.com \
  -PNODEMAP_GOOGLE_SERVER_CLIENT_ID=xxxxxxxxxxxx-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
```

`NODEMAP_GOOGLE_SERVER_CLIENT_ID`는 secret이 아니지만 서버의 `GOOGLE_CLIENT_IDS`와 동일한 Web OAuth Client ID여야 합니다. Google Auth Platform에는 Android OAuth Client도 앱의 package name(`kr.mooner510`)과 실제 APK signing certificate SHA-1/SHA-256에 맞게 등록하세요.

## 로컬 데이터 보호

DB에는 검색용 id/timestamp/day_key/event type만 평문으로 두고 실제 위치, 전화번호, SMS/MMS·알림 내용, 규칙, 장소명 payload는 Android Keystore AES-256-GCM으로 암호화합니다. 첨부파일도 암호화합니다. 휴대형/온라인 백업은 PBKDF2-HMAC-SHA256 310,000회 + AES-256-GCM을 사용합니다.

## Android 제약

개인용 APK를 전제로 합니다. READ_SMS/READ_CALL_LOG는 Android hard-restricted 권한이라 기기/설치 정책에 따라 거부될 수 있으며, 이 경우 해당 자동 압정만 비활성화됩니다. Force stop이나 위치 권한 해제는 우회하지 않습니다. 통화녹음은 NodeMap이 녹음하지 않고 MediaStore에 노출된 파일을 연결합니다. 알림 이미지는 Notification 객체가 공개한 것만 저장할 수 있습니다.

온라인 백업에서 사용자 데이터는 복원할 수 있지만 Android 시스템 권한 자체는 복원할 수 없습니다. 새 설치에서는 위치/알림/SMS/통화기록 등 필요한 권한을 다시 허용해야 합니다.

## 프로젝트

- Application ID / namespace: `kr.mooner510`
- compileSdk / targetSdk: 36
- minSdk: 31
- Kotlin: 2.3.21
- AGP: 9.3.0
- Jetpack Compose
- AndroidX Credentials 1.6.0
- Google ID SDK 1.2.0
- MapLibre Android OpenGL 13.4.1
- 기본 온라인 style: OpenFreeMap Liberty

실제 위치, 전화번호, 메시지, 알림, 녹음, signing key, OAuth secret, `.nodemap` 백업은 커밋하지 마세요.
