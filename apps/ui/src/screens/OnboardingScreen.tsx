import { useState } from 'react'
import { bridge } from '../lib/bridge'
import { usePermissions } from '../lib/hooks'
import type { Settings } from '../lib/types'
import { Icon, type IconName } from '../ui/Icon'
import { ActionPill, Button, Card, Divider, GrantBadge, Spinner, cx } from '../ui/kit'

const PAGES = 4

export function OnboardingScreen({ onDone }: { onDone: (s: Settings) => void }) {
  const [page, setPage] = useState(0)
  const [finishing, setFinishing] = useState(false)
  const { perms, request } = usePermissions()
  const canContinue = page !== 1 || !!perms?.location

  const next = async () => {
    if (page < PAGES - 1) return setPage(page + 1)
    setFinishing(true)
    try {
      onDone(await bridge.completeOnboarding())
    } finally {
      setFinishing(false)
    }
  }

  return (
    <div className="absolute inset-0 flex flex-col bg-bg">
      <header className="safe-top flex shrink-0 items-center justify-between px-6 pt-5 pb-2">
        <span className="text-[18px] font-extrabold tracking-tight">NodeMap</span>
        <span className="num text-[14px] font-semibold text-fg3">{page + 1} / {PAGES}</span>
      </header>

      <div className="scroll-y flex-1 px-5 pt-6 pb-6">
        {page === 0 && (
          <>
            <Hero icon="pin" title={'하루의 이동을\n자동으로 남겨요'} desc="NodeMap은 위치와 압정을 기기 안에 저장해요. 서버 계정 없이, 필요한 기록만 내 휴대폰에 쌓여요." />
            <Card>
              <Feature icon="route" title="이동 경로" desc="시간에 따라 움직인 위치를 타임라인으로 확인" />
              <Divider />
              <Feature icon="message" title="자동 압정" desc="문자, 통화, 선택한 앱 알림을 규칙에 따라 기록" />
              <Divider />
              <Feature icon="lock" title="로컬 암호화" desc="민감한 내용과 첨부파일은 기기 키로 암호화" />
            </Card>
          </>
        )}
        {page === 1 && (
          <>
            <Hero icon="locate" title={'위치 기록을\n먼저 준비할게요'} desc="정밀 위치는 기본 기록에 필요해요. ‘항상 허용’까지 설정하면 재부팅 후 복구와 장시간 백그라운드 기록이 더 안정적이에요." />
            <Card>
              <Step title="정밀 위치" desc="현재 위치와 이동 경로 기록 · 필수" granted={perms?.location} action="허용" onClick={() => request('location')} />
              <Divider />
              <Step title="항상 위치 허용" desc="권한 › 위치에서 ‘항상 허용’ 선택" granted={perms?.backgroundLocation} disabled={!perms?.location} action="설정" onClick={() => request('backgroundLocation')} />
            </Card>
            <button onClick={() => request('appSettings')} className="tap mt-3 w-full py-3 text-[14px] font-semibold text-accent">앱 권한 설정 직접 열기</button>
          </>
        )}
        {page === 2 && (
          <>
            <Hero icon="bell" title={'자동 압정도\n원하면 켤 수 있어요'} desc="전화·문자와 선택한 앱의 알림을 위치와 함께 기록할 수 있어요. 이 권한들이 없어도 기본 위치 기록은 동작해요." />
            <Card>
              <Step title="문자 · 통화" desc="송수신 시각, 상대방, 통화 기록과 녹음 연결" granted={perms?.sensitive} action="허용" onClick={() => request('sensitive')} />
              <Divider />
              <Step title="알림 접근" desc="내가 만든 규칙에 맞는 알림만 압정으로" granted={perms?.notificationAccess} action="설정" onClick={() => request('notificationAccess')} />
            </Card>
            <p className="px-2 pt-4 text-[13px] leading-relaxed text-fg3">일부 통화·문자 권한은 Android 버전이나 설치 방식에 따라 허용되지 않을 수 있어요. 그 경우 해당 자동 압정만 꺼져요.</p>
          </>
        )}
        {page === 3 && (
          <>
            <Hero icon="checkCircle" title="준비가 끝났어요" desc="시작과 동시에 기록해요. 선택 권한은 나중에 설정 탭에서 언제든 추가할 수 있어요." />
            <Card>
              <Status label="위치 기록" on={perms?.location} />
              <Divider />
              <Status label="백그라운드 기록" on={perms?.backgroundLocation} />
              <Divider />
              <Status label="전화·문자 자동 압정" on={perms?.sensitive} />
              <Divider />
              <Status label="알림 자동 압정" on={perms?.notificationAccess} />
            </Card>
            <Button variant="soft" className="mt-3 w-full" onClick={() => request('batteryOptimization')}>배터리 최적화 예외 설정</Button>
          </>
        )}
      </div>

      <footer className="shrink-0 px-5 pt-2" style={{ paddingBottom: 'calc(var(--sab, 0px) + 16px)' }}>
        {!canContinue && <p className="pb-3 text-center text-[13px] font-semibold text-danger">기록을 시작하려면 위치 권한이 필요해요.</p>}
        <div className="flex items-center gap-2">
          <div className="flex flex-1 items-center gap-1.5" aria-hidden>
            {Array.from({ length: PAGES }, (_, i) => (
              <span key={i} className={cx('h-2 rounded-full transition-all', i === page ? 'w-6 bg-accent' : 'w-2 bg-fg3/35')} />
            ))}
          </div>
          {page > 0 && <Button variant="ghost" onClick={() => setPage(page - 1)}>이전</Button>}
          <Button disabled={!canContinue || finishing} onClick={next} className="flex items-center gap-2">
            {finishing ? <Spinner size={20} /> : <>{page === PAGES - 1 ? 'NodeMap 시작' : '다음'}<Icon name="arrowRight" size={20} /></>}
          </Button>
        </div>
      </footer>
    </div>
  )
}

function Hero({ icon, title, desc }: { icon: IconName; title: string; desc: string }) {
  return (
    <div className="px-1 pb-7">
      <span className="grid size-20 place-items-center rounded-[28px] bg-accentsoft text-accent"><Icon name={icon} size={40} /></span>
      <h1 className="mt-6 text-[28px] leading-tight font-extrabold tracking-tight whitespace-pre-line">{title}</h1>
      <p className="mt-3 text-[15px] leading-relaxed text-fg2">{desc}</p>
    </div>
  )
}

function Feature({ icon, title, desc }: { icon: IconName; title: string; desc: string }) {
  return (
    <div className="flex items-center gap-3.5 px-5 py-4">
      <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-accentsoft text-accent"><Icon name={icon} size={22} /></span>
      <span>
        <span className="block text-[16px] font-bold">{title}</span>
        <span className="mt-0.5 block text-[13px] text-fg3">{desc}</span>
      </span>
    </div>
  )
}

function Step({ title, desc, granted, disabled, action, onClick }: { title: string; desc: string; granted?: boolean; disabled?: boolean; action: string; onClick: () => void }) {
  return (
    <div className="flex items-center gap-3 px-5 py-4">
      <span className="min-w-0 flex-1">
        <span className="block text-[16px] font-bold">{title}</span>
        <span className="mt-0.5 block text-[13px] leading-snug text-fg3">{desc}</span>
      </span>
      {granted ? <GrantBadge /> : <ActionPill disabled={disabled} onClick={onClick}>{action}</ActionPill>}
    </div>
  )
}

function Status({ label, on }: { label: string; on?: boolean }) {
  return (
    <div className="flex items-center justify-between px-5 py-3.5">
      <span className="text-[15px] font-semibold">{label}</span>
      <span className={cx('text-[14px] font-bold', on ? 'text-accent' : 'text-fg3')}>{on ? '사용 가능' : '나중에 설정'}</span>
    </div>
  )
}
