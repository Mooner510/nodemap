import { useEffect, useState } from 'react'
import { bridge } from '../lib/bridge'
import type { BackupProgress, CloudBackupMetadata } from '../lib/types'
import { Icon } from '../ui/Icon'
import { Button, Card, ConfirmSheet, Divider, PasswordSheet, ProgressPanel, Screen, SectionTitle, Spinner, fmtBytes, useToast } from '../ui/kit'

type Job = 'cloudBackup' | 'cloudRestore' | 'fileExport' | 'fileImport'

const JOB_TITLE: Record<Job, string> = {
  cloudBackup: '온라인 백업',
  cloudRestore: '온라인 백업 복원',
  fileExport: '파일로 백업',
  fileImport: '파일에서 복원',
}

const fmtKst = (t: number) =>
  new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(t)

export function BackupScreen() {
  const toast = useToast()
  const [configured, setConfigured] = useState<boolean | null>(null)
  // undefined = not checked yet (needs a Google sign-in), null = checked, nothing stored.
  const [remote, setRemote] = useState<CloudBackupMetadata | null | undefined>(undefined)
  const [checking, setChecking] = useState(false)
  const [running, setRunning] = useState<{ job: Job; startedAt: number; progress: BackupProgress } | null>(null)
  const [ask, setAsk] = useState<Job | null>(null)
  const [confirm, setConfirm] = useState<'replace' | 'delete' | 'restoreFile' | null>(null)
  const [expectedId, setExpectedId] = useState<string | null>(null)
  const busy = checking || running !== null

  useEffect(() => {
    bridge.cloudBackupConfigured().then(setConfigured).catch(() => setConfigured(false))
    return bridge.on('backup-progress', (progress) => setRunning((r) => (r ? { ...r, progress } : r)))
  }, [])

  const fail = (e: unknown, fallback: string) => toast.show(e instanceof Error && e.message ? e.message : fallback)

  /** Signs in to Google if needed and reads what's stored now. */
  const fetchRemote = async (fallback: string) => {
    setChecking(true)
    try {
      const m = await bridge.cloudBackupMetadata()
      setRemote(m)
      return { ok: true as const, m }
    } catch (e) {
      fail(e, fallback)
      return { ok: false as const }
    } finally {
      setChecking(false)
    }
  }

  const refresh = async () => {
    const r = await fetchRemote('백업 정보를 불러오지 못했어요.')
    if (r.ok && !r.m) toast.show('보관 중인 온라인 백업이 없어요.')
  }

  const prepareBackup = async () => {
    const r = await fetchRemote('현재 백업 상태를 확인하지 못했어요.')
    if (!r.ok) return
    // The server only replaces the backup we saw, so a concurrent upload from another device is never clobbered.
    setExpectedId(r.m?.id ?? null)
    if (r.m) setConfirm('replace')
    else setAsk('cloudBackup')
  }

  const prepareRestore = async () => {
    const r = await fetchRemote('현재 백업 상태를 확인하지 못했어요.')
    if (!r.ok) return
    if (!r.m) toast.show('복원할 온라인 백업이 없어요.')
    else setAsk('cloudRestore')
  }

  const run = async (job: Job, password: string) => {
    setRunning({ job, startedAt: Date.now(), progress: { phase: '준비 중', fraction: 0, detail: null } })
    try {
      switch (job) {
        case 'cloudBackup':
          setRemote(await bridge.cloudBackup({ password, expectedId }))
          toast.show('온라인 백업을 완료했어요.')
          break
        case 'cloudRestore':
          setRemote(await bridge.cloudRestore({ password }))
          toast.show('복원했어요. 시스템 권한은 다시 확인해 주세요.')
          break
        case 'fileExport':
          if (!(await bridge.exportBackup({ password })).cancelled) toast.show('암호화 백업 파일을 저장했어요.')
          break
        case 'fileImport':
          if (!(await bridge.importBackup({ password })).cancelled) toast.show('백업 파일을 복원했어요.')
          break
      }
    } catch (e) {
      fail(e, job === 'cloudRestore' ? '복원에 실패했어요. 비밀번호와 네트워크를 확인하세요.' : `${JOB_TITLE[job]}에 실패했어요.`)
    } finally {
      setRunning(null)
      setExpectedId(null)
    }
  }

  const removeRemote = async () => {
    setChecking(true)
    try {
      await bridge.cloudDelete()
      setRemote(null)
      toast.show('온라인 백업을 삭제했어요.')
    } catch (e) {
      fail(e, '온라인 백업 삭제에 실패했어요.')
    } finally {
      setChecking(false)
    }
  }

  return (
    <Screen title="백업">
      <div className="mt-4 flex items-start gap-3.5 rounded-3xl bg-accentsoft p-5">
        <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-accent text-accentfg"><Icon name="shield" size={22} /></span>
        <p className="text-[14px] leading-relaxed text-fg2">
          모든 백업은 <b className="text-fg">내가 정한 비밀번호</b>로 기기에서 먼저 암호화돼요(AES-256-GCM). 비밀번호는 어디에도 저장되지 않으니 꼭 기억해 두세요.
        </p>
      </div>

      {running && (
        <Card className="mt-4 p-5">
          <div className="mb-3 flex items-center gap-2 text-[13px] font-semibold text-fg3"><Spinner size={16} />{JOB_TITLE[running.job]}</div>
          <ProgressPanel progress={running.progress} startedAt={running.startedAt} />
        </Card>
      )}

      <SectionTitle>온라인 백업 · 14일 보관</SectionTitle>
      <Card>
        {configured === false ? (
          <p className="px-5 py-4 text-[14px] leading-relaxed text-danger">이 빌드에는 온라인 백업 설정이 없어요. 서버 URL과 Google Server Client ID를 빌드 환경에 설정해야 해요.</p>
        ) : (
          <>
            <div className="px-5 py-4">
              {remote ? (
                <div className="space-y-1">
                  <div className="text-[16px] font-bold">최근 백업 <span className="num">{fmtKst(remote.createdAt)}</span></div>
                  <div className="text-[14px] text-fg2">크기 {fmtBytes(remote.sizeBytes)}</div>
                  <div className="text-[14px] text-fg2">자동 삭제 <span className="num">{fmtKst(remote.expiresAt)}</span> KST</div>
                </div>
              ) : (
                <p className="text-[14px] leading-relaxed text-fg2">
                  {remote === null ? '보관 중인 백업이 없어요.' : 'Google 계정으로 확인하면 현재 백업 상태를 볼 수 있어요.'}
                </p>
              )}
              <p className="mt-2 text-[13px] leading-relaxed text-fg3">직접 백업할 때만 업로드해요. Google 계정마다 1개를 보관하고, 백업일로부터 약 14일 뒤(15일째 0시 KST)에 자동으로 삭제돼요.</p>
            </div>
            <Divider />
            <div className="space-y-2 p-4">
              <Button variant="soft" disabled={busy || configured === null} onClick={refresh} className="flex w-full items-center justify-center gap-2">
                {checking ? <Spinner size={18} /> : <Icon name="refresh" size={18} />}Google 계정으로 상태 확인
              </Button>
              <div className="flex gap-2">
                <Button disabled={busy || configured === null} onClick={prepareBackup} className="flex flex-1 items-center justify-center gap-2"><Icon name="upload" size={18} />{remote ? '교체' : '백업'}</Button>
                <Button variant="soft" disabled={busy || configured === null} onClick={prepareRestore} className="flex flex-1 items-center justify-center gap-2"><Icon name="download" size={18} />복원</Button>
              </div>
              {remote && (
                <Button variant="ghost" disabled={busy} onClick={() => setConfirm('delete')} className="flex w-full items-center justify-center gap-2 text-danger"><Icon name="trash" size={18} />온라인 백업 삭제</Button>
              )}
            </div>
          </>
        )}
      </Card>

      <SectionTitle>기기 파일</SectionTitle>
      <Card>
        <p className="px-5 pt-4 text-[13px] leading-relaxed text-fg3">기록 전체를 암호화된 .nodemap 파일 하나로 저장하거나, 그 파일에서 복원해요.</p>
        <div className="flex gap-2 p-4">
          <Button disabled={busy} onClick={() => setAsk('fileExport')} className="flex flex-1 items-center justify-center gap-2"><Icon name="file" size={18} />파일로 백업</Button>
          <Button variant="soft" disabled={busy} onClick={() => setConfirm('restoreFile')} className="flex flex-1 items-center justify-center gap-2"><Icon name="download" size={18} />파일에서 복원</Button>
        </div>
      </Card>
      <p className="px-5 pt-5 text-[13px] leading-relaxed text-fg3">복원하면 지금 기기의 기록이 백업 내용으로 바뀌어요.</p>

      <PasswordSheet
        open={ask !== null}
        title={ask ? JOB_TITLE[ask] : ''}
        confirmLabel={ask === 'fileExport' ? '저장할 위치 선택' : ask === 'fileImport' ? '파일 선택' : ask === 'cloudRestore' ? '복원' : '백업'}
        onClose={() => setAsk(null)}
        onSubmit={(pw) => ask && run(ask, pw)}
      />
      <ConfirmSheet
        open={confirm === 'replace'}
        title="기존 온라인 백업 교체"
        message="지금 보관 중인 백업을 새 백업으로 바꿔요. 새 백업 업로드가 끝나기 전까지 기존 백업은 그대로 유지돼요."
        confirmLabel="교체"
        onClose={() => setConfirm(null)}
        onConfirm={() => setAsk('cloudBackup')}
      />
      <ConfirmSheet
        open={confirm === 'delete'}
        title="온라인 백업 삭제"
        message="서버에 보관된 백업을 바로 삭제해요. 되돌릴 수 없어요."
        confirmLabel="삭제"
        danger
        onClose={() => setConfirm(null)}
        onConfirm={removeRemote}
      />
      <ConfirmSheet
        open={confirm === 'restoreFile'}
        title="파일에서 복원"
        message="복원하면 지금 기기의 기록이 백업 파일 내용으로 바뀌어요. 계속할까요?"
        confirmLabel="계속"
        onClose={() => setConfirm(null)}
        onConfirm={() => setAsk('fileImport')}
      />
      {toast.node}
    </Screen>
  )
}
