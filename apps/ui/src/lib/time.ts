export const MINUTE = 60_000
export const HOUR = 60 * MINUTE
export const DAY = 24 * HOUR

const pad = (n: number) => String(n).padStart(2, '0')

export function dayKey(t: number): string {
  const d = new Date(t)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function startOfDay(t: number): number {
  const d = new Date(t)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function parseDayKey(key: string): number {
  const [y, m, d] = key.split('-').map(Number)
  return new Date(y, m - 1, d).getTime()
}

export function fmtClock(t: number): string {
  const d = new Date(t)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function fmtClockSec(t: number): string {
  const d = new Date(t)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토']

export function fmtDay(t: number, withWeekday = true): string {
  const d = new Date(t)
  return `${d.getMonth() + 1}월 ${d.getDate()}일${withWeekday ? ` ${WEEKDAY[d.getDay()]}요일` : ''}`
}

export function relativeDay(t: number, now = Date.now()): string {
  const diff = Math.round((startOfDay(now) - startOfDay(t)) / DAY)
  if (diff === 0) return '오늘'
  if (diff === 1) return '어제'
  if (diff === 2) return '그저께'
  return fmtDay(t, false)
}

export function fmtAgo(t: number, now = Date.now()): string {
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 45) return '방금 전'
  const m = Math.round(s / 60)
  if (m < 60) return `${m}분 전`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}시간 ${m % 60 ? `${m % 60}분 ` : ''}전`
  return `${Math.floor(h / 24)}일 전`
}

export function fmtDuration(ms: number): string {
  const m = Math.round(ms / MINUTE)
  if (m < 60) return `${m}분`
  const h = Math.floor(m / 60)
  return m % 60 ? `${h}시간 ${m % 60}분` : `${h}시간`
}
