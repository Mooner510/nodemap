# NodeMap

개인용 Android 위치 타임라인 기록 앱입니다. 서버 없이 기기 안에 위치와 압정 이벤트를 누적하고, 날짜와 시각을 돌려 과거 동선을 확인합니다.

## 핵심 기능

- 백그라운드 위치 기록: Foreground Service + LocationManager, 정밀/균형/절전 프리셋
- 날짜별 타임랩스: 선택 시각 기준 최근 2시간은 선명하게, 2~12시간은 옅게 표시
- 5분 이상 기록 공백은 직선 추정 없이 지도 선을 끊어 표시
- 현재 시각/위치 수동 텍스트 압정
- SMS/MMS, 통화 기록/상대방, 접근 가능한 통화녹음 MediaStore 파일 연결
- NotificationListenerService + 다중 대상 앱 + 포함 regex + 제외 regex 규칙. 제외 우선, 빈 include는 전체 허용
- 알림 업데이트마다 새로운 압정 생성; 알림이 공개한 small/large icon 및 BigPicture는 암호화 첨부파일로 보존
- Galaxy 모드 및 루틴: 사용자 압정 템플릿을 Dynamic App Shortcut으로 노출
- MapLibre 오프라인 지도 영역 저장
- Reverse Geocoding opt-in + 기기 내 암호화 캐시
- 생체인증/기기 잠금
- 비밀번호 기반 AES-256-GCM `.nodemap` 백업/복원

## 로컬 데이터 보호

NodeMap은 서버를 사용하지 않습니다. DB에는 검색용 id/timestamp/day_key/event type만 평문으로 두고 실제 위치, 전화번호, SMS/MMS·알림 내용, 규칙, 장소명 payload는 Android Keystore AES-256-GCM으로 암호화합니다. 첨부파일도 암호화합니다. 휴대형 백업은 PBKDF2-HMAC-SHA256 310,000회 + AES-256-GCM을 사용합니다.

## Android 제약

개인용 APK를 전제로 합니다. READ_SMS/READ_CALL_LOG는 Android hard-restricted 권한이라 기기/설치 정책에 따라 거부될 수 있으며, 이 경우 해당 자동 압정만 비활성화됩니다. Force stop이나 위치 권한 해제는 우회하지 않습니다. 통화녹음은 NodeMap이 녹음하지 않고 MediaStore에 노출된 파일을 연결합니다. 알림 이미지는 Notification 객체가 공개한 것만 저장할 수 있습니다.

## 프로젝트

- Application ID / namespace: `kr.mooner510`
- compileSdk / targetSdk: 36
- minSdk: 31
- Kotlin: 2.3.21
- AGP: 9.3.0
- Jetpack Compose
- MapLibre Android OpenGL 13.4.1
- 기본 온라인 style: OpenFreeMap Liberty

GitHub Actions는 main push마다 debug APK를 빌드합니다. 실제 위치, 전화번호, 메시지, 알림, 녹음, signing key, `.nodemap` 백업은 커밋하지 마세요.
