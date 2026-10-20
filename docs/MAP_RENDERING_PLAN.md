# 타임라인 지도 렌더링 개선 — 선행 조사

다음 에이전트가 바로 구현할 수 있도록 정리한 조사 결과입니다. 아직 코드는 바뀌지 않았습니다.
(`gradlew`가 저장소에 없어 빌드·실측은 하지 못했습니다. 아래 비용 분석은 코드 읽기 기반이므로 구현 전 프로파일링으로 확인하세요.)

## 현재 구조 (`ui/TimelineScreen.kt`)

- `TimelineMap` → `AndroidView.update`가 recomposition마다 `render(map)` → `drawTimelineMap(...)` 호출.
- 스크럽 중에는 `onPreview`가 `previewTime`을 바꾸므로 `renderTime`이 바뀔 때마다(드래그 프레임마다) 전체가 다시 그려짐.
- `drawTimelineMap` 한 번에 하는 일:
  1. `map.clear()` — 모든 `Marker` 제거 (경로는 GeoJSON 소스/레이어라 유지됨).
  2. `points.inTimeRange(...)` (이분 탐색, 저렴) 후 `buildTimelineRouteData` — **구간 전체를 `sortedBy`로 재정렬**하고 세그먼트를 다시 계산.
  3. `routeGeoJson` — 포인트 전체를 **문자열로 직렬화**하여 소스 3개(old/detail/gap)에 `setGeoJson` (메인 스레드).
  4. 현재 위치·선택 시각·핀마다 `map.addMarker(MarkerOptions…)`.
- 부가: `render`가 `fitCamera`(스크럽 중 매 프레임 `moveCamera(newLatLngBounds)`)도 호출.

## 비용 원인 (추정, 큰 순서)

1. **`map.clear()` + `addMarker` 반복**: `Marker`는 레거시 annotation API로, 마커마다 Java 객체·아이콘 등록·내부 심볼 갱신이 일어납니다. 핀이 많을수록 프레임당 O(핀 수)로 증가.
2. **GeoJSON 문자열 전체 재생성 + 파싱**: 이동한 거리가 길수록 문자열이 커지고, MapLibre가 이를 다시 파싱·타일화합니다. 스크럽 시 윈도우 경계만 조금 이동해도 전부 다시 만듭니다.
3. **`sortedBy`**: `points`는 이미 timestamp 오름차순(`lowerBoundTimestamp` 주석 참고)이므로 불필요한 O(n log n).
4. **카메라 이동 매 프레임** (`fitCamera(map, false)`): 스크럽 중 줌/경계가 계속 바뀌어 타일 재요청을 유발.

## 권장 구현 (큰 리팩터링 순서)

### A. 마커를 SymbolLayer로 교체 (핵심)
`org.maplibre.gl:android-sdk-opengl:13.4.1` 기준, 별도 플러그인 없이 가능합니다.

- 스타일 로드 후 1회: 아이콘 등록 `style.addImage(id, bitmap)` — `makeTimelineDotIcon`의 `Bitmap`을 그대로 사용(현재는 `IconFactory.fromBitmap`로 `MapIcon` 감쌈. `Bitmap`을 바로 넘기도록 변경). 핀 타입별 `"pin:{id}:{colorHex}"`, 현재 위치 `"current"`, 선택 시각 `"selected"`.
- 소스 1개: `GeoJsonSource("markers-source")`, 레이어 1개: `SymbolLayer("markers-layer", "markers-source")` with `iconImage(Expression.get("icon"))`, `iconAllowOverlap(true)`, `iconIgnorePlacement(true)`.
- 각 피처는 `Point` + properties `{ icon, title, snippet }`. 갱신은 `source.setGeoJson(FeatureCollection)` 한 번.
- 스타일이 바뀌면(`setStyle`) 소스·레이어·이미지가 사라지므로 `setStyle` 콜백(`appliedStyle` 갱신 지점)에서 재생성. 지금 `pinIconCache.clear()`를 하는 자리.
- 레이어는 경로 레이어 **위**에 추가(`addLayer`는 맨 위에 올라감, 경로 레이어 생성 후 추가하면 됨).
- 말풍선(`title`/`snippet`)은 `Marker`의 InfoWindow가 사라지므로 `map.addOnMapClickListener`에서 `map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), "markers-layer")`로 탭된 피처를 찾아 Compose 쪽 카드/Snackbar로 표시. 이 UX 결정이 유일한 동작 변화.
- 대안: 공식 `maplibre-annotation-plugin`의 `SymbolManager`도 있지만 내부적으로 같은 방식이며 의존성이 늘어 비권장.

### B. 경로 GeoJSON 갱신 최소화
- `buildTimelineRouteData`의 `sortedBy` 제거(입력이 이미 정렬됨). 정렬이 보장 안 되는 호출부가 있는지만 확인.
- 스크럽 중에는 `totalStart`(= renderTime − totalWindow)가 매 프레임 이동하므로 전체 재생성이 불가피. 대안: **하루 전체 경로를 소스에 한 번만 넣고** 각 피처에 `t`(timestamp) 속성을 달아 레이어 `filter(Expression.all(gte(get("t"), start), lte(get("t"), end)))`와 `lineColor`/`lineOpacity` 표현식으로 old/detail을 구분. 시간 이동은 `layer.setFilter(...)`만 바꾸면 되어 지오메트리 재파싱이 없음. 포인트별 속성이 필요하므로 세그먼트를 포인트 쌍(2점 LineString) 대신 `line-gradient`/분할 피처로 쪼개는 설계가 필요 — 가장 큰 개선 폭이지만 작업량도 가장 큼. A만으로 부족할 때 진행.
- 최소 대안: `points`/`renderTime`이 같으면 스킵하는 키 비교 + 스크럽 중 `setGeoJson` 스로틀(예: 50~100ms) 또는 `Choreographer` 프레임당 1회.

### C. 호출 빈도 줄이기
- `update` 블록은 recomposition마다 호출되므로, 입력(`points` 참조, `renderTime`, `pins`, 윈도우 설정)이 이전 렌더와 같으면 `render` 생략.
- 스크럽 중 `fitCamera(map, false)`는 프레임마다 하지 말고 `scrubbing` 종료(`fitRequest`) 시에만 하거나 스로틀.
- 무거운 계산(`buildTimelineRouteData` + `routeGeoJson`)은 `Dispatchers.Default`에서 수행하고 결과만 메인에서 `setGeoJson`. `GeoJsonSource.setGeoJson(String)`은 내부적으로 비동기 파싱이지만 문자열 생성은 호출 스레드 비용.

## 검증 방법
- 변경 전후로 Perfetto/`adb shell dumpsys gfxinfo kr.mooner510 framestats`로 스크럽 중 프레임 시간 비교(핀 수 많은 날, 포인트 수천 개인 날 각각).
- 확인할 회귀: 스타일 변경 후 마커 복원, 다크/라이트 스타일 전환, 핀 탭 시 정보 표시, `ready` 스피너 해제 타이밍.

## 같이 정리할 것
- `MapIcon`/`IconFactory`/`MarkerOptions` import와 `pinIconCache`(`MapIcon` 타입)는 A 적용 후 `Bitmap` 기반으로 교체.
