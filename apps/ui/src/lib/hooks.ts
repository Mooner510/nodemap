import { useCallback, useEffect, useRef, useState } from 'react'
import { bridge } from './bridge'
import type { Settings } from './types'

export function useSettings() {
  const [settings, setSettings] = useState<Settings | null>(null)
  useEffect(() => {
    let alive = true
    bridge.getSettings().then((s) => alive && setSettings(s))
    return () => { alive = false }
  }, [])
  const update = useCallback(async (patch: Partial<Settings>) => {
    // Optimistic: the native side is the source of truth and replies with the normalized value.
    setSettings((s) => (s ? { ...s, ...patch } : s))
    try {
      setSettings(await bridge.updateSettings(patch))
    } catch {
      setSettings(await bridge.getSettings())
    }
  }, [])
  return { settings, update }
}

/** Runs `fn` on mount and whenever native data changes (throttled), keeping the latest result. */
export function useLiveData<T>(fn: () => Promise<T>, deps: unknown[], throttleMs = 1000) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const fnRef = useRef(fn)
  fnRef.current = fn
  const seq = useRef(0)

  const reload = useCallback(() => {
    const mine = ++seq.current
    fnRef.current()
      .then((d) => mine === seq.current && (setData(d), setError(null)))
      .catch((e: Error) => mine === seq.current && setError(e.message))
  }, [])

  useEffect(() => {
    reload()
    let last = 0
    let t: number | undefined
    const off = bridge.onDataChanged(() => {
      const wait = Math.max(0, last + throttleMs - Date.now())
      window.clearTimeout(t)
      t = window.setTimeout(() => { last = Date.now(); reload() }, wait)
    })
    return () => { off(); window.clearTimeout(t); seq.current++ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return { data, error, reload }
}

export function useNow(intervalMs = 30_000) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(id)
  }, [intervalMs])
  return now
}
