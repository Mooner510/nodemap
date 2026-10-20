export type TrackingPreset = 'PRECISE' | 'BALANCED' | 'BATTERY'

export interface Settings {
  trackingEnabled: boolean
  trackingPreset: TrackingPreset
  reverseGeocodingEnabled: boolean
  biometricLockEnabled: boolean
  autoStartAfterBoot: boolean
  mapStyleUri: string
  dialTickMinutes: number
  dialRadiusMinutes: number
  detailWindowMinutes: number
  totalWindowMinutes: number
  cameraWindowMinutes: number
}

/** [timestampMs, latitude, longitude, accuracyMeters] — compact because a day holds thousands of them. */
export type TrackPoint = readonly [t: number, lat: number, lng: number, acc: number]

export type PinIconName =
  | 'PIN' | 'PHONE' | 'MESSAGE' | 'NOTIFICATION' | 'ROUTINE' | 'STAR' | 'PLACE' | 'HOME' | 'WORK'

export interface PinType {
  id: string
  name: string
  color: string
  icon: PinIconName
}

export interface Pin {
  id: string
  t: number
  eventType: 'PIN_MANUAL' | 'PIN_ROUTINE' | 'PHONE_CALL' | 'SMS' | 'MMS' | 'NOTIFICATION' | 'SYSTEM'
  title: string
  body: string | null
  lat: number | null
  lng: number | null
  source: string | null
  typeId: string
  typeName: string
  color: string
  icon: PinIconName
}

export interface TimelineData {
  points: TrackPoint[]
  pins: Pin[]
  pinTypes: PinType[]
  /** The most recent recorded point, regardless of the requested window. */
  latest: TrackPoint | null
  /** Local dates (YYYY-MM-DD) that hold any data, ascending. */
  days: string[]
}

export interface RuleSummary {
  id: string
  name: string
  source: 'PHONE_CALL' | 'SMS' | 'MMS' | 'NOTIFICATION' | 'ROUTINE'
  system: boolean
  enabled: boolean
  hidden: boolean
  priority: number
  typeId: string
  typeName: string
  color: string
  conditionCount: number
  packageCount: number
}

export type NativeScreen = 'rules' | 'cloudBackup' | 'advancedSettings'

export interface Bridge {
  readonly isNative: boolean
  getSettings(): Promise<Settings>
  updateSettings(patch: Partial<Settings>): Promise<Settings>
  getTimeline(range: { start: number; end: number }): Promise<TimelineData>
  getPins(range: { start: number; end: number }): Promise<Pin[]>
  getPinTypes(): Promise<PinType[]>
  addManualPin(input: { text: string; typeId: string }): Promise<void>
  getRules(): Promise<RuleSummary[]>
  setRuleEnabled(input: { id: string; enabled: boolean }): Promise<void>
  openNative(screen: NativeScreen): Promise<void>
  /** Subscribes to "data changed" notifications (new track points / events). Returns an unsubscribe fn. */
  onDataChanged(cb: () => void): () => void
}
