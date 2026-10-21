import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { bridge } from '../lib/bridge'
import { useLiveData, useNow } from '../lib/hooks'
import { fmtAgo, fmtClock, fmtDay, fmtDuration, DAY, MINUTE, dayKey, parseDayKey, relativeDay, startOfDay } from '../lib/time'
import { fmtDistance, routeDistance } from '../lib/geo'
import type { Pin, Settings } from '../lib/types'
import { Icon } from '../ui/Icon'
import { Sheet, Spinner, cx, useToast } from '../ui/kit'
import { Scrubber } from './Scrubber'
import { DayPickerSheet, ManualPinSheet, PinBadge, PinCard } from './parts'

// maplibre is most of the bundle: start fetching/compiling it right away, in parallel with the app shell and data.
const mapModule = import('./MapView')
const MapView = lazy(() => mapModule.then((m) => ({ default: m.MapView })))

export function TimelineScreen({ settings }: { settings: Settings }) {
  const now = useNow(15_000)
  const [selected, setSelected] = useState(() => Date.now())
  const [preview, setPreview] = useState<number | null>(null)
  const scrubbing = preview !== null
  const displayTime = preview ?? selected
  const day = dayKey(displayTime)

  const [pickerOpen, setPickerOpen] = useState(false)
  const [manualOpen, setManualOpen] = useState(false)
  const [daySheetOpen, setDaySheetOpen] = useState(false)
  const [focused, setFocused] = useState<Pin | null>(null)
  const [recenterKey, setRecenterKey] = useState(0)
  const [fitKey, setFitKey] = useState(0)
  const [offlineMap, setOfflineMap] = useState(false)
  const toast = useToast()

  const panel = useRef<HTMLDivElement>(null)
  const [panelH, setPanelH] = useState(260)
  useEffect(() => {
    const ro = new ResizeObserver(([e]) => setPanelH(e.target.getBoundingClientRect().height))
    ro.observe(panel.current!)
    return () => ro.disconnect()
  }, [])

  const { total, detail, camera, dialRadiusMinutes } = {
    total: settings.totalWindowMinutes,
    detail: settings.detailWindowMinutes,
    camera: settings.cameraWindowMinutes,
    dialRadiusMinutes: settings.dialRadiusMinutes,
  }

  const { data, error } = useLiveData(
    () => {
      const d = parseDayKey(day)
      const before = (Math.max(total, detail, camera) + dialRadiusMinutes + 60) * MINUTE
      const after = (dialRadiusMinutes + 60) * MINUTE
      return bridge.getTimeline({ start: d - before, end: d + DAY + after })
    },
    [day, total, detail, camera, dialRadiusMinutes],
  )

  const firstFit = useRef(false)
  useEffect(() => {
    if (data && !firstFit.current) {
      firstFit.current = true
      setFitKey((k) => k + 1)
    }
  }, [data])

  const points = data?.points ?? []
  const pins = data?.pins ?? []
  const clock = Math.max(now, Date.now())
  const rangeStart = data?.days.length ? parseDayKey(data.days[0]) : clock - total * MINUTE

  const nearby = useMemo(
    () => pins.filter((p) => Math.abs(p.t - displayTime) <= 4 * MINUTE).sort((a, b) => Math.abs(a.t - displayTime) - Math.abs(b.t - displayTime)),
    [pins, displayTime],
  )

  const dayStart = parseDayKey(day)
  const dayPoints = useMemo(() => points.filter((p) => p[0] >= dayStart && p[0] < dayStart + DAY), [points, dayStart])
  const dayPins = useMemo(() => pins.filter((p) => p.t >= dayStart && p.t < dayStart + DAY), [pins, dayStart])

  const latest = data?.latest ?? null
  const status = !settings.trackingEnabled
    ? { label: '기록 꺼짐', dot: 'bg-fg3', pulse: false }
    : latest && clock - latest[0] < 5 * MINUTE
      ? { label: '기록 중', dot: 'bg-ok', pulse: true }
      : { label: latest ? `${fmtAgo(latest[0], clock)} 마지막 기록` : '기록 대기 중', dot: 'bg-warn', pulse: false }

  const jump = useCallback((t: number) => {
    setSelected(t)
    setPreview(null)
    setFitKey((k) => k + 1)
  }, [])

  const pickDay = (key: string) => {
    const start = parseDayKey(key)
    const end = start + DAY
    // Land on the last moment of the day that has data (or "now" for today).
    bridge.getTimeline({ start, end }).then((d) => {
      const last = Math.max(0, ...d.points.map((p) => p[0]), ...d.pins.map((p) => p.t))
      jump(Math.min(last || start + 12 * 60 * MINUTE, Date.now()))
    })
  }

  const away = Math.abs(clock - displayTime) > 5 * MINUTE
  const stillLoading = !data && !error

  return (
    <div className="absolute inset-0 overflow-hidden bg-surface2">
      {/* The map mounts before the timeline arrives so its style and tiles load alongside the query. */}
      <Suspense fallback={null}>
        <MapView
          points={points}
          pins={pins}
          renderTime={displayTime}
          styleUri={settings.mapStyleUri}
          detailWindowMinutes={detail}
          totalWindowMinutes={total}
          cameraWindowMinutes={camera}
          scrubbing={scrubbing}
          recenterKey={recenterKey}
          fitKey={fitKey}
          padding={{ top: 110, bottom: panelH + 100 }}
          onPinTap={(p) => setFocused(p)}
          onOffline={setOfflineMap}
        />
      </Suspense>
      {stillLoading && <div className="absolute inset-0 grid place-items-center"><Spinner size={32} /></div>}
      {error && !data && (
        <div className="absolute inset-0 grid place-items-center px-10 text-center text-fg2">기록을 불러오지 못했어요.<br />{error}</div>
      )}

      {/* Top bar */}
      <div className="safe-top pointer-events-none absolute inset-x-0 top-0 z-10 px-4 pt-3">
        <div className="flex items-center justify-between gap-3">
          <button
            onClick={() => setPickerOpen(true)}
            disabled={!data?.days.length}
            className="glass tap pointer-events-auto flex min-w-0 items-center gap-2.5 rounded-full py-2.5 pr-3.5 pl-4 disabled:opacity-60"
            aria-label="날짜 선택"
          >
            <Icon name="calendar" size={18} className="text-accent" />
            <span className="truncate text-[15px] font-bold">{relativeDay(displayTime, clock)}</span>
            <span className="truncate text-[13px] text-fg2">{fmtDay(displayTime)}</span>
            <Icon name="chevronDown" size={16} className="text-fg3" />
          </button>
          <div className="glass pointer-events-auto flex shrink-0 items-center gap-2 rounded-full px-3.5 py-2.5" role="status">
            <span className="relative grid size-2.5 place-items-center">
              {status.pulse && <span className={cx('anim-pulse absolute size-2.5 rounded-full', status.dot)} />}
              <span className={cx('size-2.5 rounded-full', status.dot)} />
            </span>
            <span className="text-[13px] font-semibold">{status.label}</span>
          </div>
        </div>
        {offlineMap && (
          <div className="glass anim-pop pointer-events-auto mt-2 inline-flex items-center gap-2 rounded-full px-3.5 py-2 text-[12px] font-semibold text-fg2">
            <Icon name="wifiOff" size={14} />지도를 불러오지 못해 경로만 표시해요
          </div>
        )}
      </div>

      {/* Map controls */}
      <div className="absolute right-4 z-10 flex flex-col items-end gap-2.5" style={{ bottom: `calc(var(--tab-h) + var(--sab, 0px) + 32px + ${panelH}px)` }}>
        {away && (
          <button onClick={() => { jump(Date.now()); setRecenterKey((k) => k + 1) }} className="glass tap flex items-center gap-1.5 rounded-full px-4 py-2.5 text-[14px] font-bold text-accent">
            <Icon name="clock" size={17} />지금
          </button>
        )}
        <button onClick={() => setRecenterKey((k) => k + 1)} aria-label="내 위치" className="glass tap grid size-12 place-items-center rounded-full text-fg">
          <Icon name="locate" size={22} />
        </button>
        <button onClick={() => setManualOpen(true)} aria-label="압정 추가" className="tap grid size-14 place-items-center rounded-full bg-accent text-accentfg shadow-[var(--shadow)]">
          <Icon name="plus" size={28} strokeWidth={2.4} />
        </button>
      </div>

      {/* Bottom panel */}
      <div className="absolute inset-x-0 z-10 px-3" style={{ bottom: 'calc(var(--tab-h) + var(--sab, 0px) + 20px)' }}>
        <div ref={panel} className="glass overflow-hidden rounded-[28px]">
          <div className="flex items-end justify-between px-5 pt-4">
            <div>
              <div className="num text-[40px] leading-none font-extrabold tracking-tight">{fmtClock(displayTime)}</div>
              <div className="mt-1.5 text-[13px] text-fg2">
                {scrubbing ? '드래그해서 이동 중' : away ? `${fmtAgo(displayTime, clock)}의 기록` : '지금'}
              </div>
            </div>
            <button onClick={() => setDaySheetOpen(true)} className="tap flex items-center gap-1.5 rounded-full bg-accentsoft px-3.5 py-2 text-[13px] font-bold text-accent">
              <Icon name="list" size={16} />하루 보기
              <span className="num rounded-full bg-accent px-1.5 text-[11px] text-accentfg">{dayPins.length}</span>
            </button>
          </div>

          {(focused || nearby.length > 0) && (
            <div className="scroll-y flex gap-2 overflow-x-auto px-4 pt-3">
              {(focused ? [focused] : nearby).map((p) => (
                <PinCard key={p.id} pin={p} compact={!focused && nearby.length > 1} onJump={focused ? () => { jump(p.t); setFocused(null) } : undefined} onClose={focused ? () => setFocused(null) : undefined} />
              ))}
            </div>
          )}

          <div className="pt-1 pb-1.5">
            <Scrubber
              time={selected}
              pins={pins}
              tickMinutes={settings.dialTickMinutes}
              radiusMinutes={settings.dialRadiusMinutes}
              rangeStart={rangeStart}
              rangeEnd={clock}
              onStart={(t) => { setPreview(t); setFocused(null) }}
              onPreview={setPreview}
              onCommit={(t) => { setSelected(t); setPreview(null); setFitKey((k) => k + 1) }}
            />
          </div>
        </div>
      </div>

      <DayPickerSheet open={pickerOpen} onClose={() => setPickerOpen(false)} days={data?.days ?? []} current={displayTime} onPick={pickDay} />
      <ManualPinSheet
        open={manualOpen}
        onClose={() => setManualOpen(false)}
        types={data?.pinTypes ?? []}
        onSave={(text, typeId) => {
          setManualOpen(false)
          bridge.addManualPin({ text, typeId }).then(() => toast.show('압정을 꽂았어요')).catch(() => toast.show('압정을 저장하지 못했어요'))
        }}
      />
      <Sheet open={daySheetOpen} onClose={() => setDaySheetOpen(false)} title={`${fmtDay(dayStart)}`}>
        <DaySummary
          distance={routeDistance(dayPoints)}
          first={dayPoints[0]?.[0]}
          last={dayPoints[dayPoints.length - 1]?.[0]}
          pins={dayPins}
          onPick={(t) => { setDaySheetOpen(false); jump(t) }}
        />
      </Sheet>
      {toast.node}
    </div>
  )
}

function DaySummary({ distance, first, last, pins, onPick }: { distance: number; first?: number; last?: number; pins: Pin[]; onPick: (t: number) => void }) {
  const stats = [
    { label: '이동 거리', value: distance ? fmtDistance(distance) : '—' },
    { label: '기록 시간', value: first && last ? fmtDuration(last - first) : '—' },
    { label: '압정', value: `${pins.length}개` },
  ]
  return (
    <div className="px-5 pb-6">
      <div className="grid grid-cols-3 gap-2">
        {stats.map((s) => (
          <div key={s.label} className="rounded-2xl bg-surface2 px-3 py-3.5 text-center">
            <div className="num text-[18px] font-extrabold">{s.value}</div>
            <div className="mt-0.5 text-[12px] text-fg3">{s.label}</div>
          </div>
        ))}
      </div>
      <div className="mt-5 space-y-1">
        {pins.length === 0 && <p className="py-10 text-center text-[14px] text-fg3">이 날에는 압정이 없어요</p>}
        {pins.map((p, i) => (
          <button key={p.id} onClick={() => onPick(p.t)} className="tap flex w-full items-center gap-3 rounded-2xl p-2.5 text-left active:bg-surface2">
            <span className="num w-12 shrink-0 text-[13px] font-semibold text-fg3">{fmtClock(p.t)}</span>
            <span className="relative flex flex-col items-center self-stretch">
              <PinBadge pin={p} size={36} />
              {i < pins.length - 1 && <span className="absolute top-10 -bottom-3.5 w-0.5 rounded bg-line" />}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[15px] font-bold">{p.title}</span>
              {p.body && <span className="block truncate text-[13px] text-fg2">{p.body}</span>}
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

export { startOfDay }
