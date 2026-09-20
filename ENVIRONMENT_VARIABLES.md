# Production Environment Variables

이 문서는 NodeMap Android production release에 필요한 환경변수만 기록한다.

## Project root / common

Production 공통 환경변수 없음.

## Android app

| 환경 변수명 | 값 예시 | 설명 | 필수 여부 |
|---|---|---|---|
| `NODEMAP_BACKUP_SERVER_URL` | `https://backup.example.com` | 앱이 사용하는 NodeMap encrypted-backup server public URL | Backup 기능 사용 시 |
| `NODEMAP_GOOGLE_SERVER_CLIENT_ID` | `123456789-abcdef.apps.googleusercontent.com` | Google ID token의 server/Web OAuth client ID. Server `GOOGLE_CLIENT_IDS`와 일치해야 함 | Backup 로그인 사용 시 |

Android signing material은 일반 env가 아니라 canonical host signing store와 central Android production action이 관리한다.
