import type { TrackPoint } from './types'

export function haversine(a: TrackPoint, b: TrackPoint): number {
  const R = 6371000
  const rad = Math.PI / 180
  const dLat = (b[1] - a[1]) * rad
  const dLng = (b[2] - a[2]) * rad
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}

/** First index whose timestamp is >= target. `points` must be ascending by timestamp. */
export function lowerBound(points: readonly TrackPoint[], target: number): number {
  let lo = 0
  let hi = points.length
  while (lo < hi) {
    const mid = (lo + hi) >>> 1
    if (points[mid][0] < target) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** Linear interpolation between the two samples around `t`; clamps to the ends. */
export function positionAt(points: readonly TrackPoint[], t: number): { lat: number; lng: number; t: number } | null {
  if (!points.length) return null
  const i = lowerBound(points, t)
  if (i === 0) return { lat: points[0][1], lng: points[0][2], t: points[0][0] }
  if (i >= points.length) {
    const p = points[points.length - 1]
    return { lat: p[1], lng: p[2], t: p[0] }
  }
  const a = points[i - 1]
  const b = points[i]
  // Don't glide across a recording gap — snap to the earlier sample instead.
  if (b[0] - a[0] > GAP_MS) return { lat: a[1], lng: a[2], t: a[0] }
  const f = (t - a[0]) / (b[0] - a[0] || 1)
  return { lat: a[1] + (b[1] - a[1]) * f, lng: a[2] + (b[2] - a[2]) * f, t }
}

export const GAP_MS = 90_000
export const GAP_MIN_METERS = 25

/** A recording gap long enough, and a jump far enough beyond both fixes' error, to draw as "no data in between". */
export function isMeaningfulGap(a: TrackPoint, b: TrackPoint): boolean {
  return b[0] - a[0] > GAP_MS && haversine(a, b) > Math.max(GAP_MIN_METERS, a[3] + b[3])
}

/**
 * Display-side cleanup of raw fixes; the stored track is untouched.
 * Network fixes (±100m and worse) bounce between nearby spots and the odd fix lands kilometres away, which drew
 * zig-zags and scribbles over every place the phone stayed. Stays are collapsed to one spot for the same reason.
 */
export function cleanTrack(points: readonly TrackPoint[]): TrackPoint[] {
  return collapseStays(dropSpikes(dropImprecise(points)))
}

const IMPRECISE_METERS = 200
const PRECISE_METERS = 100
const IMPRECISE_NEIGHBOR_MS = 2 * 60_000

/** Very coarse fixes are only kept when nothing better was recorded around the same time. */
function dropImprecise(points: readonly TrackPoint[]): TrackPoint[] {
  return points.filter((p, i) => {
    if (p[3] <= IMPRECISE_METERS) return true
    for (let j = i - 1; j >= 0 && p[0] - points[j][0] <= IMPRECISE_NEIGHBOR_MS; j--) if (points[j][3] <= PRECISE_METERS) return false
    for (let j = i + 1; j < points.length && points[j][0] - p[0] <= IMPRECISE_NEIGHBOR_MS; j++) if (points[j][3] <= PRECISE_METERS) return false
    return true
  })
}

const SPIKE_MIN_METERS = 40
/** Faster than any ground travel the app records (≈250km/h). */
const SPIKE_SPEED_MPS = 70
/** cos of the turn beyond which the track is doubling back (135°). */
const BACKTRACK_COS = -0.7
/** A doubling-back leg up to this many times the fix's error is read as error, not as a real U-turn. */
const BACKTRACK_ACCURACY_FACTOR = 1.5

/** cos of the angle between a→b and b→c, on a local flat projection. */
function turnCos(a: TrackPoint, b: TrackPoint, c: TrackPoint): number {
  const k = Math.cos((b[1] * Math.PI) / 180)
  const ux = (b[2] - a[2]) * k
  const uy = b[1] - a[1]
  const vx = (c[2] - b[2]) * k
  const vy = c[1] - b[1]
  const n = Math.hypot(ux, uy) * Math.hypot(vx, vy)
  return n ? (ux * vx + uy * vy) / n : 1
}

/**
 * Drops fixes that jump out and come back: straight back when the jump is within the fix's own error or impossibly
 * fast, or partly back (an overshoot, typical of network fixes on a subway) when the doubling-back is within its error.
 */
function dropSpikes(points: readonly TrackPoint[]): TrackPoint[] {
  if (points.length < 3) return points.slice()
  const out: TrackPoint[] = [points[0]]
  for (let i = 1; i < points.length - 1; i++) {
    const a = out[out.length - 1]
    const b = points[i]
    const c = points[i + 1]
    const ab = haversine(a, b)
    const bc = haversine(b, c)
    const outAndBack = ab > SPIKE_MIN_METERS && bc > SPIKE_MIN_METERS && haversine(a, c) < 0.5 * Math.min(ab, bc)
    const tooFast = ab / Math.max(1, (b[0] - a[0]) / 1000) > SPIKE_SPEED_MPS && bc / Math.max(1, (c[0] - b[0]) / 1000) > SPIKE_SPEED_MPS
    if (outAndBack && (b[3] >= 0.5 * ab || tooFast)) continue
    const backtrack = Math.min(ab, bc) > 0 && turnCos(a, b, c) < BACKTRACK_COS && Math.min(ab, bc) <= BACKTRACK_ACCURACY_FACTOR * b[3]
    if (backtrack) continue
    out.push(b)
  }
  out.push(points[points.length - 1])
  return out
}

const STAY_MIN_MS = 3 * 60_000
const STAY_MIN_RADIUS = 30
const STAY_MAX_RADIUS = 80

/**
 * Runs of fixes that stay within a fix's error radius of their running centre for a few minutes become two points
 * (arrival and departure) at that centre, so a stop reads as a dot rather than a tangle.
 */
function collapseStays(points: readonly TrackPoint[]): TrackPoint[] {
  const out: TrackPoint[] = []
  let i = 0
  while (i < points.length) {
    const first = points[i]
    let w = 1 / Math.max(5, first[3]) ** 2
    let sum = w
    let lat = first[1]
    let lng = first[2]
    let acc = first[3]
    let j = i + 1
    for (; j < points.length; j++) {
      const q = points[j]
      if (haversine([0, lat, lng, 0], q) > Math.min(STAY_MAX_RADIUS, Math.max(STAY_MIN_RADIUS, q[3]))) break
      w = 1 / Math.max(5, q[3]) ** 2
      sum += w
      lat += (q[1] - lat) * (w / sum)
      lng += (q[2] - lng) * (w / sum)
      acc = Math.min(acc, q[3])
    }
    const last = points[j - 1]
    if (j - i >= 3 && last[0] - first[0] >= STAY_MIN_MS) {
      out.push([first[0], lat, lng, acc], [last[0], lat, lng, acc])
      i = j
    } else {
      out.push(first)
      i++
    }
  }
  return out
}

/**
 * Distance travelled, counting a stretch without fixes (a subway hop, a fix dropped as noise) as the straight line
 * across it unless covering it would have taken impossible speed.
 */
export function routeDistance(points: readonly TrackPoint[]): number {
  let d = 0
  for (let i = 1; i < points.length; i++) {
    const m = haversine(points[i - 1], points[i])
    if (m / Math.max(1, (points[i][0] - points[i - 1][0]) / 1000) <= SPIKE_SPEED_MPS) d += m
  }
  return d
}

export function fmtDistance(m: number): string {
  return m >= 1000 ? `${(m / 1000).toFixed(m >= 10000 ? 0 : 1)}km` : `${Math.round(m)}m`
}
