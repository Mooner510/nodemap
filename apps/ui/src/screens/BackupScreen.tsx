import { bridge } from '../lib/bridge'
import { Icon } from '../ui/Icon'
import { Card, Divider, Row, SectionTitle } from '../ui/kit'

export function BackupScreen() {
  return (
    <div className="absolute inset-0 flex flex-col">
      <header className="safe-top shrink-0 px-5 pt-4 pb-1">
        <h1 className="text-[28px] font-extrabold tracking-tight">백업</h1>
      </header>
      <div className="scroll-y flex-1 px-4 pb-[calc(var(--tab-h)+var(--sab,0px)+32px)]">
        <div className="mt-4 flex items-start gap-3.5 rounded-3xl bg-accentsoft p-5">
          <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-accent text-accentfg"><Icon name="shield" size={22} /></span>
          <p className="text-[14px] leading-relaxed text-fg2">
            모든 백업은 <b className="text-fg">내가 정한 비밀번호</b>로 기기에서 먼저 암호화돼요. 비밀번호를 잃어버리면 복구할 수 없으니 꼭 기억해 두세요.
          </p>
        </div>
        <SectionTitle>백업 위치</SectionTitle>
        <Card>
          <Row icon="cloud" title="온라인 백업" desc="Google 계정으로 암호화된 백업을 보관하고 복원해요" onClick={() => bridge.openNative('cloudBackup')} trailing={<Icon name="chevronRight" size={18} className="text-fg3" />} />
          <Divider />
          <Row icon="phoneDevice" tint="#0ea5e9" title="기기 파일로 백업" desc="파일로 내보내거나 불러와요 (설정 화면 안)" onClick={() => bridge.openNative('advancedSettings')} trailing={<Icon name="chevronRight" size={18} className="text-fg3" />} />
        </Card>
        <p className="px-5 pt-5 text-[13px] leading-relaxed text-fg3">복원하면 현재 기기의 기록이 백업 내용으로 바뀌어요. 진행 상태는 네이티브 화면에서 확인할 수 있어요.</p>
      </div>
    </div>
  )
}
