import { useEffect, useMemo, useRef, useState } from 'react'
import { haversine, lowerBound } from '../lib/geo'
import { HOUR, MINUTE } from '../lib/time'
import type { Pin, TrackPoint } from '../lib/types'

interface Props {
  time: number
  pins: Pin[]
  points: TrackPoint[]
  tickMinutes: number
  radiusMinutes: number
  rangeStart: number
  rangeEnd: number
  onStart: (t: number) => void
  onPreview: (t: number) => void
  onCommit: (t: number) => void
}

const HEIGHT = 92
const PRECISION_HOLD_MS = 700
const PRECISION_SCALE = 0.25
const VELOCITY_WINDOW_MS = 100
const MIN_VELOCITY_DT_MS = 16
/** Exponential fling friction per ms (velocity keeps 0.35% after one second). */
const FRICTION_K = -Math.log(0.0035) / 1000
/** A fling travels at most this many visible ruler widths. */
const MAX_FLING_SPANS = 4

/** Below this the phone counts as standing still; the activity graph tops out at MAX_SPEED. */
const MIN_SPEED = 0.6
const MAX_SPEED = 30
const GRAPH_H = 20
/** Hour labels need at least this much room. */
const MIN_LABEL_PX = 30

const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim()

/** Movement per segment as [start, end, speed m/s], to draw where the day had trips. */
function activity(points: readonly TrackPoint[]): { t: Float64Array; speed: Float32Array } {
  const n = Math.max(0, points.length - 1)
  const t = new Float64Array(n * 2)
  const speed = new Float32Array(n)
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]
    const b = points[i]
    t[(i - 1) * 2] = a[0]
    t[(i - 1) * 2 + 1] = b[0]
    speed[i - 1] = haversine(a, b) / Math.max(1, (b[0] - a[0]) / 1000)
  }
  return { t, speed }
}

export function Scrubber({ time, pins, points, tickMinutes, radiusMinutes, rangeStart, rangeEnd, onStart, onPreview, onCommit }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const live = useRef(time)
  const dragging = useRef(false)
  const [precise, setPrecise] = useState(false)
  const moves = useMemo(() => ({ points, ...activity(points) }), [points])
  const latest = useRef({ pins, moves, tickMinutes, radiusMinutes, rangeStart, rangeEnd, precise, onStart, onPreview, onCommit })
  latest.current = { pins, moves, tickMinutes, radiusMinutes, rangeStart, rangeEnd, precise, onStart, onPreview, onCommit }
  const raf = useRef(0)

  if (!dragging.current) live.current = time

  const draw = () => {
    const c = canvas.current
    if (!c) return
    const { pins, moves, tickMinutes, radiusMinutes, rangeStart, rangeEnd, precise } = latest.current
    const dpr = window.devicePixelRatio || 1
    const w = c.clientWidth
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(HEIGHT * dpr)) {
      c.width = Math.round(w * dpr)
      c.height = Math.round(HEIGHT * dpr)
    }
    const g = c.getContext('2d')!
    g.setTransform(dpr, 0, 0, dpr, 0, 0)
    g.clearRect(0, 0, w, HEIGHT)

    const spanMs = radiusMinutes * 2 * MINUTE
    const pxPerMs = (w / spanMs) * (precise ? 1 / PRECISION_SCALE : 1)
    const t0 = live.current
    const xOf = (t: number) => w / 2 + (t - t0) * pxPerMs
    const left = t0 - w / 2 / pxPerMs
    const right = t0 + w / 2 / pxPerMs
    const baseY = 62

    // Areas with no recording (before the first day / after "now") are dimmed.
    g.fillStyle = css('--surface-2')
    g.fillRect(0, baseY - 24, w, 36)
    g.fillStyle = css('--fg-3')
    g.globalAlpha = 0.18
    const dimL = xOf(rangeStart)
    const dimR = xOf(rangeEnd)
    if (dimL > 0) g.fillRect(0, baseY - 24, Math.min(w, dimL), 36)
    if (dimR < w) g.fillRect(Math.max(0, dimR), baseY - 24, w - Math.max(0, dimR), 36)
    g.globalAlpha = 1

    // Activity graph: how fast the phone moved, so trips stand out on the ruler.
    const acc = css('--accent')
    const cols = new Float32Array(Math.ceil(w) + 1)
    for (let i = Math.max(0, lowerBound(moves.points, left) - 1); i < moves.speed.length; i++) {
      const ta = moves.t[i * 2]
      if (ta > right) break
      const v = moves.speed[i]
      if (v < MIN_SPEED) continue
      const h = Math.min(1, Math.log2(1 + (v - MIN_SPEED)) / Math.log2(1 + MAX_SPEED))
      const x0 = Math.max(0, Math.floor(xOf(ta)))
      const x1 = Math.min(cols.length - 1, Math.ceil(xOf(moves.t[i * 2 + 1])))
      for (let x = x0; x <= x1; x++) cols[x] = Math.max(cols[x], h)
    }
    g.fillStyle = acc
    g.globalAlpha = 0.32
    g.beginPath()
    g.moveTo(0, baseY + 12)
    for (let x = 0; x < cols.length; x++) g.lineTo(x, baseY + 12 - cols[x] * GRAPH_H)
    g.lineTo(cols.length - 1, baseY + 12)
    g.fill()
    g.globalAlpha = 1

    const tickMs = Math.max(1, tickMinutes) * MINUTE
    const tickPx = tickMs * pxPerMs
    const stride = tickPx < 5 ? Math.ceil(5 / tickPx) : 1
    g.strokeStyle = css('--fg-3')
    g.lineWidth = 1.5
    g.globalAlpha = 0.32
    g.beginPath()
    for (let t = Math.floor(left / tickMs) * tickMs; t <= right; t += tickMs * stride) {
      const x = xOf(t)
      g.moveTo(x, baseY + 12)
      g.lineTo(x, baseY + 5)
    }
    g.stroke()

    // Hours (and half hours when there is room) on local time, independent of the tick setting.
    const hourPx = HOUR * pxPerMs
    const labelEvery = [1, 2, 3, 6, 12].find((n) => n * hourPx >= MIN_LABEL_PX) ?? 24
    const first = new Date(left)
    first.setMinutes(0, 0, 0)
    g.textAlign = 'center'
    g.font = '600 11px system-ui, sans-serif'
    for (let t = first.getTime(); t <= right + HOUR; t += HOUR / 2) {
      const d = new Date(t)
      const half = d.getMinutes() !== 0
      if (half && hourPx < 40) continue
      const x = xOf(t)
      g.globalAlpha = half ? 0.55 : 0.9
      g.beginPath()
      g.moveTo(x, baseY + 12)
      g.lineTo(x, baseY + 12 - (half ? 11 : 16))
      g.stroke()
      g.globalAlpha = 1
      if (!half && d.getHours() % labelEvery === 0) {
        const midnight = d.getHours() === 0
        g.fillStyle = css(midnight ? '--fg' : '--fg-2')
        g.fillText(midnight ? `${d.getMonth() + 1}/${d.getDate()}` : String(d.getHours()).padStart(2, '0'), x, HEIGHT - 6)
      }
    }

    // Pins sit above the ruler; overlapping ones stack as a count bubble.
    const visible = pins.filter((p) => p.t >= left - 20 * MINUTE && p.t <= right + 20 * MINUTE)
    const clusters: { x: number; items: Pin[] }[] = []
    for (const p of visible) {
      const x = xOf(p.t)
      const last = clusters[clusters.length - 1]
      if (last && x - last.x < 14) last.items.push(p)
      else clusters.push({ x, items: [p] })
    }
    for (const cl of clusters) {
      const y = baseY - 34
      g.beginPath()
      g.arc(cl.x, y, 9, 0, Math.PI * 2)
      g.fillStyle = cl.items[0].color
      g.fill()
      g.lineWidth = 2.5
      g.strokeStyle = css('--surface')
      g.stroke()
      if (cl.items.length > 1) {
        g.fillStyle = '#fff'
        g.font = '700 10px system-ui, sans-serif'
        g.fillText(String(cl.items.length), cl.x, y + 3.5)
      }
      g.strokeStyle = cl.items[0].color
      g.globalAlpha = 0.5
      g.lineWidth = 1.5
      g.beginPath()
      g.moveTo(cl.x, y + 9)
      g.lineTo(cl.x, baseY - 24)
      g.stroke()
      g.globalAlpha = 1
    }

    // Playhead
    g.strokeStyle = acc
    g.lineWidth = 3
    g.lineCap = 'round'
    g.beginPath()
    g.moveTo(w / 2, baseY - 28)
    g.lineTo(w / 2, baseY + 14)
    g.stroke()
    g.fillStyle = acc
    g.beginPath()
    g.moveTo(w / 2 - 6, 8)
    g.lineTo(w / 2 + 6, 8)
    g.lineTo(w / 2, 17)
    g.fill()
  }

  const schedule = () => {
    cancelAnimationFrame(raf.current)
    raf.current = requestAnimationFrame(draw)
  }

  useEffect(schedule)
  useEffect(() => {
    const ro = new ResizeObserver(schedule)
    ro.observe(canvas.current!)
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    mq.addEventListener('change', schedule)
    return () => { ro.disconnect(); mq.removeEventListener('change', schedule); cancelAnimationFrame(raf.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const gesture = useRef<{ id: number; x: number; startX: number; moved: boolean; up: boolean; samples: { at: number; t: number }[]; hold?: number; inertia?: number; rearm?: () => void } | null>(null)

  const clamp = (t: number) => Math.min(latest.current.rangeEnd, Math.max(latest.current.rangeStart, t))
  const pxPerMs = () => {
    const w = canvas.current!.clientWidth
    return (w / (latest.current.radiusMinutes * 2 * MINUTE)) * (latest.current.precise ? 1 / PRECISION_SCALE : 1)
  }

  const finish = () => {
    const gs = gesture.current
    window.clearTimeout(gs?.hold)
    setPrecise(false)
    dragging.current = false
    gesture.current = null
    latest.current.onCommit(live.current)
  }

  const onDown = (e: React.PointerEvent) => {
    const prev = gesture.current
    // Only one finger drives the ruler; a second touch while the first is still down is ignored.
    if (prev && !prev.up && prev.id !== e.pointerId) return
    canvas.current!.setPointerCapture(e.pointerId)
    if (prev?.inertia) cancelAnimationFrame(prev.inertia)
    window.clearTimeout(prev?.hold)
    const gs: NonNullable<typeof gesture.current> = { id: e.pointerId, x: e.clientX, startX: e.clientX, moved: false, up: false, samples: [{ at: e.timeStamp, t: live.current }] }
    gesture.current = gs
    dragging.current = true
    // Start from where the ruler is now — mid-fling that is not the last committed time.
    latest.current.onStart(live.current)
    const arm = () => {
      gs.hold = window.setTimeout(() => setPrecise(true), PRECISION_HOLD_MS)
    }
    arm()
    gs.rearm = () => { window.clearTimeout(gs.hold); arm() }
  }

  const onMove = (e: React.PointerEvent) => {
    const gs = gesture.current
    if (!gs || gs.up || gs.id !== e.pointerId) return
    const dx = e.clientX - gs.x
    if (Math.abs(e.clientX - gs.startX) > 5) gs.moved = true
    if (dx === 0) return
    gs.x = e.clientX
    live.current = clamp(live.current - dx / pxPerMs())
    gs.samples.push({ at: e.timeStamp, t: live.current })
    while (gs.samples.length > 2 && gs.samples[0].at < e.timeStamp - VELOCITY_WINDOW_MS) gs.samples.shift()
    if (!latest.current.precise) gs.rearm?.()
    latest.current.onPreview(live.current)
    schedule()
  }

  /** Average velocity (ms of timeline per ms) over the last few samples, capped so a fling can't fly off. */
  const releaseVelocity = (gs: NonNullable<typeof gesture.current>, at: number) => {
    const recent = gs.samples.filter((s) => s.at >= at - VELOCITY_WINDOW_MS)
    if (recent.length < 2) return 0
    const first = recent[0]
    const last = recent[recent.length - 1]
    // Coalesced touch events can land a few ms apart; a too-short window would explode the velocity.
    const dt = Math.max(MIN_VELOCITY_DT_MS, last.at - first.at)
    const vel = (last.t - first.t) / dt
    const visibleSpan = canvas.current!.clientWidth / pxPerMs()
    const maxVel = MAX_FLING_SPANS * visibleSpan * FRICTION_K
    return Math.max(-maxVel, Math.min(maxVel, vel))
  }

  const onCancel = (e: React.PointerEvent) => {
    const gs = gesture.current
    if (gs && !gs.up && gs.id === e.pointerId) finish()
  }

  const onUp = (e: React.PointerEvent) => {
    const gs = gesture.current
    if (!gs || gs.up || gs.id !== e.pointerId) return
    gs.up = true
    window.clearTimeout(gs.hold)
    // A tap on a pin dot jumps straight to it.
    if (!gs.moved) {
      const rect = canvas.current!.getBoundingClientRect()
      const x = e.clientX - rect.left
      const ppm = pxPerMs()
      const hit = latest.current.pins
        .map((p) => ({ p, d: Math.abs(rect.width / 2 + (p.t - live.current) * ppm - x) }))
        .filter((h) => h.d < 16)
        .sort((a, b) => a.d - b.d)[0]
      if (hit) live.current = clamp(hit.p.t)
      finish()
      return
    }
    // Fling with inertia.
    let vel = releaseVelocity(gs, e.timeStamp)
    if (latest.current.precise || Math.abs(vel) < 0.02 * (latest.current.radiusMinutes * MINUTE / 1000)) {
      finish()
      return
    }
    let last = performance.now()
    const step = (now: number) => {
      // The first frame can carry a stale timestamp; never step backwards or by a huge gap.
      const dt = Math.min(50, Math.max(0, now - last))
      last = now
      live.current = clamp(live.current + vel * dt)
      vel *= Math.exp(-FRICTION_K * dt)
      latest.current.onPreview(live.current)
      draw()
      if (Math.abs(vel) > 0.004 * (latest.current.radiusMinutes * MINUTE / 1000) && live.current > latest.current.rangeStart && live.current < latest.current.rangeEnd) {
        gs.inertia = requestAnimationFrame(step)
      } else finish()
    }
    gs.inertia = requestAnimationFrame(step)
  }

  return (
    <div className="relative">
      <canvas
        ref={canvas}
        role="slider"
        aria-label="시간 이동"
        aria-valuemin={latest.current.rangeStart}
        aria-valuemax={latest.current.rangeEnd}
        aria-valuenow={time}
        tabIndex={0}
        onKeyDown={(e) => {
          const step = e.shiftKey ? 30 * MINUTE : 5 * MINUTE
          if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            const t = clamp(live.current + (e.key === 'ArrowLeft' ? -step : step))
            live.current = t
            onCommit(t)
          }
        }}
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onCancel}
        className="block w-full touch-none"
        style={{ height: HEIGHT }}
      />
      {precise && (
        <span className="anim-pop pointer-events-none absolute top-1 right-2 rounded-full bg-accent px-2.5 py-1 text-[11px] font-bold text-accentfg">정밀 이동</span>
      )}
    </div>
  )
}
