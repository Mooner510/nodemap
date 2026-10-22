import type { Bridge, BridgeEvents, Settings } from './types'
import { createMockBridge } from './mock'

interface NativeHost {
  call(id: number, method: string, argsJson: string): void
}

declare global {
  interface Window {
    NodeMapHost?: NativeHost
    __nm?: {
      resolve(id: number, ok: boolean, json: string): void
      emit(name: string, json: string): void
      setInsets(top: number, bottom: number, left: number, right: number): void
    }
  }
}

function createNativeBridge(host: NativeHost): Bridge {
  let seq = 0
  const pending = new Map<number, { ok: (v: unknown) => void; fail: (e: Error) => void }>()
  const listeners = new Map<string, Set<(payload: never) => void>>()

  window.__nm = {
    resolve(id, ok, json) {
      const p = pending.get(id)
      if (!p) return
      pending.delete(id)
      if (ok) p.ok(json ? JSON.parse(json) : undefined)
      else p.fail(new Error(json || '요청에 실패했습니다.'))
    },
    emit(name, json) {
      const set = listeners.get(name)
      if (!set?.size) return
      const payload = json ? JSON.parse(json) : undefined
      set.forEach((l) => (l as (p: unknown) => void)(payload))
    },
    setInsets(top, bottom, left, right) {
      const s = document.documentElement.style
      s.setProperty('--sat', `${top}px`)
      s.setProperty('--sab', `${bottom}px`)
      s.setProperty('--sal', `${left}px`)
      s.setProperty('--sar', `${right}px`)
    },
  }

  const call = <T>(method: string, args?: unknown) =>
    new Promise<T>((ok, fail) => {
      const id = ++seq
      pending.set(id, { ok: ok as (v: unknown) => void, fail })
      host.call(id, method, args === undefined ? '' : JSON.stringify(args))
    })

  const on: Bridge['on'] = (name, cb) => {
    let set = listeners.get(name)
    if (!set) listeners.set(name, (set = new Set()))
    set.add(cb as (payload: never) => void)
    return () => { set.delete(cb as (payload: never) => void) }
  }

  // Each method forwards to the native method of the same name.
  const methods = [
    'getSettings', 'updateSettings', 'completeOnboarding', 'getTimeline', 'getPins', 'getPinTypes', 'addManualPin',
    'getRuleEditor', 'saveRule', 'deleteRule', 'setRuleEnabled', 'setRuleHidden', 'moveRule', 'savePinType', 'deletePinType', 'getInstalledApps',
    'getPermissions', 'requestPermission', 'getOfflineMaps', 'downloadAllOfflineMaps', 'deleteOfflineMap',
    'exportBackup', 'importBackup', 'cloudBackupConfigured', 'cloudBackupMetadata', 'cloudBackup', 'cloudRestore', 'cloudDelete',
  ] as const satisfies readonly Exclude<keyof Bridge, 'isNative' | 'on' | 'onDataChanged'>[]
  const forward = Object.fromEntries(methods.map((m) => [m, (args?: unknown) => call(m, args)]))

  return {
    ...(forward as unknown as Omit<Bridge, 'isNative' | 'on' | 'onDataChanged'>),
    isNative: true,
    on,
    onDataChanged: (cb) => on('data-changed', cb),
  }
}

export const bridge: Bridge = window.NodeMapHost ? createNativeBridge(window.NodeMapHost) : createMockBridge()

export type { BridgeEvents, Settings }
