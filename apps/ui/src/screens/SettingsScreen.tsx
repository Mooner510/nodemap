import { useEffect, useRef, useState } from 'react'
import { bridge } from '../lib/bridge'
import { usePermissions } from '../lib/hooks'
import { fmtClock, fmtDuration, parseDayKey, relativeDay } from '../lib/time'
import type { AutoSyncStatus, DownloadLogEntry, OfflineDownload, OfflineMaps, Settings, TrackingPreset } from '../lib/types'
import { Icon, type IconName } from '../ui/Icon'
import { ActionPill, Button, Card, ConfirmSheet, Divider, GrantBadge, Row, Screen, SectionTitle, Segmented, Switch, cx, fmtBytes } from '../ui/kit'
import { DayPickerSheet } from './parts'

const PRESETS: { value: TrackingPreset; label: string; desc: string }[] = [
  { value: 'PRECISE', label: '정밀', desc: '이동 경로를 가장 촘촘하게 기록해요. 배터리를 더 써요.' },
  { value: 'BALANCED', label: '균형', desc: '정확도와 배터리 사용량을 균형 있게 맞춰요.' },
  { value: 'BATTERY', label: '절전', desc: '배터리를 아끼는 대신 기록 간격이 넓어요.' },
]

const STYLES = [
  { name: '기본', uri: 'https://tiles.openfreemap.org/styles/liberty' },
  { name: '밝은', uri: 'https://tiles.openfreemap.org/styles/bright' },
  { name: '단순', uri: 'https://tiles.openfreemap.org/styles/positron' },
  { name: '어두운', uri: 'https://tiles.openfreemap.org/styles/dark' },
]

export function SettingsScreen({ settings, update }: { settings: Settings; update: (p: Partial<Settings>) => void }) {
  const preset = PRESETS.find((p) => p.value === settings.trackingPreset)!
  const { perms, request } = usePermissions()
  return (
    <Screen title="설정">
      <SectionTitle>위치 기록</SectionTitle>
      <Card>
        <Row icon="route" title="위치 기록" desc={settings.trackingEnabled ? '이동 경로를 계속 기록하고 있어요' : '지금은 기록하지 않아요'} trailing={<Switch label="위치 기록" checked={settings.trackingEnabled} onChange={(v) => update({ trackingEnabled: v })} />} />
        <Divider />
        <div className="px-5 py-4">
          <div className="mb-2.5 text-[16px] font-semibold">기록 정확도</div>
          <Segmented<TrackingPreset> value={settings.trackingPreset} options={PRESETS.map((p) => ({ value: p.value, label: p.label }))} onChange={(v) => update({ trackingPreset: v })} />
          <p className="mt-2.5 text-[13px] leading-snug text-fg3">{preset.desc}</p>
        </div>
        <Divider />
        <Row icon="battery" tint="#16a34a" title="부팅 후 자동 시작" desc="기기를 켜면 기록을 이어서 시작해요" trailing={<Switch label="부팅 후 자동 시작" checked={settings.autoStartAfterBoot} onChange={(v) => update({ autoStartAfterBoot: v })} />} />
      </Card>

      <SectionTitle>보안 · 개인정보</SectionTitle>
      <Card>
        <Row icon="lock" tint="#8b5cf6" title="앱 잠금" desc="생체 인증이나 화면 잠금으로 열어요" trailing={<Switch label="앱 잠금" checked={settings.biometricLockEnabled} onChange={(v) => update({ biometricLockEnabled: v })} />} />
        <Divider />
        <Row icon="pin" tint="#f59e0b" title="주소로 변환" desc="좌표를 주소로 바꿔 보여줘요. 좌표가 외부 서버로 전송돼요." trailing={<Switch label="주소로 변환" checked={settings.reverseGeocodingEnabled} onChange={(v) => update({ reverseGeocodingEnabled: v })} />} />
      </Card>

      <SectionTitle>지도</SectionTitle>
      <Card>
        <div className="px-5 py-4">
          <div className="mb-2.5 flex items-center gap-2 text-[16px] font-semibold"><Icon name="layers" size={18} className="text-accent" />지도 스타일</div>
          <div className="grid grid-cols-4 gap-2">
            {STYLES.map((s) => (
              <button key={s.uri} onClick={() => update({ mapStyleUri: s.uri })} className={cx('tap rounded-xl py-2.5 text-[14px] font-semibold', settings.mapStyleUri === s.uri ? 'bg-accent text-accentfg' : 'bg-surface2 text-fg2')}>{s.name}</button>
            ))}
          </div>
        </div>
      </Card>

      <SectionTitle>권한</SectionTitle>
      <Card>
        <PermissionRow icon="locate" title="정밀 위치" desc="이동 경로 기록에 필요해요" granted={perms?.location} onClick={() => request('location')} />
        <Divider />
        <PermissionRow icon="route" title="항상 위치 허용" desc="권한 › 위치에서 ‘항상 허용’ 선택" granted={perms?.backgroundLocation} onClick={() => request('backgroundLocation')} />
        <Divider />
        <PermissionRow icon="message" title="문자 · 통화 기록" desc="전화·문자 자동 압정" granted={perms?.sensitive} onClick={() => request('sensitive')} />
        <Divider />
        <PermissionRow icon="bell" title="알림 접근" desc="앱 알림 자동 압정" granted={perms?.notificationAccess} onClick={() => request('notificationAccess')} />
        <Divider />
        <Row icon="battery" tint="#16a34a" title="배터리 최적화 예외" desc="장시간 기록이 끊기지 않게 해요" onClick={() => request('batteryOptimization')} trailing={<Icon name="chevronRight" size={18} className="text-fg3" />} />
      </Card>
      <p className="px-5 pt-2 text-[12px] leading-relaxed text-fg3">허용하지 않은 권한에 해당하는 기능만 꺼져요.</p>

      <SectionTitle>오프라인 지도</SectionTitle>
      <OfflineMapsCard settings={settings} update={update} />

      <SectionTitle>타임라인 표시</SectionTitle>
      <Card>
        <Slider label="시간 눈금 간격" unit="분" value={settings.dialTickMinutes} min={1} max={30} step={1} onChange={(v) => update({ dialTickMinutes: v })} />
        <Divider />
        <Slider label="시간 막대 표시 범위" unit="분" value={settings.dialRadiusMinutes} min={30} max={720} step={30} onChange={(v) => update({ dialRadiusMinutes: v })} help="현재 시각 기준 좌우로 보이는 범위" />
        <Divider />
        <Slider label="전체 경로 표시" unit="분" value={settings.totalWindowMinutes} min={60} max={1440} step={60} onChange={(v) => update({ totalWindowMinutes: v })} help="지도에 옅게 보이는 지난 경로 길이" />
        <Divider />
        <Slider label="상세 경로 표시" unit="분" value={settings.detailWindowMinutes} min={10} max={Math.max(10, settings.totalWindowMinutes)} step={10} onChange={(v) => update({ detailWindowMinutes: v })} help="진하게 보이는 최근 경로 길이" />
        <Divider />
        <Slider label="카메라 맞춤 범위" unit="분" value={settings.cameraWindowMinutes} min={5} max={Math.max(5, settings.detailWindowMinutes)} step={5} onChange={(v) => update({ cameraWindowMinutes: v })} help="이동을 멈추면 이 범위가 화면에 들어오게 맞춰요" />
      </Card>
      <p className="px-5 pt-6 text-center text-[12px] text-fg3">NodeMap</p>
    </Screen>
  )
}

function PermissionRow({ icon, title, desc, granted, onClick }: { icon: IconName; title: string; desc: string; granted?: boolean; onClick: () => void }) {
  return <Row icon={icon} title={title} desc={desc} trailing={granted ? <GrantBadge /> : <ActionPill onClick={onClick}>허용</ActionPill>} />
}

/** A tile arriving less than this long ago means the download is really moving. */
const STALL_MS = 20_000

function useNow(active: boolean) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [active])
  return now
}

function secondsAgo(t: number, now: number) {
  const s = Math.max(0, Math.round((now - t) / 1000))
  return s < 60 ? `${s}초 전` : `${Math.floor(s / 60)}분 ${s % 60}초 전`
}

function autoStatusText(auto: AutoSyncStatus, dl: OfflineDownload | undefined) {
  if (!auto.enabled) return '꺼져 있어요'
  if (dl?.state === 'downloading' && dl.auto) return '지금 받는 중이에요'
  if (!auto.wifi) return '대기 중 · Wi-Fi에 연결되면 받아요'
  if (dl?.state === 'downloading') return '대기 중 · 직접 저장이 끝나면 이어서 받아요'
  return 'Wi-Fi 연결됨 · 새 경로가 생기면 받아요'
}

function OfflineMapsCard({ settings, update }: { settings: Settings; update: (p: Partial<Settings>) => void }) {
  const [data, setData] = useState<OfflineMaps | null>(null)
  const [range, setRange] = useState<{ start: string; end: string } | null>(null)
  const [picking, setPicking] = useState<'start' | 'end' | null>(null)
  const [deleting, setDeleting] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    const load = () => bridge.getOfflineMaps().then((d) => {
      if (!alive) return
      setData(d)
      // Default to the latest recorded day; drop a selection whose day no longer has data.
      setRange((r) => {
        const last = d.days[d.days.length - 1]
        if (!last) return null
        return r && d.days.includes(r.start) && d.days.includes(r.end) ? r : { start: last, end: last }
      })
    })
    load()
    const offData = bridge.onDataChanged(() => bridge.getOfflineMaps().then((d) => alive && setData((cur) => (cur ? { ...cur, days: d.days } : d))))
    const offDl = bridge.on('offline-changed', (download) => {
      setData((cur) => (cur ? { ...cur, download } : cur))
      // Reload on every state change so the log shows a run as soon as it starts and once it ends.
      if (download.state !== lastState.current) load()
      lastState.current = download.state
    })
    const offAuto = bridge.on('offline-auto-changed', (auto) => setData((cur) => (cur ? { ...cur, auto } : cur)))
    return () => { alive = false; offData(); offDl(); offAuto() }
  }, [])
  const lastState = useRef<OfflineDownload['state'] | null>(null)

  const dl = data?.download
  const downloading = dl?.state === 'downloading'
  const pct = dl?.state === 'downloading' && dl.required > 0 ? Math.min(1, dl.completed / dl.required) : null
  const running = data?.log.some((l) => l.result === 'running') ?? false
  const now = useNow(downloading || running)

  const start = async () => {
    if (!range) return
    setError(null)
    try {
      await bridge.downloadOfflineMap(range)
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장하지 못했어요.')
    }
  }

  const resume = async (id: number) => {
    setError(null)
    try {
      await bridge.resumeOfflineMap({ id })
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장하지 못했어요.')
    }
  }

  const remove = async (id: number) => {
    try {
      await bridge.deleteOfflineMap({ id })
      setData((cur) => (cur ? { ...cur, regions: cur.regions.filter((r) => r.id !== id) } : cur))
    } catch (e) {
      setError(e instanceof Error ? e.message : '삭제하지 못했어요.')
    }
  }

  const days = data?.days ?? []
  const deletingRegion = data?.regions.find((r) => r.id === deleting)
  return (
    <Card>
      <Row
        icon="route"
        title="다닌 곳 자동 저장"
        desc={data ? autoStatusText(data.auto, dl) : 'Wi-Fi에 연결된 채 앱을 보고 있을 때만 받아요'}
        trailing={<Switch label="다닌 곳 자동 저장" checked={settings.autoOfflineMaps} onChange={(v) => update({ autoOfflineMaps: v })} />}
      />
      <p className="px-5 pb-3 text-[12px] leading-relaxed text-fg3">새로 기록된 경로(점선 구간 포함) 주변 중 아직 없는 지도만 받아요. Wi-Fi가 끊기거나 앱을 벗어나면 바로 멈추고, 다음에 이어서 받아요.</p>
      <Divider />
      <div className="px-5 pt-4">
        <p className="text-[13px] leading-relaxed text-fg3">선택한 기간에 이동한 경로 주변의 지도를 저장해요. 저장한 지도는 인터넷 없이도 모든 지도 스타일에서 보여요.</p>
        <div className="mt-3 grid grid-cols-2 gap-2">
          {(['start', 'end'] as const).map((k) => (
            <button key={k} disabled={!range} onClick={() => setPicking(k)} className="tap rounded-2xl bg-surface2 px-4 py-3 text-left disabled:opacity-40">
              <span className="block text-[12px] font-semibold text-fg3">{k === 'start' ? '시작' : '종료'}</span>
              <span className="num block text-[15px] font-bold">{range?.[k] ?? '-'}</span>
            </button>
          ))}
        </div>
        <Button disabled={!range || downloading} onClick={start} className="mt-3 flex w-full items-center justify-center gap-2">
          <Icon name="download" size={18} />{downloading ? '저장 중' : '선택 기간 지도 저장'}
        </Button>
        {data && days.length === 0 && <p className="mt-2 text-[13px] text-fg3">아직 기록된 위치가 없어요.</p>}
        {dl?.state === 'downloading' && (
          <div className="mt-3 space-y-1.5">
            <div className="h-2 overflow-hidden rounded-full bg-surface2">
              <div className={cx('h-full rounded-full bg-accent', pct === null && 'w-1/3 animate-pulse')} style={pct === null ? undefined : { width: `${pct * 100}%` }} />
            </div>
            <p className="text-[13px] text-fg2">{dl.name}{pct !== null && ` · ${Math.round(pct * 100)}%`}{dl.bytes > 0 && ` · ${fmtBytes(dl.bytes)}`}</p>
            <Heartbeat lastTileAt={dl.lastTileAt ?? null} now={now} />
          </div>
        )}
        {dl?.state === 'complete' && <p className="mt-2 text-[13px] font-semibold text-accent">{dl.name} 저장 완료 · {fmtBytes(dl.bytes)}</p>}
        {dl?.state === 'failed' && <p className="mt-2 text-[13px] font-semibold text-danger">저장 실패 · {dl.message}</p>}
        {error && <p className="mt-2 text-[13px] font-semibold text-danger">{error}</p>}
      </div>
      <div className="pb-2" />
      {data?.regions.map((r) => (
        <div key={r.id}>
          <Divider />
          <div className="flex items-center gap-3 px-5 py-3">
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[15px] font-semibold">{r.name}{r.auto && <span className="ml-1.5 rounded-md bg-accentsoft px-1.5 py-0.5 text-[11px] font-bold text-accent">자동</span>}</span>
              <span className="num block text-[13px] text-fg3">{r.completed}/{r.required} · {fmtBytes(r.bytes)}</span>
            </span>
            {r.completed < r.required && !downloading && !r.auto && <ActionPill onClick={() => resume(r.id)}>이어 받기</ActionPill>}
            <button onClick={() => setDeleting(r.id)} aria-label={`${r.name} 삭제`} className="tap grid size-9 place-items-center rounded-xl text-fg3"><Icon name="trash" size={18} /></button>
          </div>
        </div>
      ))}

      {data && data.log.length > 0 && (
        <>
          <Divider />
          <div className="px-5 pt-4 pb-1 text-[13px] font-bold text-fg3">다운로드 기록</div>
          {data.log.map((l) => <LogRow key={l.id} entry={l} live={dl?.state === 'downloading' ? dl : null} now={now} />)}
          <div className="pb-2" />
        </>
      )}

      {range && (
        <DayPickerSheet
          key={picking ?? 'closed'}
          open={picking !== null}
          onClose={() => setPicking(null)}
          days={picking === 'start' ? days.filter((d) => d <= range.end) : days.filter((d) => d >= range.start)}
          current={parseDayKey(picking === 'end' ? range.end : range.start)}
          onPick={(d) => setRange(picking === 'start' ? { start: d, end: range.end } : { start: range.start, end: d })}
        />
      )}
      <ConfirmSheet
        open={deleting !== null}
        title="오프라인 지도 삭제"
        message={`‘${deletingRegion?.name ?? ''}’ 영역을 기기에서 지울까요?`}
        confirmLabel="삭제"
        danger
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting !== null && remove(deleting)}
      />
    </Card>
  )
}

/** "마지막 수신 n초 전": proof that tiles are actually arriving, not just a spinner. */
function Heartbeat({ lastTileAt, now }: { lastTileAt: number | null; now: number }) {
  if (lastTileAt === null) return <p className="text-[12px] text-fg3">서버에 연결하는 중…</p>
  const stalled = now - lastTileAt > STALL_MS
  return (
    <p className={cx('num flex items-center gap-1.5 text-[12px] font-semibold', stalled ? 'text-warn' : 'text-ok')}>
      <span className={cx('size-2 rounded-full', stalled ? 'bg-warn' : 'animate-pulse bg-ok')} />
      마지막 수신 {secondsAgo(lastTileAt, now)}{stalled && ' · 응답이 늦어요'}
    </p>
  )
}

const RESULT_LABEL: Record<DownloadLogEntry['result'], { text: string; cls: string }> = {
  running: { text: '진행 중', cls: 'bg-ok/12 text-ok' },
  complete: { text: '완료', cls: 'bg-surface2 text-fg2' },
  stopped: { text: '중단', cls: 'bg-warn/12 text-warn' },
  failed: { text: '실패', cls: 'bg-danger/12 text-danger' },
}

function LogRow({ entry: l, live, now }: { entry: DownloadLogEntry; live: Extract<OfflineDownload, { state: 'downloading' }> | null; now: number }) {
  const label = RESULT_LABEL[l.result]
  const isRunning = l.result === 'running'
  // The row is written every ~2 s; the live progress event is fresher while this run is the active one.
  const lastTileAt = isRunning && live?.name === l.name ? (live.lastTileAt ?? null) : l.lastTileAt
  const end = l.endedAt ?? now
  return (
    <div className="px-5 py-2.5">
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-[14px] font-semibold">{l.name}</span>
        <span className={cx('shrink-0 rounded-md px-2 py-0.5 text-[11px] font-bold', label.cls)}>{label.text}</span>
      </div>
      <p className="num mt-0.5 text-[12px] text-fg3">
        {relativeDay(l.startedAt, now)} {fmtClock(l.startedAt)} · {end - l.startedAt < 60_000 ? `${Math.max(1, Math.round((end - l.startedAt) / 1000))}초` : fmtDuration(end - l.startedAt)} · 타일 {l.tiles.toLocaleString()}개 · {fmtBytes(l.bytes)}
        {l.remaining > 0 && !isRunning && ` · 남은 ${l.remaining.toLocaleString()}개`}
      </p>
      {isRunning && <Heartbeat lastTileAt={lastTileAt} now={now} />}
      {l.message && <p className="text-[12px] text-fg3">{l.message}</p>}
    </div>
  )
}

function Slider({ label, unit, value, min, max, step, onChange, help }: { label: string; unit: string; value: number; min: number; max: number; step: number; onChange: (v: number) => void; help?: string }) {
  const fmt = (v: number) => (v >= 60 && v % 60 === 0 ? `${v / 60}시간` : `${v}${unit}`)
  return (
    <div className="px-5 py-4">
      <div className="flex items-baseline justify-between">
        <span className="text-[16px] font-semibold">{label}</span>
        <span className="num text-[15px] font-bold text-accent">{fmt(value)}</span>
      </div>
      {help && <p className="mt-0.5 text-[13px] text-fg3">{help}</p>}
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={Math.min(Math.max(value, min), max)}
        aria-label={label}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-3 h-1.5 w-full touch-pan-y appearance-none rounded-full bg-surface2 accent-[var(--accent)]"
      />
    </div>
  )
}
