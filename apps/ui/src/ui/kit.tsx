import { useEffect, useRef, useState, type ReactNode } from 'react'
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

export function Button({ children, onClick, variant = 'primary', disabled, className }: { children: ReactNode; onClick?: () => void; variant?: 'primary' | 'soft' | 'ghost'; disabled?: boolean; className?: string }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={cx(
        'tap rounded-2xl px-5 py-3.5 text-[16px] font-bold disabled:opacity-40',
        variant === 'primary' && 'bg-accent text-accentfg',
        variant === 'soft' && 'bg-accentsoft text-accent',
        variant === 'ghost' && 'text-fg2',
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
