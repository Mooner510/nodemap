import type { Bridge, BridgeEvents, CloudBackupMetadata, DownloadLogEntry, EditablePinType, OfflineDownload, OfflineRegion, Permissions, Pin, PinRule, PinType, Settings, TimelineData, TrackPoint } from './types'
import { DAY, HOUR, MINUTE, dayKey, startOfDay } from './time'

const PIN_TYPES: PinType[] = [
  { id: 'general', name: '일반', color: '#3182F6', icon: 'PIN' },
  { id: 'call', name: '전화', color: '#22C55E', icon: 'PHONE' },
  { id: 'sms', name: '문자', color: '#F59E0B', icon: 'MESSAGE' },
  { id: 'noti', name: '알림', color: '#8B5CF6', icon: 'NOTIFICATION' },
  { id: 'food', name: '맛집', color: '#EF4444', icon: 'STAR' },
]

let settings: Settings = {
  onboardingCompleted: new URLSearchParams(location.search).get('onboarding') === null,
  trackingEnabled: true,
  trackingPreset: 'BALANCED',
  reverseGeocodingEnabled: false,
  biometricLockEnabled: true,
  autoStartAfterBoot: true,
  mapStyleUri: 'https://tiles.openfreemap.org/styles/liberty',
  dialTickMinutes: 5,
  dialRadiusMinutes: 180,
  detailWindowMinutes: 120,
  totalWindowMinutes: 720,
  cameraWindowMinutes: 30,
  autoOfflineMaps: true,
}

const base = { titleOverride: '', bodyTemplate: '', appMode: 'INCLUDE_SELECTED', packages: [], join: 'ALL', conditions: [], hidden: false } as const
let rules: PinRule[] = [
  { ...base, id: 'r-call', name: '전화 기록', source: 'PHONE_CALL', system: true, enabled: true, priority: 10, typeId: 'call', usage: 31, packages: [], conditions: [] },
  { ...base, id: 'r-sms', name: '문자 메시지', source: 'SMS', system: true, enabled: true, priority: 20, typeId: 'sms', usage: 54, packages: [], conditions: [] },
  {
    ...base, id: 'r-pay', name: '결제 알림', source: 'NOTIFICATION', system: false, enabled: true, priority: 100, typeId: 'noti', usage: 12,
    packages: ['com.kakaobank.channel', 'viva.republica.toss', 'com.shinhan.sbanking'],
    conditions: [{ id: 'c1', field: 'TEXT', operator: 'CONTAINS', value: '결제' }, { id: 'c2', field: 'TEXT', operator: 'NOT_CONTAINS', value: '취소' }],
  },
  {
    ...base, id: 'r-deliv', name: '배달 도착', source: 'NOTIFICATION', system: false, enabled: false, priority: 110, typeId: 'noti', usage: 0,
    packages: ['com.sampleapp', 'com.coupang.mobile.eats'], conditions: [{ id: 'c3', field: 'BODY', operator: 'REGEX', value: '도착(했|완료)' }],
  },
]
const typeUsage: Record<string, number> = { general: 3, call: 31, sms: 54, noti: 12 }
const APPS = [
  ['배달의민족', 'com.sampleapp'], ['쿠팡이츠', 'com.coupang.mobile.eats'], ['카카오뱅크', 'com.kakaobank.channel'], ['토스', 'viva.republica.toss'],
  ['신한 SOL뱅크', 'com.shinhan.sbanking'], ['카카오톡', 'com.kakao.talk'], ['네이버', 'com.nhn.android.search'], ['Gmail', 'com.google.android.gm'],
].map(([label, packageName]) => ({ label, packageName }))

let permissions: Permissions = { location: true, backgroundLocation: false, sensitive: true, notificationAccess: false }
let offlineRegions: OfflineRegion[] = [
  { id: 3, name: '다닌 곳 (자동 저장)', completed: 2310, required: 2310, bytes: 58 * 1024 * 1024, auto: true },
  { id: 1, name: '최근 이동 범위', completed: 1840, required: 1840, bytes: 46 * 1024 * 1024 },
  { id: 2, name: '중단된 이동 범위', completed: 620, required: 2100, bytes: 15 * 1024 * 1024 },
]
let cloud: CloudBackupMetadata | null = null

// Deterministic PRNG so the mock days look the same on every reload.
function rng(seed: number) {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 2 ** 32
  }
}

const HOME: [number, number] = [37.4979, 127.0276]
const WORK: [number, number] = [37.5665, 126.978]
const LUNCH: [number, number] = [37.5636, 126.9822]

function leg(out: TrackPoint[], t0: number, from: [number, number], to: [number, number], minutes: number, rand: () => number) {
  const n = Math.max(2, Math.round((minutes * 60) / 12))
  for (let i = 1; i <= n; i++) {
    const f = i / n
    const wobble = Math.sin(f * Math.PI * 3) * 0.004 * (rand() * 0.5 + 0.5)
    out.push([
      t0 + (i * minutes * MINUTE) / n,
      from[0] + (to[0] - from[0]) * f + wobble * 0.4 + (rand() - 0.5) * 0.0004,
      from[1] + (to[1] - from[1]) * f - wobble + (rand() - 0.5) * 0.0004,
      6 + rand() * 10,
    ])
  }
}

function dwell(out: TrackPoint[], t0: number, at: [number, number], minutes: number, rand: () => number) {
  for (let m = 1; m <= minutes; m += 2) {
    out.push([t0 + m * MINUTE, at[0] + (rand() - 0.5) * 0.00012, at[1] + (rand() - 0.5) * 0.00012, 12 + rand() * 14])
  }
}

function buildDay(day: number): { points: TrackPoint[]; pins: Pin[] } {
  const rand = rng(Math.floor(day / DAY))
  const points: TrackPoint[] = []
  let t = day + 7 * HOUR + Math.floor(rand() * 30) * MINUTE
  dwell(points, day + 5 * MINUTE, HOME, 20, rand)
  points.push([t, HOME[0], HOME[1], 8])
  leg(points, t, HOME, WORK, 38, rand); t += 38 * MINUTE
  dwell(points, t, WORK, 205, rand); t += 205 * MINUTE
  leg(points, t, WORK, LUNCH, 9, rand); t += 9 * MINUTE
  dwell(points, t, LUNCH, 50, rand); t += 50 * MINUTE
  leg(points, t, LUNCH, WORK, 9, rand); t += 9 * MINUTE
  dwell(points, t, WORK, 270, rand); t += 270 * MINUTE
  leg(points, t, WORK, HOME, 42, rand); t += 42 * MINUTE
  dwell(points, t, HOME, 120, rand)

  const mk = (id: string, tt: number, typeId: string, eventType: Pin['eventType'], title: string, body: string | null, at: [number, number]): Pin => {
    const type = PIN_TYPES.find((p) => p.id === typeId)!
    return { id: `${day}-${id}`, t: tt, eventType, title, body, lat: at[0], lng: at[1], source: null, typeId, typeName: type.name, color: type.color, icon: type.icon }
  }
  const d = day
  const pins = [
    mk('a', d + 8 * HOUR + 12 * MINUTE, 'noti', 'NOTIFICATION', '교통카드 결제', '지하철 1,550원', [37.53, 127.0]),
    mk('b', d + 9 * HOUR + 50 * MINUTE, 'call', 'PHONE_CALL', '김민준', '수신 · 4분 12초', WORK),
    mk('c', d + 12 * HOUR + 20 * MINUTE, 'food', 'PIN_MANUAL', '점심', '칼국수 맛집 — 다음에 또 오기', LUNCH),
    mk('d', d + 15 * HOUR + 5 * MINUTE, 'sms', 'SMS', '택배 알림', '[CJ대한통운] 오늘 도착 예정입니다', WORK),
    mk('e', d + 18 * HOUR + 40 * MINUTE, 'noti', 'NOTIFICATION', '배달의민족', '주문하신 음식이 도착했어요', HOME),
  ]
  return { points, pins }
}

const listeners = new Map<string, Set<(payload: never) => void>>()
function emit<K extends keyof BridgeEvents>(name: K, payload: BridgeEvents[K]) {
  listeners.get(name)?.forEach((l) => (l as (p: BridgeEvents[K]) => void)(payload))
}

/** Plays a fake backup/restore so the progress UI can be seen in the browser. */
async function fakeProgress(steps: string[]) {
  for (let i = 0; i <= 20; i++) {
    emit('backup-progress', { phase: steps[Math.min(steps.length - 1, Math.floor((i / 20) * steps.length))], fraction: i / 20, detail: i < 20 ? `${i * 5}%` : null })
    await delay(undefined, 150)
  }
}
const extraPins: Pin[] = []
const delay = <T,>(v: T, ms = 120) => new Promise<T>((r) => setTimeout(() => r(v), ms))

export function createMockBridge(): Bridge {
  const today = startOfDay(Date.now())
  const days = Array.from({ length: 9 }, (_, i) => today - (8 - i) * DAY)

  const load = (start: number, end: number) => {
    const points: TrackPoint[] = []
    const pins: Pin[] = []
    for (const day of days) {
      if (day + DAY < start || day > end) continue
      const built = buildDay(day)
      points.push(...built.points.filter((p) => p[0] >= start && p[0] < end))
      pins.push(...built.pins.filter((p) => p.t >= start && p.t < end))
    }
    // The mock "recording" stops at the present moment.
    const now = Date.now()
    pins.push(...extraPins.filter((p) => p.t >= start && p.t < end))
    return {
      points: points.filter((p) => p[0] <= now).sort((a, b) => a[0] - b[0]),
      pins: pins.filter((p) => p.t <= now).sort((a, b) => a.t - b.t),
    }
  }

  const on: Bridge['on'] = (name, cb) => {
    let set = listeners.get(name)
    if (!set) listeners.set(name, (set = new Set()))
    set.add(cb as (payload: never) => void)
    return () => { set.delete(cb as (payload: never) => void) }
  }

  return {
    isNative: false,
    getSettings: () => delay({ ...settings }, 30),
    updateSettings: (patch) => {
      settings = { ...settings, ...patch }
      return delay({ ...settings }, 30)
    },
    async getTimeline({ start, end }) {
      const { points, pins } = load(start, end)
      const all = load(today - 9 * DAY, Date.now() + DAY).points
      const data: TimelineData = {
        points,
        pins,
        pinTypes: PIN_TYPES,
        latest: all[all.length - 1] ?? null,
        days: days.map(dayKey),
      }
      return delay(data)
    },
    getPins: ({ start, end }) => delay(load(start, end).pins.reverse()),
    getPinTypes: () => delay(PIN_TYPES, 30),
    async addManualPin({ text, typeId }) {
      const type = PIN_TYPES.find((p) => p.id === typeId) ?? PIN_TYPES[0]
      const all = load(today - DAY, Date.now() + DAY).points
      const last = all[all.length - 1]
      extraPins.push({
        id: `manual-${Date.now()}`, t: Date.now(), eventType: 'PIN_MANUAL', title: '압정', body: text,
        lat: last?.[1] ?? null, lng: last?.[2] ?? null, source: 'MANUAL',
        typeId: type.id, typeName: type.name, color: type.color, icon: type.icon,
      })
      emit('data-changed', undefined)
      return delay(undefined, 60)
    },
    async completeOnboarding() {
      settings = { ...settings, onboardingCompleted: true }
      return delay({ ...settings }, 60)
    },
    getRuleEditor: () => delay({
      types: PIN_TYPES.map((t): EditablePinType => ({ ...t, system: ['general', 'call', 'sms', 'noti'].includes(t.id), usage: typeUsage[t.id] ?? 0 })),
      rules: [...rules].sort((a, b) => a.source.localeCompare(b.source) || a.priority - b.priority).map((r) => ({ ...r })),
    }, 80),
    async saveRule(draft) {
      const prev = rules.find((r) => r.id === draft.id)
      const next: PinRule = prev
        ? { ...prev, ...draft, source: prev.system || prev.usage > 0 ? prev.source : draft.source }
        : { ...draft, id: `r-${Date.now()}`, system: false, enabled: true, hidden: false, priority: 200, usage: 0 }
      rules = prev ? rules.map((r) => (r.id === next.id ? next : r)) : [...rules, next]
      emit('data-changed', undefined)
    },
    async deleteRule({ id }) {
      const r = rules.find((x) => x.id === id)
      if (!r || r.system || r.usage > 0) throw new Error('압정을 만든 규칙이나 시스템 규칙은 삭제할 수 없습니다.')
      rules = rules.filter((x) => x.id !== id)
    },
    async setRuleEnabled({ id, enabled }) {
      rules = rules.map((r) => (r.id === id ? { ...r, enabled, hidden: enabled ? false : r.hidden } : r))
    },
    async setRuleHidden({ id, hidden }) {
      rules = rules.map((r) => (r.id === id ? { ...r, hidden } : r))
    },
    async moveRule({ id, delta }) {
      const r = rules.find((x) => x.id === id)!
      const same = rules.filter((x) => x.source === r.source).sort((a, b) => a.priority - b.priority)
      const from = same.indexOf(r)
      const other = same[Math.min(same.length - 1, Math.max(0, from + delta))]
      if (other === r) return
      const p = r.priority
      rules = rules.map((x) => (x === r ? { ...x, priority: other.priority } : x === other ? { ...x, priority: p } : x))
    },
    async savePinType(t) {
      const i = PIN_TYPES.findIndex((p) => p.id === t.id)
      if (i >= 0) PIN_TYPES[i] = { ...t }
      else PIN_TYPES.push({ ...t, id: `t-${Date.now()}` })
    },
    async deletePinType({ id }) {
      const i = PIN_TYPES.findIndex((p) => p.id === id)
      if ((typeUsage[id] ?? 0) > 0 || i < 0) throw new Error('사용 중인 타입이나 기본 타입은 삭제할 수 없습니다.')
      PIN_TYPES.splice(i, 1)
    },
    getInstalledApps: () => delay(APPS, 400),
    getPermissions: () => delay({ ...permissions }, 30),
    async requestPermission({ kind }) {
      if (kind in permissions) permissions = { ...permissions, [kind]: true }
      return delay({ ...permissions }, 300)
    },
    getOfflineMaps: () => delay({
      days: days.map(dayKey),
      regions: offlineRegions.map((r) => ({ ...r })),
      download: { state: 'idle' } as OfflineDownload,
      auto: { enabled: settings.autoOfflineMaps, foreground: true, wifi: true },
      log: [
        { id: 2, name: '다닌 곳 (자동 저장)', auto: true, startedAt: Date.now() - 3_600_000, updatedAt: Date.now() - 3_480_000, lastTileAt: Date.now() - 3_480_000, endedAt: Date.now() - 3_480_000, tiles: 412, bytes: 9 * 1024 * 1024, remaining: 0, result: 'complete', message: null },
        { id: 1, name: '다닌 곳 (자동 저장)', auto: true, startedAt: Date.now() - 86_400_000, updatedAt: Date.now() - 86_100_000, lastTileAt: Date.now() - 86_100_000, endedAt: Date.now() - 86_100_000, tiles: 120, bytes: 3 * 1024 * 1024, remaining: 290, result: 'stopped', message: 'Wi-Fi 연결이 끊김' },
      ] as DownloadLogEntry[],
    }),
    async downloadOfflineMap({ start, end }) {
      const name = start === end ? `${start} 이동 범위` : `${start} ~ ${end} 이동 범위`
      const required = 1200
      ;(async () => {
        for (let done = 0; done <= required; done += 150) {
          emit('offline-changed', { state: 'downloading', name, completed: done, required, bytes: done * 24_000 })
          await delay(undefined, 250)
        }
        offlineRegions = [...offlineRegions, { id: Date.now(), name, completed: required, required, bytes: required * 24_000 }]
        emit('offline-changed', { state: 'complete', name, bytes: required * 24_000 })
      })()
    },
    async resumeOfflineMap({ id }) {
      const region = offlineRegions.find((r) => r.id === id)
      if (!region) throw new Error('오프라인 지도를 찾을 수 없습니다.')
      ;(async () => {
        for (let done = region.completed; done <= region.required; done += 150) {
          emit('offline-changed', { state: 'downloading', name: region.name, completed: done, required: region.required, bytes: done * 24_000 })
          await delay(undefined, 250)
        }
        offlineRegions = offlineRegions.map((r) => (r.id === id ? { ...r, completed: r.required, bytes: r.required * 24_000 } : r))
        emit('offline-changed', { state: 'complete', name: region.name, bytes: region.required * 24_000 })
      })()
    },
    async deleteOfflineMap({ id }) {
      offlineRegions = offlineRegions.filter((r) => r.id !== id)
    },
    async exportBackup() {
      await fakeProgress(['데이터 읽는 중', '압축 중', '암호화 중'])
      return { cancelled: false }
    },
    async importBackup() {
      await fakeProgress(['암호 해제 중', '데이터 복원 중'])
      return { cancelled: false }
    },
    cloudBackupConfigured: () => delay(true, 30),
    cloudBackupMetadata: () => delay(cloud, 500),
    async cloudBackup({ expectedId }) {
      if ((cloud?.id ?? null) !== expectedId) throw new Error('온라인 백업 상태가 다른 기기에서 변경되었습니다. 상태를 다시 확인하세요.')
      await fakeProgress(['Google 계정 확인 중', '암호화 중', '업로드 중'])
      const now = Date.now()
      cloud = { id: `b-${now}`, createdAt: now, expiresAt: now + 14 * DAY, sizeBytes: 38 * 1024 * 1024 }
      return cloud
    },
    async cloudRestore() {
      if (!cloud) throw new Error('복원할 온라인 백업이 없습니다.')
      await fakeProgress(['Google 계정 확인 중', '다운로드 중', '데이터 복원 중'])
      return cloud
    },
    async cloudDelete() {
      cloud = null
    },
    on,
    onDataChanged: (cb) => on('data-changed', cb),
  }
}
