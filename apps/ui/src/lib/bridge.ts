import type { Bridge, NativeScreen, Settings } from './types'
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
  const listeners = new Set<() => void>()

  window.__nm = {
    resolve(id, ok, json) {
      const p = pending.get(id)
      if (!p) return
      pending.delete(id)
      if (ok) p.ok(json ? JSON.parse(json) : undefined)
      else p.fail(new Error(json || '요청에 실패했습니다.'))
    },
    emit(name) {
      if (name === 'data-changed') listeners.forEach((l) => l())
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

  return {
    isNative: true,
    getSettings: () => call('getSettings'),
    updateSettings: (patch) => call('updateSettings', patch),
    getTimeline: (r) => call('getTimeline', r),
    getPins: (r) => call('getPins', r),
    getPinTypes: () => call('getPinTypes'),
    addManualPin: (i) => call('addManualPin', i),
    getRules: () => call('getRules'),
    setRuleEnabled: (i) => call('setRuleEnabled', i),
    openNative: (s: NativeScreen) => call('openNative', { screen: s }),
    onDataChanged(cb) {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
  }
}

export const bridge: Bridge = window.NodeMapHost ? createNativeBridge(window.NodeMapHost) : createMockBridge()

export type { Settings }
