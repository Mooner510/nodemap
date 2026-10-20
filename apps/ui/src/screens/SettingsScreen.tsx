import { bridge } from '../lib/bridge'
import type { Settings, TrackingPreset } from '../lib/types'
import { Icon } from '../ui/Icon'
import { Card, Divider, Row, SectionTitle, Segmented, Switch, cx } from '../ui/kit'

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
  return (
    <div className="absolute inset-0 flex flex-col">
      <header className="safe-top shrink-0 px-5 pt-4 pb-1">
        <h1 className="text-[28px] font-extrabold tracking-tight">설정</h1>
      </header>
      <div className="scroll-y flex-1 px-4 pb-[calc(var(--tab-h)+var(--sab,0px)+32px)]">
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
          <Divider />
          <Row icon="download" tint="#0ea5e9" title="오프라인 지도 · 권한" desc="구간 다운로드, 알림·문자 접근 권한 설정" onClick={() => bridge.openNative('advancedSettings')} trailing={<Icon name="chevronRight" size={18} className="text-fg3" />} />
        </Card>

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
      </div>
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
