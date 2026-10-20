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

export function isMeaningfulGap(a: TrackPoint, b: TrackPoint): boolean {
  return b[0] - a[0] > GAP_MS && haversine(a, b) > GAP_MIN_METERS
}

/**
 * The whole route as one FeatureCollection of 2-point segments tagged with their end time `t`.
 * Scrubbing only changes layer filters, so the geometry is uploaded to the GPU once per data load.
 */
export function routeFeatures(points: readonly TrackPoint[]): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = []
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]
    const b = points[i]
    features.push({
      type: 'Feature',
      properties: { t: b[0], gap: isMeaningfulGap(a, b) ? 1 : 0 },
      geometry: { type: 'LineString', coordinates: [[a[2], a[1]], [b[2], b[1]]] },
    })
  }
  return { type: 'FeatureCollection', features }
}

export function routeDistance(points: readonly TrackPoint[]): number {
  let d = 0
  for (let i = 1; i < points.length; i++) {
    if (points[i][0] - points[i - 1][0] <= GAP_MS) d += haversine(points[i - 1], points[i])
  }
  return d
}

export function fmtDistance(m: number): string {
  return m >= 1000 ? `${(m / 1000).toFixed(m >= 10000 ? 0 : 1)}km` : `${Math.round(m)}m`
}
