import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { bridge } from '../lib/bridge'
import { useLiveData } from '../lib/hooks'
import type {
  AppMode, ConditionField, ConditionJoin, ConditionOperator, EditablePinType, InstalledApp, PinIconName, PinRule, PinRuleDraft, RuleCondition, RuleSource,
} from '../lib/types'
import { Icon, PIN_ICON, type IconName } from '../ui/Icon'
import { Button, Card, Chip, ConfirmSheet, Divider, Empty, SectionTitle, Segmented, Sheet, Spinner, Switch, TextField, cx, useToast } from '../ui/kit'
import { PinBadge } from './parts'

const SOURCES: { value: RuleSource; label: string; icon: IconName }[] = [
  { value: 'PHONE_CALL', label: '통화', icon: 'phone' },
  { value: 'SMS', label: '문자', icon: 'message' },
  { value: 'MMS', label: '멀티미디어 문자', icon: 'message' },
  { value: 'NOTIFICATION', label: '앱 알림', icon: 'bell' },
  { value: 'ROUTINE', label: 'Galaxy 루틴', icon: 'repeat' },
]

const FIELDS: { value: ConditionField; label: string }[] = [
  { value: 'TEXT', label: '전체 텍스트' },
  { value: 'TITLE', label: '제목' },
  { value: 'BODY', label: '내용' },
  { value: 'PACKAGE_NAME', label: '앱 패키지' },
  { value: 'APP_LABEL', label: '앱 이름' },
  { value: 'CONTACT_NAME', label: '연락처 이름' },
  { value: 'PHONE_NUMBER', label: '전화번호' },
  { value: 'ADDRESS', label: '발신/수신 주소' },
  { value: 'DIRECTION', label: '수신/발신 방향' },
  { value: 'DURATION_SECONDS', label: '통화 시간(초)' },
  { value: 'SOURCE', label: '원본 소스' },
]

const OPERATORS: { value: ConditionOperator; label: string }[] = [
  { value: 'CONTAINS', label: '포함' },
  { value: 'NOT_CONTAINS', label: '포함하지 않음' },
  { value: 'EQUALS', label: '같음' },
  { value: 'NOT_EQUALS', label: '같지 않음' },
  { value: 'REGEX', label: '정규식 일치' },
  { value: 'NOT_REGEX', label: '정규식 불일치' },
  { value: 'GREATER_OR_EQUAL', label: '이상' },
  { value: 'LESS_OR_EQUAL', label: '이하' },
]

const ICONS: { value: PinIconName; label: string }[] = [
  { value: 'PIN', label: '압정' }, { value: 'PHONE', label: '통화' }, { value: 'MESSAGE', label: '메시지' },
  { value: 'NOTIFICATION', label: '알림' }, { value: 'ROUTINE', label: '루틴' }, { value: 'STAR', label: '별' },
  { value: 'PLACE', label: '장소' }, { value: 'HOME', label: '집' }, { value: 'WORK', label: '업무' },
]

const SWATCHES = ['#3182F6', '#22C55E', '#F59E0B', '#EF4444', '#8B5CF6', '#EC4899', '#14B8A6', '#64748B']
const HEX = /^#[0-9A-Fa-f]{6}$/

/** Mirrors the native check; the native side validates again on save. */
function conditionError(c: RuleCondition): string | null {
  if (!c.value.trim()) return '값을 입력하세요'
  if (c.operator === 'REGEX' || c.operator === 'NOT_REGEX') {
    try {
      new RegExp(c.value)
    } catch {
      return '올바른 정규식이 아니에요'
    }
  }
  if ((c.operator === 'GREATER_OR_EQUAL' || c.operator === 'LESS_OR_EQUAL') && !Number.isFinite(Number(c.value))) return '숫자를 입력하세요'
  return null
}

const errorMessage = (e: unknown, fallback: string) => (e instanceof Error && e.message ? e.message : fallback)

export function RulesEditor() {
  const toast = useToast()
  const { data, error, reload } = useLiveData(() => bridge.getRuleEditor(), [], 300)
  const [local, setLocal] = useState<Record<string, boolean>>({})
  const [editing, setEditing] = useState<{ rule: PinRule | null; key: number } | null>(null)
  const [typeEditing, setTypeEditing] = useState<{ type: EditablePinType | null; key: number } | null>(null)

  // A fresh server state supersedes optimistic switch positions.
  useEffect(() => setLocal({}), [data])

  const act = (p: Promise<unknown>, fallback: string) => p.catch((e) => toast.show(errorMessage(e, fallback))).finally(reload)

  if (!data && !error) return <div className="grid place-items-center py-20"><Spinner size={30} /></div>
  if (error || !data) return <Empty icon="x" title="불러오지 못했어요" desc={error ?? undefined} />

  const types = new Map(data.types.map((t) => [t.id, t]))
  const groups = SOURCES.map((s) => ({ ...s, rules: data.rules.filter((r) => r.source === s.value) })).filter((g) => g.rules.length > 0)

  return (
    <div className="px-4">
      <p className="px-1 pb-1 text-[14px] leading-relaxed text-fg3">전화·문자·알림이 들어오면 규칙에 맞는 것만 그 순간의 위치에 압정으로 남겨요. 같은 이벤트가 여러 규칙에 맞으면 위에 있는 규칙 하나만 써요.</p>

      {groups.map((g) => (
        <section key={g.value}>
          <SectionTitle>{g.label}</SectionTitle>
          <Card>
            {g.rules.map((r, i) => {
              const type = types.get(r.typeId)
              const enabled = local[r.id] ?? r.enabled
              return (
                <div key={r.id}>
                  {i > 0 && <Divider />}
                  <div className="flex items-center gap-3 py-3 pr-4 pl-4">
                    <button onClick={() => setEditing({ rule: r, key: Date.now() })} className="tap flex min-w-0 flex-1 items-center gap-3.5 text-left">
                      <PinBadge pin={{ color: type?.color ?? '#3182F6', icon: type?.icon ?? 'PIN' }} />
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center gap-1.5">
                          <span className={cx('truncate text-[16px] font-semibold', !enabled && 'text-fg3')}>{r.name}</span>
                          {r.system && <span className="shrink-0 rounded-md bg-accentsoft px-1.5 py-0.5 text-[11px] font-bold text-accent">기본</span>}
                          {r.hidden && <Icon name="eyeOff" size={14} className="shrink-0 text-fg3" aria-label="숨김" />}
                        </span>
                        <span className="mt-0.5 block truncate text-[13px] text-fg3">
                          {[type?.name ?? '타입 없음', `압정 ${r.usage}개`, r.conditions.length ? `조건 ${r.conditions.length}개` : null, r.source === 'NOTIFICATION' ? `앱 ${r.packages.length}개${r.appMode === 'EXCLUDE_SELECTED' ? ' 제외' : ''}` : null].filter(Boolean).join(' · ')}
                        </span>
                      </span>
                    </button>
                    {g.rules.length > 1 && (
                      <span className="flex shrink-0 flex-col">
                        <button aria-label={`${r.name} 우선순위 올리기`} disabled={i === 0} onClick={() => act(bridge.moveRule({ id: r.id, delta: -1 }), '순서를 바꾸지 못했어요.')} className="tap grid h-6 w-8 place-items-center rounded-md text-fg3 disabled:opacity-25"><Icon name="arrowUp" size={16} /></button>
                        <button aria-label={`${r.name} 우선순위 내리기`} disabled={i === g.rules.length - 1} onClick={() => act(bridge.moveRule({ id: r.id, delta: 1 }), '순서를 바꾸지 못했어요.')} className="tap grid h-6 w-8 place-items-center rounded-md text-fg3 disabled:opacity-25"><Icon name="arrowDown" size={16} /></button>
                      </span>
                    )}
                    <Switch
                      label={`${r.name} 사용`}
                      checked={enabled}
                      onChange={(v) => {
                        setLocal((l) => ({ ...l, [r.id]: v }))
                        act(bridge.setRuleEnabled({ id: r.id, enabled: v }), '바꾸지 못했어요.')
                      }}
                    />
                  </div>
                </div>
              )
            })}
          </Card>
        </section>
      ))}
      <Button variant="soft" className="mt-4 flex w-full items-center justify-center gap-2" onClick={() => setEditing({ rule: null, key: Date.now() })}>
        <Icon name="plus" size={18} />규칙 추가
      </Button>

      <SectionTitle>압정 타입</SectionTitle>
      <p className="px-1 pb-2 text-[13px] leading-relaxed text-fg3">규칙은 타입을 참조해요. 타입의 이름·색을 바꾸면 지난 압정에도 바로 반영돼요.</p>
      <Card>
        {data.types.map((t, i) => (
          <div key={t.id}>
            {i > 0 && <Divider />}
            <button onClick={() => setTypeEditing({ type: t, key: Date.now() })} className="tap flex w-full items-center gap-3.5 px-4 py-3 text-left active:bg-surface2">
              <PinBadge pin={t} size={36} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[16px] font-semibold">{t.name}</span>
                <span className="num block text-[13px] text-fg3">{t.color.toUpperCase()} · 사용 {t.usage}곳{t.system ? ' · 기본' : ''}</span>
              </span>
              <Icon name="edit" size={18} className="text-fg3" />
            </button>
          </div>
        ))}
      </Card>
      <Button variant="soft" className="mt-4 flex w-full items-center justify-center gap-2" onClick={() => setTypeEditing({ type: null, key: Date.now() })}>
        <Icon name="plus" size={18} />새 타입
      </Button>

      {editing && (
        <RuleSheet
          key={editing.key}
          rule={editing.rule}
          types={data.types}
          onClose={() => setEditing(null)}
          onAddType={() => setTypeEditing({ type: null, key: Date.now() })}
          onSave={(draft) => bridge.saveRule(draft).then(() => { setEditing(null); toast.show('규칙을 저장했어요.'); reload() })}
          onDelete={(id) => bridge.deleteRule({ id }).then(() => { setEditing(null); toast.show('규칙을 삭제했어요.'); reload() })}
          onHidden={(id, hidden) => act(bridge.setRuleHidden({ id, hidden }), '바꾸지 못했어요.')}
        />
      )}
      {typeEditing && (
        <TypeSheet
          key={typeEditing.key}
          type={typeEditing.type}
          onClose={() => setTypeEditing(null)}
          onSave={(t) => bridge.savePinType(t).then(() => { setTypeEditing(null); reload() })}
          onDelete={(id) => bridge.deletePinType({ id }).then(() => { setTypeEditing(null); reload() })}
        />
      )}
      {toast.node}
    </div>
  )
}

function Field({ label, children, note }: { label: string; children: ReactNode; note?: ReactNode }) {
  return (
    <div>
      <div className="mb-2 px-1 text-[13px] font-semibold text-fg2">{label}</div>
      {children}
      {note && <p className="mt-1.5 px-1 text-[12px] leading-snug text-fg3">{note}</p>}
    </div>
  )
}

function SaveBar({ busy, disabled, error, onSave, label = '저장' }: { busy: boolean; disabled: boolean; error: string | null; onSave: () => void; label?: string }) {
  return (
    <div className="pt-2">
      {error && <p className="pb-2 text-center text-[13px] font-semibold text-danger">{error}</p>}
      <Button className="flex w-full items-center justify-center" disabled={disabled || busy} onClick={onSave}>{busy ? <Spinner size={20} /> : label}</Button>
    </div>
  )
}

function RuleSheet({ rule, types, onClose, onAddType, onSave, onDelete, onHidden }: {
  rule: PinRule | null
  types: EditablePinType[]
  onClose: () => void
  onAddType: () => void
  onSave: (d: PinRuleDraft) => Promise<void>
  onDelete: (id: string) => Promise<void>
  onHidden: (id: string, hidden: boolean) => void
}) {
  const [name, setName] = useState(rule?.name ?? '')
  const [source, setSource] = useState<RuleSource>(rule?.source ?? 'NOTIFICATION')
  const [typeId, setTypeId] = useState(rule?.typeId ?? types.find((t) => t.id === 'system:type:general')?.id ?? types[0]?.id ?? '')
  const [titleOverride, setTitleOverride] = useState(rule?.titleOverride ?? '')
  const [bodyTemplate, setBodyTemplate] = useState(rule?.bodyTemplate ?? '')
  const [appMode, setAppMode] = useState<AppMode>(rule?.appMode ?? 'INCLUDE_SELECTED')
  const [packages, setPackages] = useState<string[]>(rule?.packages ?? [])
  const [join, setJoin] = useState<ConditionJoin>(rule?.join ?? 'ALL')
  const [conditions, setConditions] = useState<RuleCondition[]>(rule?.conditions ?? [])
  const [hidden, setHidden] = useState(rule?.hidden ?? false)
  const [pickingApps, setPickingApps] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const sourceLocked = !!rule && (rule.system || rule.usage > 0)
  const appsValid = source !== 'NOTIFICATION' || appMode === 'EXCLUDE_SELECTED' || packages.length > 0
  const conditionsValid = conditions.every((c) => !conditionError(c))
  const canDelete = !!rule && !rule.system && rule.usage === 0
  const valid = name.trim() !== '' && typeId !== '' && appsValid && conditionsValid

  const patchCondition = (id: string, patch: Partial<RuleCondition>) => setConditions((cs) => cs.map((c) => (c.id === id ? { ...c, ...patch } : c)))

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      await onSave({ id: rule?.id ?? '', name: name.trim(), source, typeId, titleOverride, bodyTemplate, appMode, packages, join, conditions })
    } catch (e) {
      setError(errorMessage(e, '저장하지 못했어요.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Sheet open onClose={onClose} title={rule ? '규칙 수정' : '규칙 추가'}>
        <div className="space-y-6 px-6 pt-2 pb-6">
          <TextField label="규칙 이름" value={name} onChange={setName} placeholder="예: 결제 알림" autoFocus={!rule} />

          <Field
            label="감지할 이벤트"
            note={sourceLocked ? (rule?.system ? '기본 규칙은 감지 이벤트를 바꿀 수 없어요.' : '이미 압정을 만든 규칙은 감지 이벤트를 바꿀 수 없어요.') : undefined}
          >
            <div className="flex flex-wrap gap-2">
              {SOURCES.map((s) => (
                <Chip key={s.value} active={source === s.value} disabled={sourceLocked && source !== s.value} onClick={() => !sourceLocked && setSource(s.value)}>
                  <Icon name={s.icon} size={15} />{s.label}
                </Chip>
              ))}
            </div>
          </Field>

          <Field label="압정 타입">
            <div className="flex flex-wrap gap-2">
              {types.map((t) => (
                <Chip key={t.id} active={typeId === t.id} color={t.color} onClick={() => setTypeId(t.id)}>
                  <Icon name={PIN_ICON[t.icon] ?? 'pin'} size={15} style={typeId === t.id ? undefined : { color: t.color }} />{t.name}
                </Chip>
              ))}
              <Chip active={false} onClick={onAddType}><Icon name="plus" size={15} />새 타입</Chip>
            </div>
          </Field>

          <TextField label="표시 제목" value={titleOverride} onChange={setTitleOverride} placeholder="비우면 원래 제목을 써요" />

          {source === 'ROUTINE' && (
            <TextField label="루틴 압정 내용" value={bodyTemplate} onChange={setBodyTemplate} rows={2} placeholder="루틴이 실행될 때 압정에 남길 내용" />
          )}

          {source === 'NOTIFICATION' && (
            <Field label="대상 앱">
              <Segmented<AppMode> value={appMode} onChange={setAppMode} options={[{ value: 'INCLUDE_SELECTED', label: '선택한 앱만' }, { value: 'EXCLUDE_SELECTED', label: '선택한 앱 제외' }]} />
              <button onClick={() => setPickingApps(true)} className={cx('tap mt-2 flex w-full items-center gap-3 rounded-2xl bg-surface2 px-4 py-3.5 text-left', !appsValid && 'ring-2 ring-danger/60')}>
                <Icon name="apps" size={20} className="text-accent" />
                <span className="flex-1 text-[15px] font-semibold">앱 {packages.length}개 선택됨</span>
                <Icon name="chevronRight" size={18} className="text-fg3" />
              </button>
              {!appsValid && <p className="mt-1.5 px-1 text-[12px] text-danger">‘선택한 앱만’은 앱을 하나 이상 골라야 해요.</p>}
            </Field>
          )}

          <Field label="조건" note={conditions.length === 0 ? '조건이 없으면 이 이벤트는 모두 압정으로 남아요.' : undefined}>
            {conditions.length > 1 && (
              <div className="mb-2">
                <Segmented<ConditionJoin> value={join} onChange={setJoin} options={[{ value: 'ALL', label: '모두 만족' }, { value: 'ANY', label: '하나라도 만족' }]} />
              </div>
            )}
            <div className="space-y-2">
              {conditions.map((c) => {
                const err = conditionError(c)
                return (
                  <div key={c.id} className="rounded-2xl bg-surface2 p-3">
                    <div className="flex gap-2">
                      <Select label="조건 항목" value={c.field} options={FIELDS} onChange={(field) => patchCondition(c.id, { field })} />
                      <Select label="비교 방식" value={c.operator} options={OPERATORS} onChange={(operator) => patchCondition(c.id, { operator })} />
                      <button aria-label="조건 삭제" onClick={() => setConditions((cs) => cs.filter((x) => x.id !== c.id))} className="tap grid size-11 shrink-0 place-items-center rounded-xl text-fg3"><Icon name="trash" size={18} /></button>
                    </div>
                    <input
                      value={c.value}
                      onChange={(e) => patchCondition(c.id, { value: e.target.value })}
                      placeholder="값"
                      aria-label="조건 값"
                      spellCheck={false}
                      className={cx('mt-2 w-full rounded-xl bg-surface px-3.5 py-3 text-[15px] outline-none focus:ring-2', err ? 'ring-2 ring-danger/60' : 'focus:ring-accent/50')}
                    />
                    {err && <p className="mt-1 px-1 text-[12px] text-danger">{err}</p>}
                  </div>
                )
              })}
            </div>
            <button onClick={() => setConditions((cs) => [...cs, { id: crypto.randomUUID?.() ?? `c-${Date.now()}`, field: 'TEXT', operator: 'CONTAINS', value: '' }])} className="tap mt-2 flex w-full items-center justify-center gap-1.5 rounded-2xl border border-dashed border-fg3/40 py-3 text-[14px] font-semibold text-fg2">
              <Icon name="plus" size={16} />조건 추가
            </button>
          </Field>

          {rule && !rule.enabled && (
            <div className="flex items-center gap-3 rounded-2xl bg-surface2 px-4 py-3">
              <span className="min-w-0 flex-1">
                <span className="block text-[15px] font-semibold">이 규칙의 압정 숨기기</span>
                <span className="block text-[12px] text-fg3">꺼진 규칙이 남긴 압정을 지도와 목록에서 숨겨요</span>
              </span>
              <Switch label="이 규칙의 압정 숨기기" checked={hidden} onChange={(v) => { setHidden(v); onHidden(rule.id, v) }} />
            </div>
          )}

          <SaveBar busy={busy} disabled={!valid} error={error} onSave={save} />

          {rule && (canDelete ? (
            <Button variant="ghost" className="flex w-full items-center justify-center gap-2 text-danger" onClick={() => setConfirmDelete(true)}><Icon name="trash" size={18} />규칙 삭제</Button>
          ) : (
            <p className="text-center text-[12px] leading-relaxed text-fg3">
              {rule.system ? '기본 규칙은 삭제할 수 없어요. 대신 끌 수 있어요.' : `압정을 ${rule.usage}개 만든 규칙은 삭제할 수 없어요. 끈 뒤 숨길 수 있어요.`}
            </p>
          ))}
        </div>
      </Sheet>
      {pickingApps && <AppPickerSheet initial={packages} onClose={() => setPickingApps(false)} onDone={(p) => { setPackages(p); setPickingApps(false) }} />}
      <ConfirmSheet
        open={confirmDelete}
        title="규칙 삭제"
        message={`‘${rule?.name ?? ''}’ 규칙을 삭제할까요?`}
        confirmLabel="삭제"
        danger
        onClose={() => setConfirmDelete(false)}
        onConfirm={() => rule && onDelete(rule.id).catch((e) => setError(errorMessage(e, '삭제하지 못했어요.')))}
      />
    </>
  )
}

function Select<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <span className="relative min-w-0 flex-1">
      <select
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
        className="h-11 w-full appearance-none truncate rounded-xl bg-surface pr-8 pl-3.5 text-[14px] font-semibold outline-none focus:ring-2 focus:ring-accent/50"
      >
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <Icon name="chevronDown" size={16} className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-fg3" />
    </span>
  )
}

function AppPickerSheet({ initial, onClose, onDone }: { initial: string[]; onClose: () => void; onDone: (packages: string[]) => void }) {
  const [apps, setApps] = useState<InstalledApp[] | null>(null)
  const [chosen, setChosen] = useState(() => new Set(initial))
  const [query, setQuery] = useState('')
  useEffect(() => {
    bridge.getInstalledApps().then(setApps).catch(() => setApps([]))
  }, [])
  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = apps ?? []
    // Chosen apps first so the current selection is visible without searching.
    const sorted = [...list].sort((a, b) => Number(chosen.has(b.packageName)) - Number(chosen.has(a.packageName)))
    return q ? sorted.filter((a) => a.label.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)) : sorted
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apps, query])
  const toggle = (pkg: string) => setChosen((s) => {
    const n = new Set(s)
    if (n.has(pkg)) n.delete(pkg)
    else n.add(pkg)
    return n
  })

  return (
    <Sheet open onClose={onClose} title="대상 앱">
      <div className="px-5 pb-2">
        <div className="flex items-center gap-2 rounded-2xl bg-surface2 px-4 py-3">
          <Icon name="search" size={18} className="text-fg3" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="앱 이름이나 패키지 검색" aria-label="앱 검색" className="min-w-0 flex-1 bg-transparent text-[15px] outline-none placeholder:text-fg3" />
          {query && <button aria-label="지우기" onClick={() => setQuery('')} className="text-fg3"><Icon name="x" size={16} /></button>}
        </div>
        <div className="mt-2 flex items-center justify-between px-1 text-[13px]">
          <span className="font-semibold text-fg2">{chosen.size}개 선택</span>
          <span className="flex gap-3 font-semibold text-accent">
            <button onClick={() => setChosen(new Set([...chosen, ...visible.map((a) => a.packageName)]))}>{query ? '검색 결과 모두 선택' : '모두 선택'}</button>
            <button onClick={() => setChosen(new Set())}>모두 해제</button>
          </span>
        </div>
      </div>
      {!apps ? (
        <div className="grid place-items-center py-16"><Spinner size={28} /></div>
      ) : (
        <ul className="px-2">
          {visible.map((a) => {
            const on = chosen.has(a.packageName)
            return (
              <li key={a.packageName}>
                <button onClick={() => toggle(a.packageName)} aria-pressed={on} className="tap flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left active:bg-surface2">
                  <span className={cx('grid size-6 shrink-0 place-items-center rounded-lg border-2', on ? 'border-accent bg-accent text-accentfg' : 'border-fg3/50')}>
                    {on && <Icon name="check" size={14} strokeWidth={3} />}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[15px] font-semibold">{a.label}</span>
                    <span className="block truncate text-[12px] text-fg3">{a.packageName}</span>
                  </span>
                </button>
              </li>
            )
          })}
          {visible.length === 0 && <li className="py-10 text-center text-[14px] text-fg3">일치하는 앱이 없어요</li>}
        </ul>
      )}
      <div className="sticky bottom-0 bg-surface px-5 pt-2 pb-4">
        <Button className="w-full" onClick={() => onDone([...chosen])}>완료</Button>
      </div>
    </Sheet>
  )
}

function TypeSheet({ type, onClose, onSave, onDelete }: {
  type: EditablePinType | null
  onClose: () => void
  onSave: (t: { id: string; name: string; color: string; icon: PinIconName }) => Promise<void>
  onDelete: (id: string) => Promise<void>
}) {
  const [name, setName] = useState(type?.name ?? '')
  const [color, setColor] = useState(type?.color ?? '#3182F6')
  const [icon, setIcon] = useState<PinIconName>(type?.icon ?? 'PIN')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const colorValid = HEX.test(color.trim())
  const canDelete = !!type && !type.system && type.usage === 0

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      await onSave({ id: type?.id ?? '', name: name.trim(), color: color.trim().toUpperCase(), icon })
    } catch (e) {
      setError(errorMessage(e, '저장하지 못했어요.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Sheet open onClose={onClose} title={type ? '압정 타입 수정' : '압정 타입 추가'}>
        <div className="space-y-6 px-6 pt-2 pb-6">
          <div className="flex items-center gap-4">
            <PinBadge pin={{ color: colorValid ? color : '#8b95a1', icon }} size={52} />
            <div className="min-w-0 flex-1"><TextField label="타입 이름" value={name} onChange={setName} placeholder="예: 맛집" autoFocus={!type} /></div>
          </div>

          <Field label="색상">
            <div className="flex flex-wrap items-center gap-2.5">
              {SWATCHES.map((c) => (
                <button key={c} aria-label={c} aria-pressed={color.toUpperCase() === c} onClick={() => setColor(c)} className={cx('tap size-9 rounded-full', color.toUpperCase() === c && 'ring-2 ring-fg ring-offset-2 ring-offset-surface')} style={{ background: c }} />
              ))}
              <label className="tap relative grid size-9 place-items-center overflow-hidden rounded-full bg-surface2 text-fg2" aria-label="직접 고르기">
                <Icon name="plus" size={18} />
                <input type="color" value={colorValid ? color : '#3182F6'} onChange={(e) => setColor(e.target.value.toUpperCase())} className="absolute inset-0 opacity-0" />
              </label>
            </div>
            <div className="mt-3"><TextField label="직접 입력 (#RRGGBB)" value={color} onChange={setColor} mono error={colorValid ? null : '#RRGGBB 형식으로 입력하세요'} /></div>
          </Field>

          <Field label="아이콘">
            <div className="grid grid-cols-3 gap-2">
              {ICONS.map((i) => (
                <button key={i.value} onClick={() => setIcon(i.value)} aria-pressed={icon === i.value} className={cx('tap flex flex-col items-center gap-1 rounded-2xl py-3 text-[13px] font-semibold', icon === i.value ? 'bg-accentsoft text-accent ring-2 ring-accent/50' : 'bg-surface2 text-fg2')}>
                  <Icon name={PIN_ICON[i.value] ?? 'pin'} size={22} />{i.label}
                </button>
              ))}
            </div>
          </Field>

          <SaveBar busy={busy} disabled={!name.trim() || !colorValid} error={error} onSave={save} />
          {type && (canDelete ? (
            <Button variant="ghost" className="flex w-full items-center justify-center gap-2 text-danger" onClick={() => setConfirmDelete(true)}><Icon name="trash" size={18} />타입 삭제</Button>
          ) : (
            <p className="text-center text-[12px] text-fg3">{type.system ? '기본 타입은 삭제할 수 없어요.' : `규칙이나 압정 ${type.usage}곳에서 쓰는 타입은 삭제할 수 없어요.`}</p>
          ))}
        </div>
      </Sheet>
      <ConfirmSheet
        open={confirmDelete}
        title="타입 삭제"
        message={`‘${type?.name ?? ''}’ 타입을 삭제할까요?`}
        confirmLabel="삭제"
        danger
        onClose={() => setConfirmDelete(false)}
        onConfirm={() => type && onDelete(type.id).catch((e) => setError(errorMessage(e, '삭제하지 못했어요.')))}
      />
    </>
  )
}
