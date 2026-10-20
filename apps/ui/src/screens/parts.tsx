import { useMemo, useState } from 'react'
import { Icon, PIN_ICON } from '../ui/Icon'
import { Button, Sheet, cx } from '../ui/kit'
import { DAY, dayKey, fmtClock, fmtDay, parseDayKey, relativeDay } from '../lib/time'
import type { Pin, PinType } from '../lib/types'

export function PinBadge({ pin, size = 40 }: { pin: Pick<Pin, 'color' | 'icon'>; size?: number }) {
  return (
    <span className="grid shrink-0 place-items-center rounded-2xl text-white" style={{ width: size, height: size, background: pin.color }}>
      <Icon name={PIN_ICON[pin.icon] ?? 'pin'} size={size * 0.5} />
    </span>
  )
}

export function PinCard({ pin, onJump, onClose, compact }: { pin: Pin; onJump?: () => void; onClose?: () => void; compact?: boolean }) {
  return (
    <div className={cx('anim-pop flex items-start gap-3 rounded-2xl bg-surface2/80 p-3', compact && 'min-w-[240px]')}>
      <PinBadge pin={pin} />
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="truncate text-[15px] font-bold">{pin.title}</span>
          <span className="num shrink-0 text-[12px] text-fg3">{fmtClock(pin.t)}</span>
        </div>
        {pin.body && <p className="mt-0.5 line-clamp-2 text-[13px] leading-snug text-fg2">{pin.body}</p>}
        <span className="mt-1 inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: `${pin.color}22`, color: pin.color }}>{pin.typeName}</span>
      </div>
      {onJump && (
        <button onClick={onJump} aria-label="이 시각으로 이동" className="tap grid size-9 shrink-0 place-items-center rounded-xl bg-accentsoft text-accent"><Icon name="clock" size={18} /></button>
      )}
      {onClose && (
        <button onClick={onClose} aria-label="닫기" className="tap grid size-9 shrink-0 place-items-center rounded-xl text-fg3"><Icon name="x" size={18} /></button>
      )}
    </div>
  )
}

export function DayPickerSheet({ open, onClose, days, current, onPick }: { open: boolean; onClose: () => void; days: string[]; current: number; onPick: (key: string) => void }) {
  const available = useMemo(() => new Set(days), [days])
  const [cursor, setCursor] = useState(() => { const d = new Date(current); return new Date(d.getFullYear(), d.getMonth(), 1) })
  const first = cursor.getDay()
  const count = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0).getDate()
  const cells = [...Array(first).fill(null), ...Array.from({ length: count }, (_, i) => i + 1)]
  const today = dayKey(Date.now())
  const selected = dayKey(current)
  const shift = (n: number) => setCursor(new Date(cursor.getFullYear(), cursor.getMonth() + n, 1))
  const key = (d: number) => dayKey(new Date(cursor.getFullYear(), cursor.getMonth(), d).getTime())
  const hasPrev = days.length > 0 && parseDayKey(days[0]) < cursor.getTime()
  const hasNext = days.length > 0 && parseDayKey(days[days.length - 1]) >= new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1).getTime()

  return (
    <Sheet open={open} onClose={onClose} title="날짜 선택">
      <div className="px-5 pb-6">
        <div className="mb-3 flex items-center justify-between">
          <button aria-label="이전 달" disabled={!hasPrev} onClick={() => shift(-1)} className="tap grid size-10 place-items-center rounded-xl text-fg2 disabled:opacity-25"><Icon name="chevronLeft" /></button>
          <span className="text-[16px] font-bold">{cursor.getFullYear()}년 {cursor.getMonth() + 1}월</span>
          <button aria-label="다음 달" disabled={!hasNext} onClick={() => shift(1)} className="tap grid size-10 place-items-center rounded-xl text-fg2 disabled:opacity-25"><Icon name="chevronRight" /></button>
        </div>
        <div className="grid grid-cols-7 gap-y-1 text-center text-[12px] font-semibold text-fg3">
          {['일', '월', '화', '수', '목', '금', '토'].map((w) => <span key={w} className="py-1">{w}</span>)}
        </div>
        <div className="grid grid-cols-7 gap-y-1">
          {cells.map((d, i) => {
            if (d === null) return <span key={i} />
            const k = key(d)
            const has = available.has(k)
            return (
              <button
                key={i}
                disabled={!has}
                onClick={() => { onPick(k); onClose() }}
                className={cx(
                  'tap relative mx-auto grid size-11 place-items-center rounded-full text-[15px] font-semibold',
                  k === selected ? 'bg-accent text-accentfg' : has ? 'text-fg' : 'text-fg3/40',
                  k === today && k !== selected && 'ring-2 ring-accent/50',
                )}
              >
                {d}
                {has && k !== selected && <span className="absolute bottom-1 size-1 rounded-full bg-accent" />}
              </button>
            )
          })}
        </div>
        <p className="mt-4 text-center text-[13px] text-fg3">기록이 있는 날짜만 선택할 수 있어요</p>
      </div>
    </Sheet>
  )
}

export function ManualPinSheet({ open, onClose, types, onSave }: { open: boolean; onClose: () => void; types: PinType[]; onSave: (text: string, typeId: string) => void }) {
  const [text, setText] = useState('')
  const [typeId, setTypeId] = useState<string | null>(null)
  const chosen = typeId ?? types.find((t) => t.name === '일반')?.id ?? types[0]?.id
  return (
    <Sheet open={open} onClose={onClose} title="지금 위치에 압정 꽂기">
      <div className="space-y-5 px-6 pt-1 pb-6">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="무엇을 기억하고 싶나요? (선택)"
          rows={3}
          className="w-full resize-none rounded-2xl bg-surface2 p-4 text-[16px] outline-none placeholder:text-fg3 focus:ring-2 focus:ring-accent/50"
        />
        <div className="flex flex-wrap gap-2">
          {types.map((t) => (
            <button
              key={t.id}
              onClick={() => setTypeId(t.id)}
              className={cx('tap flex items-center gap-2 rounded-full px-3.5 py-2 text-[14px] font-semibold', t.id === chosen ? 'text-white' : 'bg-surface2 text-fg2')}
              style={t.id === chosen ? { background: t.color } : undefined}
            >
              <Icon name={PIN_ICON[t.icon] ?? 'pin'} size={16} />{t.name}
            </button>
          ))}
        </div>
        <Button className="w-full" disabled={!chosen} onClick={() => { if (chosen) { onSave(text.trim(), chosen); setText('') } }}>압정 꽂기</Button>
      </div>
    </Sheet>
  )
}

export { DAY, fmtDay, relativeDay }
