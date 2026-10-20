import { useMemo, useState } from 'react'
import { bridge } from '../lib/bridge'
import { useLiveData } from '../lib/hooks'
import { DAY, dayKey, fmtClock, parseDayKey, relativeDay, startOfDay, fmtDay } from '../lib/time'
import type { Pin, RuleSummary } from '../lib/types'
import { Icon } from '../ui/Icon'
import { Button, Card, Divider, Empty, Row, Segmented, Spinner, Switch, cx } from '../ui/kit'
import { PinBadge } from './parts'

type Tab = 'log' | 'rules'
type Period = 'today' | 'week' | 'month' | 'all'
const PERIODS: { value: Period; label: string; days: number }[] = [
  { value: 'today', label: '오늘', days: 1 },
  { value: 'week', label: '7일', days: 7 },
  { value: 'month', label: '30일', days: 30 },
  { value: 'all', label: '전체', days: 0 },
]

export function PinsScreen() {
  const [tab, setTab] = useState<Tab>('log')
  return (
    <div className="absolute inset-0 flex flex-col">
      <header className="safe-top shrink-0 px-5 pt-4 pb-3">
        <h1 className="mb-3 text-[28px] font-extrabold tracking-tight">압정</h1>
        <Segmented<Tab> value={tab} onChange={setTab} options={[{ value: 'log', label: '기록' }, { value: 'rules', label: '자동 규칙' }]} />
      </header>
      <div className="scroll-y flex-1 pb-[calc(var(--tab-h)+var(--sab,0px)+32px)]">{tab === 'log' ? <PinLog /> : <Rules />}</div>
    </div>
  )
}

function PinLog() {
  const [period, setPeriod] = useState<Period>('week')
  const [typeId, setTypeId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const { data, error } = useLiveData(async () => {
    const days = PERIODS.find((p) => p.value === period)!.days
    const end = startOfDay(Date.now()) + DAY
    const [pins, types] = await Promise.all([bridge.getPins({ start: days ? end - days * DAY : 0, end }), bridge.getPinTypes()])
    return { pins, types }
  }, [period])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (data?.pins ?? []).filter((p) => (!typeId || p.typeId === typeId) && (!q || `${p.title} ${p.body ?? ''}`.toLowerCase().includes(q)))
  }, [data, typeId, query])

  const groups = useMemo(() => {
    const m = new Map<string, Pin[]>()
    for (const p of filtered) {
      const k = dayKey(p.t)
      m.set(k, [...(m.get(k) ?? []), p])
    }
    return [...m.entries()]
  }, [filtered])

  return (
    <div className="px-4">
      <div className="mb-3 flex items-center gap-2 rounded-2xl bg-surface px-4 py-3">
        <Icon name="list" size={18} className="text-fg3" />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="압정 검색" className="min-w-0 flex-1 bg-transparent text-[15px] outline-none placeholder:text-fg3" />
        {query && <button aria-label="지우기" onClick={() => setQuery('')} className="text-fg3"><Icon name="x" size={16} /></button>}
      </div>
      <div className="scroll-y -mx-4 flex gap-2 overflow-x-auto px-4 pb-1">
        {PERIODS.map((p) => (
          <Chip key={p.value} active={period === p.value} onClick={() => setPeriod(p.value)}>{p.label}</Chip>
        ))}
        <span className="mx-1 w-px shrink-0 bg-line" />
        {(data?.types ?? []).map((t) => (
          <Chip key={t.id} active={typeId === t.id} color={t.color} onClick={() => setTypeId(typeId === t.id ? null : t.id)}>{t.name}</Chip>
        ))}
      </div>

      {!data && !error && <div className="grid place-items-center py-20"><Spinner size={30} /></div>}
      {error && <Empty icon="x" title="불러오지 못했어요" desc={error} />}
      {data && groups.length === 0 && <Empty icon="pin" title="압정이 없어요" desc="타임라인에서 + 버튼으로 지금 위치에 압정을 꽂거나, 자동 규칙을 만들어 보세요." />}
      {groups.map(([key, pins]) => (
        <section key={key} className="mt-5">
          <h3 className="mb-2 flex items-baseline gap-2 px-1">
            <span className="text-[17px] font-extrabold">{relativeDay(parseDayKey(key))}</span>
            <span className="text-[13px] text-fg3">{fmtDay(parseDayKey(key))} · {pins.length}개</span>
          </h3>
          <Card>
            {pins.map((p, i) => (
              <div key={p.id}>
                {i > 0 && <Divider />}
                <div className="flex items-start gap-3.5 px-4 py-3.5">
                  <PinBadge pin={p} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-baseline justify-between gap-2">
                      <span className="truncate text-[16px] font-bold">{p.title}</span>
                      <span className="num shrink-0 text-[13px] text-fg3">{fmtClock(p.t)}</span>
                    </div>
                    {p.body && <p className="mt-0.5 line-clamp-3 text-[14px] leading-snug text-fg2">{p.body}</p>}
                    <div className="mt-1.5 flex items-center gap-2 text-[12px] font-semibold">
                      <span style={{ color: p.color }}>{p.typeName}</span>
                      {p.lat === null && <span className="flex items-center gap-1 text-fg3"><Icon name="wifiOff" size={12} />위치 없음</span>}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </Card>
        </section>
      ))}
    </div>
  )
}

function Chip({ active, color, onClick, children }: { active: boolean; color?: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cx('tap shrink-0 rounded-full px-4 py-2 text-[14px] font-semibold', active ? 'text-white' : 'bg-surface text-fg2')}
      style={active ? { background: color ?? 'var(--accent)' } : undefined}
    >
      {children}
    </button>
  )
}

const SOURCE_LABEL: Record<RuleSummary['source'], string> = { PHONE_CALL: '전화', SMS: '문자', MMS: '멀티미디어 문자', NOTIFICATION: '앱 알림', ROUTINE: '루틴' }

function Rules() {
  const { data, error, reload } = useLiveData(() => bridge.getRules(), [], 300)
  const [local, setLocal] = useState<Record<string, boolean>>({})
  const toggle = (r: RuleSummary, enabled: boolean) => {
    setLocal((l) => ({ ...l, [r.id]: enabled }))
    bridge.setRuleEnabled({ id: r.id, enabled }).then(reload).catch(reload)
  }
  if (!data && !error) return <div className="grid place-items-center py-20"><Spinner size={30} /></div>
  if (error) return <Empty icon="x" title="불러오지 못했어요" desc={error} />
  const rules = data ?? []
  return (
    <div className="px-4">
      <p className="px-1 pb-3 text-[14px] leading-relaxed text-fg3">전화·문자·알림이 들어오면 규칙에 맞는 것만 그 순간의 위치에 압정으로 남겨요.</p>
      <Card>
        {rules.map((r, i) => (
          <div key={r.id}>
            {i > 0 && <Divider />}
            <Row
              icon={r.source === 'PHONE_CALL' ? 'phone' : r.source === 'SMS' || r.source === 'MMS' ? 'message' : r.source === 'ROUTINE' ? 'repeat' : 'bell'}
              tint={r.color}
              title={r.name}
              desc={[SOURCE_LABEL[r.source], r.conditionCount ? `조건 ${r.conditionCount}개` : null, r.packageCount ? `앱 ${r.packageCount}개` : null, r.hidden ? '숨김' : null].filter(Boolean).join(' · ')}
              trailing={<Switch label={`${r.name} 사용`} checked={local[r.id] ?? r.enabled} onChange={(v) => toggle(r, v)} />}
            />
          </div>
        ))}
      </Card>
      <Button variant="soft" className="mt-4 flex w-full items-center justify-center gap-2" onClick={() => bridge.openNative('rules')}>
        <Icon name="edit" size={18} />규칙 만들기 · 편집
      </Button>
    </div>
  )
}
