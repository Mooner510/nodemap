# Production Environment Variables

이 문서는 NodeMap Android production release에 필요한 환경변수만 기록한다.

## Project root / common

Production 공통 환경변수 없음.

## Android app

| 환경 변수명 | 값 예시 | 설명 | 필수 여부 |
|---|---|---|---|
| `NODEMAP_BACKUP_SERVER_URL` | `https://backup.example.com` | 실제 배포된 NodeMap encrypted-backup server의 public HTTPS URL을 입력 | Backup 기능 사용 시 |
| `NODEMAP_GOOGLE_SERVER_CLIENT_ID` | `123456789-abcdef.apps.googleusercontent.com` | Google Auth Platform/Google Cloud Console에서 Web OAuth client를 생성하고 Client ID를 복사. Server `GOOGLE_CLIENT_IDS`에도 동일 값을 등록 | Backup 로그인 사용 시 |

Android signing material은 일반 env가 아니라 canonical host signing store와 central Android production action이 관리한다.
