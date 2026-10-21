import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { BackupProgress } from '../lib/types'
import { Icon, type IconName } from './Icon'

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(' ')
}

export function Switch({ checked, onChange, disabled, label }: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cx('relative h-[30px] w-[50px] shrink-0 rounded-full transition-colors duration-200 disabled:opacity-40', checked ? 'bg-accent' : 'bg-fg3/40')}
    >
      <span className={cx('absolute top-[3px] left-[3px] size-6 rounded-full bg-white shadow transition-transform duration-200', checked && 'translate-x-5')} />
    </button>
  )
}

export function Segmented<T extends string>({ value, options, onChange }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div role="tablist" className="flex gap-1 rounded-2xl bg-surface2 p-1">
      {options.map((o) => (
        <button
          key={o.value}
          role="tab"
          aria-selected={o.value === value}
          onClick={() => onChange(o.value)}
          className={cx('tap flex-1 rounded-xl py-2 text-[14px] font-semibold', o.value === value ? 'bg-surface text-fg shadow-sm' : 'text-fg3')}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('overflow-hidden rounded-3xl bg-surface', className)}>{children}</div>
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return <h2 className="px-5 pt-6 pb-2 text-[13px] font-semibold tracking-wide text-fg3">{children}</h2>
}

export function Row({ icon, tint, title, desc, trailing, onClick }: { icon?: IconName; tint?: string; title: string; desc?: string; trailing?: ReactNode; onClick?: () => void }) {
  const body = (
    <>
      {icon && (
        <span className="grid size-10 shrink-0 place-items-center rounded-2xl" style={{ background: `${tint ?? '#3182F6'}22`, color: tint ?? 'var(--accent)' }}>
          <Icon name={icon} size={20} />
        </span>
      )}
      <span className="min-w-0 flex-1 text-left">
        <span className="block text-[16px] font-semibold">{title}</span>
        {desc && <span className="mt-0.5 block text-[13px] leading-snug text-fg3">{desc}</span>}
      </span>
      {trailing}
    </>
  )
  const cls = 'flex w-full items-center gap-3.5 px-5 py-3.5'
  return onClick ? (
    <button onClick={onClick} className={cx(cls, 'tap active:bg-surface2')}>{body}</button>
  ) : (
    <div className={cls}>{body}</div>
  )
}

export function Divider() {
  return <div className="mx-5 h-px bg-line" />
}

export function Spinner({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" className="animate-spin text-accent" aria-label="로딩 중">
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeOpacity=".2" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  )
}

export function Empty({ icon, title, desc }: { icon: IconName; title: string; desc?: string }) {
  return (
    <div className="flex flex-col items-center gap-2 px-8 py-16 text-center">
      <span className="grid size-16 place-items-center rounded-3xl bg-surface2 text-fg3"><Icon name={icon} size={30} /></span>
      <p className="mt-2 text-[17px] font-bold">{title}</p>
      {desc && <p className="text-[14px] leading-relaxed text-fg3">{desc}</p>}
    </div>
  )
}

/** Bottom sheet. Drag the handle down (or tap the scrim) to dismiss. */
export function Sheet({ open, onClose, title, children }: { open: boolean; onClose: () => void; title?: string; children: ReactNode }) {
  const [dy, setDy] = useState(0)
  const start = useRef<number | null>(null)
  useEffect(() => {
    if (!open) setDy(0)
  }, [open])
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-end" role="dialog" aria-modal="true" aria-label={title}>
      <div className="anim-fade absolute inset-0 bg-black/45" onClick={onClose} />
      <div
        className="anim-sheet relative max-h-[88%] w-full overflow-hidden rounded-t-[28px] bg-surface"
        style={{ transform: dy ? `translateY(${dy}px)` : undefined, transition: start.current === null ? 'transform .2s' : 'none', paddingBottom: 'var(--sab, 0px)' }}
      >
        <div
          className="flex touch-none justify-center pt-3 pb-2"
          onPointerDown={(e) => { start.current = e.clientY; e.currentTarget.setPointerCapture(e.pointerId) }}
          onPointerMove={(e) => start.current !== null && setDy(Math.max(0, e.clientY - start.current))}
          onPointerUp={() => {
            const shouldClose = dy > 90
            start.current = null
            if (shouldClose) onClose()
            else setDy(0)
          }}
        >
          <span className="h-1.5 w-10 rounded-full bg-fg3/40" />
        </div>
        {title && <h3 className="px-6 pb-2 text-[20px] font-bold">{title}</h3>}
        <div className="scroll-y max-h-[calc(88vh-70px)]">{children}</div>
      </div>
    </div>
  )
}

export function Button({ children, onClick, variant = 'primary', disabled, className }: { children: ReactNode; onClick?: () => void; variant?: 'primary' | 'soft' | 'ghost' | 'danger'; disabled?: boolean; className?: string }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={cx(
        'tap rounded-2xl px-5 py-3.5 text-[16px] font-bold disabled:opacity-40',
        variant === 'primary' && 'bg-accent text-accentfg',
        variant === 'soft' && 'bg-accentsoft text-accent',
        variant === 'ghost' && 'text-fg2',
        variant === 'danger' && 'bg-danger text-white',
        className,
      )}
    >
      {children}
    </button>
  )
}

export function useToast() {
  const [msg, setMsg] = useState<string | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const show = (m: string) => {
    setMsg(m)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setMsg(null), 2200)
  }
  const node = msg ? (
    <div className="pointer-events-none fixed inset-x-0 z-[60] flex justify-center px-6" style={{ bottom: 'calc(var(--tab-h) + var(--sab, 0px) + 28px)' }}>
      <div className="anim-pop glass rounded-full px-5 py-3 text-[14px] font-semibold">{msg}</div>
    </div>
  ) : null
  return { show, node }
}

export function Chip({ active, color, onClick, disabled, children }: { active: boolean; color?: string; onClick: () => void; disabled?: boolean; children: ReactNode }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
      className={cx('tap flex shrink-0 items-center gap-1.5 rounded-full px-4 py-2 text-[14px] font-semibold disabled:opacity-35', active ? 'text-white' : 'bg-surface2 text-fg2')}
      style={active ? { background: color ?? 'var(--accent)' } : undefined}
    >
      {children}
    </button>
  )
}

export function TextField({ label, value, onChange, type = 'text', placeholder, error, hint, rows, autoFocus, mono }: {
  label: string
  value: string
  onChange: (v: string) => void
  type?: 'text' | 'password'
  placeholder?: string
  error?: string | null
  hint?: string
  rows?: number
  autoFocus?: boolean
  mono?: boolean
}) {
  const cls = cx(
    'w-full rounded-2xl bg-surface2 px-4 py-3.5 text-[16px] outline-none placeholder:text-fg3 focus:ring-2',
    error ? 'ring-2 ring-danger/60 focus:ring-danger/60' : 'focus:ring-accent/50',
    mono && 'font-mono text-[15px]',
  )
  return (
    <label className="block">
      <span className="mb-1.5 block px-1 text-[13px] font-semibold text-fg2">{label}</span>
      {rows ? (
        <textarea value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} rows={rows} autoFocus={autoFocus} className={cx(cls, 'resize-none')} />
      ) : (
        <input value={value} onChange={(e) => onChange(e.target.value)} type={type} placeholder={placeholder} autoFocus={autoFocus} autoComplete={type === 'password' ? 'off' : undefined} spellCheck={false} className={cls} />
      )}
      {(error || hint) && <span className={cx('mt-1.5 block px-1 text-[12px] leading-snug', error ? 'text-danger' : 'text-fg3')}>{error || hint}</span>}
    </label>
  )
}

/** A yes/no question as a bottom sheet (the WebView has no native confirm dialog). */
export function ConfirmSheet({ open, title, message, confirmLabel, danger, onConfirm, onClose }: { open: boolean; title: string; message: string; confirmLabel: string; danger?: boolean; onConfirm: () => void; onClose: () => void }) {
  return (
    <Sheet open={open} onClose={onClose} title={title}>
      <div className="px-6 pb-6">
        <p className="text-[15px] leading-relaxed text-fg2">{message}</p>
        <div className="mt-6 flex gap-2">
          <Button variant="ghost" className="flex-1 bg-surface2" onClick={onClose}>취소</Button>
          <Button variant={danger ? 'danger' : 'primary'} className="flex-1" onClick={() => { onClose(); onConfirm() }}>{confirmLabel}</Button>
        </div>
      </div>
    </Sheet>
  )
}

/** Asks for the backup password (8+ chars) before a backup/restore starts. */
export function PasswordSheet({ open, title, confirmLabel, onSubmit, onClose }: { open: boolean; title: string; confirmLabel: string; onSubmit: (password: string) => void; onClose: () => void }) {
  const [pw, setPw] = useState('')
  useEffect(() => {
    if (!open) setPw('')
  }, [open])
  const ok = pw.length >= 8
  return (
    <Sheet open={open} onClose={onClose} title={title}>
      <form className="space-y-5 px-6 pt-1 pb-6" onSubmit={(e) => { e.preventDefault(); if (ok) { onSubmit(pw); setPw(''); onClose() } }}>
        <TextField label="백업 비밀번호" type="password" value={pw} onChange={setPw} autoFocus hint="8자 이상 · 서버에 저장되지 않아요. 잊으면 복원할 수 없어요." />
        <button type="submit" disabled={!ok} className="tap w-full rounded-2xl bg-accent px-5 py-3.5 text-[16px] font-bold text-accentfg disabled:opacity-40">{confirmLabel}</button>
      </form>
    </Sheet>
  )
}

const fmtSec = (s: number) => (s >= 60 ? `${Math.floor(s / 60)}분 ${s % 60}초` : `${s}초`)

/** Live status of a running backup/restore: step, percent, elapsed and estimated time left. */
export function ProgressPanel({ progress, startedAt }: { progress: BackupProgress; startedAt: number }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 500)
    return () => window.clearInterval(id)
  }, [])
  const fraction = Math.min(1, Math.max(0, progress.fraction))
  const elapsed = Math.max(0, Math.floor((now - startedAt) / 1000))
  // Early fractions are too noisy (key derivation, DB reads) to extrapolate from.
  const remaining = fraction >= 0.05 && elapsed >= 3 ? Math.round((elapsed * (1 - fraction)) / fraction) : null
  return (
    <div className="space-y-2" role="status" aria-live="polite">
      <div className="flex items-baseline justify-between">
        <span className="text-[15px] font-bold">{progress.phase}</span>
        <span className="num text-[15px] font-bold text-accent">{Math.round(fraction * 100)}%</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-surface2">
        <div className="h-full rounded-full bg-accent transition-[width] duration-300" style={{ width: `${fraction * 100}%` }} />
      </div>
      {progress.detail && <p className="text-[13px] text-fg2">{progress.detail}</p>}
      <p className="text-[12px] text-fg3">
        경과 {fmtSec(elapsed)} · {remaining === null ? '남은 시간 계산 중' : `남은 시간 약 ${fmtSec(remaining)}`}
        <br />작업이 끝날 때까지 앱을 닫지 마세요.
      </p>
    </div>
  )
}

export function fmtBytes(bytes: number) {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${Math.floor(bytes / 1024)} KB`
}

/** Standard screen frame: large title, optional back button, scrolling body clear of the tab bar. */
export function Screen({ title, onBack, children, header }: { title: string; onBack?: () => void; children: ReactNode; header?: ReactNode }) {
  return (
    <div className="absolute inset-0 flex flex-col">
      <header className="safe-top shrink-0 px-5 pt-4 pb-1">
        <div className="flex items-center gap-1">
          {onBack && (
            <button onClick={onBack} aria-label="뒤로" className="tap -ml-2 grid size-10 place-items-center rounded-xl text-fg2"><Icon name="chevronLeft" size={26} /></button>
          )}
          <h1 className="text-[28px] font-extrabold tracking-tight">{title}</h1>
        </div>
        {header}
      </header>
      <div className="scroll-y flex-1 px-4 pb-[calc(var(--tab-h)+var(--sab,0px)+32px)]">{children}</div>
    </div>
  )
}

export function GrantBadge() {
  return <span className="flex shrink-0 items-center gap-1 rounded-full bg-ok/12 px-3 py-1.5 text-[13px] font-bold text-ok"><Icon name="check" size={15} strokeWidth={2.6} />완료</span>
}

export function ActionPill({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return <button onClick={onClick} disabled={disabled} className="tap shrink-0 rounded-full bg-accentsoft px-4 py-2 text-[14px] font-bold text-accent disabled:opacity-35">{children}</button>
}
