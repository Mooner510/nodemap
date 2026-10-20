import { useState } from 'react'
import { useSettings } from './lib/hooks'
import { Icon, type IconName } from './ui/Icon'
import { Spinner, cx } from './ui/kit'
import { BackupScreen } from './screens/BackupScreen'
import { PinsScreen } from './screens/PinsScreen'
import { SettingsScreen } from './screens/SettingsScreen'
import { TimelineScreen } from './screens/TimelineScreen'

const TABS: { id: string; label: string; icon: IconName }[] = [
  { id: 'timeline', label: '타임라인', icon: 'map' },
  { id: 'pins', label: '압정', icon: 'pin' },
  { id: 'backup', label: '백업', icon: 'cloud' },
  { id: 'settings', label: '설정', icon: 'settings' },
]

export function App() {
  const { settings, update } = useSettings()
  const [tab, setTab] = useState('timeline')
  if (!settings) return <div className="grid h-full place-items-center"><Spinner size={32} /></div>

  return (
    <div className="relative h-full overflow-hidden">
      {/* Timeline stays mounted so the map keeps its camera and loaded tiles when switching tabs. */}
      <div className={cx('absolute inset-0', tab !== 'timeline' && 'invisible')}><TimelineScreen settings={settings} /></div>
      {tab === 'pins' && <div className="absolute inset-0 bg-bg"><PinsScreen /></div>}
      {tab === 'backup' && <div className="absolute inset-0 bg-bg"><BackupScreen /></div>}
      {tab === 'settings' && <div className="absolute inset-0 bg-bg"><SettingsScreen settings={settings} update={update} /></div>}

      <nav className="glass absolute inset-x-3 z-40 flex rounded-[26px] p-1.5" style={{ bottom: 'calc(var(--sab, 0px) + 10px)', height: 'var(--tab-h)' }} aria-label="주 메뉴">
        {TABS.map((t) => {
          const active = t.id === tab
          return (
            <button key={t.id} onClick={() => setTab(t.id)} aria-current={active ? 'page' : undefined} className={cx('tap flex flex-1 flex-col items-center justify-center gap-0.5 rounded-[20px] transition-colors', active ? 'bg-accentsoft text-accent' : 'text-fg3')}>
              <Icon name={t.icon} size={23} strokeWidth={active ? 2.3 : 1.9} />
              <span className="text-[11px] font-bold">{t.label}</span>
            </button>
          )
        })}
      </nav>
    </div>
  )
}
