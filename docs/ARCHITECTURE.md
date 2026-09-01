# Architecture

NodeMap은 local-first 단일 Android 앱입니다.

```text
LocationManager ─┐
SMS/MMS Provider ├─> NodeMapRepository ─> SQLite indices + AES-GCM payload
CallLog Provider ┤                         └> encrypted attachment store
Notifications ───┤
Routine Shortcut ┘

SQLite/attachments ─> Timeline UI / MapLibre
                   └> portable encrypted .nodemap backup
```

## Tracking

기본 BALANCED는 이동 중 약 8초/5m, 3분 이상 의미 있는 이동이 없으면 약 45초/15m를 요청합니다. 실제 전달 주기는 Android 위치 스택과 기기 상태가 결정합니다. 두 포인트 간격이 5분을 넘으면 UI는 경로를 연결하지 않습니다.

## Event model

`PIN_MANUAL`, `PIN_ROUTINE`, `PHONE_CALL`, `SMS`, `MMS`, `NOTIFICATION`, `SYSTEM`을 하나의 TimelineEvent로 정규화합니다. 동일 notification key의 업데이트도 `onNotificationPosted`마다 새 이벤트가 생성됩니다.

## Offline map

MapLibre OfflineRegion을 사용합니다. 위치/이벤트 기록은 지도 네트워크와 독립적입니다. 온라인에서 영역을 받은 뒤 해당 지도 리소스는 오프라인 사용을 목표로 보존합니다.

## Backup

`.nodemap` v1은 JSONL + attachment ZIP stream 전체를 password-derived AES-GCM으로 암호화합니다. Android Keystore 키 자체는 내보내지 않습니다.
