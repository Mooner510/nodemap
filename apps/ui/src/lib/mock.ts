import type { Bridge, Pin, PinType, RuleSummary, Settings, TimelineData, TrackPoint } from './types'
import { DAY, HOUR, MINUTE, dayKey, startOfDay } from './time'

const PIN_TYPES: PinType[] = [
  { id: 'general', name: '일반', color: '#3182F6', icon: 'PIN' },
  { id: 'call', name: '전화', color: '#22C55E', icon: 'PHONE' },
  { id: 'sms', name: '문자', color: '#F59E0B', icon: 'MESSAGE' },
  { id: 'noti', name: '알림', color: '#8B5CF6', icon: 'NOTIFICATION' },
  { id: 'food', name: '맛집', color: '#EF4444', icon: 'STAR' },
]

let settings: Settings = {
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
}

const rules: RuleSummary[] = [
  { id: 'r-call', name: '전화 기록', source: 'PHONE_CALL', system: true, enabled: true, hidden: false, priority: 10, typeId: 'call', typeName: '전화', color: '#22C55E', conditionCount: 0, packageCount: 0 },
  { id: 'r-sms', name: '문자 메시지', source: 'SMS', system: true, enabled: true, hidden: false, priority: 20, typeId: 'sms', typeName: '문자', color: '#F59E0B', conditionCount: 0, packageCount: 0 },
  { id: 'r-pay', name: '결제 알림', source: 'NOTIFICATION', system: false, enabled: true, hidden: false, priority: 50, typeId: 'noti', typeName: '알림', color: '#8B5CF6', conditionCount: 2, packageCount: 3 },
  { id: 'r-deliv', name: '배달 도착', source: 'NOTIFICATION', system: false, enabled: false, hidden: false, priority: 60, typeId: 'noti', typeName: '알림', color: '#8B5CF6', conditionCount: 1, packageCount: 2 },
]

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

const listeners = new Set<() => void>()
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
      listeners.forEach((l) => l())
      return delay(undefined, 60)
    },
    getRules: () => delay(rules.map((r) => ({ ...r })), 60),
    async setRuleEnabled({ id, enabled }) {
      const r = rules.find((x) => x.id === id)
      if (r) r.enabled = enabled
    },
    async openNative(screen) {
      console.info('[mock] openNative', screen)
    },
    onDataChanged(cb) {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
  }
}
