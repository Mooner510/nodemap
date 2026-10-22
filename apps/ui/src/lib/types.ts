export type TrackingPreset = 'PRECISE' | 'BALANCED' | 'BATTERY'

export interface Settings {
  onboardingCompleted: boolean
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
  /** Grow the auto region with new tracks while the app is open on Wi-Fi. */
  autoOfflineMaps: boolean
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

export type RuleSource = 'PHONE_CALL' | 'SMS' | 'MMS' | 'NOTIFICATION' | 'ROUTINE'
export type AppMode = 'INCLUDE_SELECTED' | 'EXCLUDE_SELECTED'
export type ConditionJoin = 'ALL' | 'ANY'
export type ConditionField =
  | 'TEXT' | 'TITLE' | 'BODY' | 'PACKAGE_NAME' | 'APP_LABEL' | 'CONTACT_NAME'
  | 'PHONE_NUMBER' | 'ADDRESS' | 'DIRECTION' | 'DURATION_SECONDS' | 'SOURCE'
export type ConditionOperator =
  | 'CONTAINS' | 'NOT_CONTAINS' | 'EQUALS' | 'NOT_EQUALS' | 'REGEX' | 'NOT_REGEX' | 'GREATER_OR_EQUAL' | 'LESS_OR_EQUAL'

export interface RuleCondition {
  id: string
  field: ConditionField
  operator: ConditionOperator
  value: string
}

export interface PinRule {
  id: string
  name: string
  source: RuleSource
  system: boolean
  enabled: boolean
  hidden: boolean
  priority: number
  typeId: string
  titleOverride: string
  bodyTemplate: string
  appMode: AppMode
  packages: string[]
  join: ConditionJoin
  conditions: RuleCondition[]
  /** Pins this rule has produced; a rule that produced any can't be deleted or change its source. */
  usage: number
}

/** What the editor sends back; `id` empty = new rule. system/enabled/priority are decided natively. */
export type PinRuleDraft = Omit<PinRule, 'system' | 'enabled' | 'hidden' | 'priority' | 'usage'>

export interface EditablePinType extends PinType {
  system: boolean
  usage: number
}

export interface RuleEditorData {
  types: EditablePinType[]
  rules: PinRule[]
}

export interface InstalledApp {
  label: string
  packageName: string
}

export interface Permissions {
  location: boolean
  backgroundLocation: boolean
  /** SMS/MMS and call log. */
  sensitive: boolean
  notificationAccess: boolean
}

export type PermissionKind = 'location' | 'backgroundLocation' | 'sensitive' | 'notificationAccess' | 'batteryOptimization' | 'appSettings'

export interface BackupProgress {
  phase: string
  fraction: number
  detail: string | null
}

export interface CloudBackupMetadata {
  id: string
  createdAt: number
  expiresAt: number
  sizeBytes: number
}

export type OfflineDownload =
  | { state: 'idle' }
  | { state: 'downloading'; name: string; completed: number; required: number; bytes: number; auto?: boolean; lastTileAt?: number | null }
  | { state: 'complete'; name: string; bytes: number }
  | { state: 'failed'; message: string }

export interface OfflineRegion {
  id: number
  name: string
  completed: number
  required: number
  bytes: number
  /** The region that grows with recorded tracks. */
  auto?: boolean
}

export interface AutoSyncStatus {
  enabled: boolean
  foreground: boolean
  wifi: boolean
}

export interface DownloadLogEntry {
  id: number
  name: string
  auto: boolean
  startedAt: number
  /** Heartbeat: written every ~2 s while the download runs. */
  updatedAt: number
  lastTileAt: number | null
  endedAt: number | null
  tiles: number
  bytes: number
  remaining: number
  result: 'running' | 'complete' | 'stopped' | 'failed'
  message: string | null
}

export interface OfflineMaps {
  regions: OfflineRegion[]
  download: OfflineDownload
  auto: AutoSyncStatus
  /** Newest first. */
  log: DownloadLogEntry[]
}

export interface BridgeEvents {
  'data-changed': void
  'settings-changed': Settings
  'backup-progress': BackupProgress
  'offline-changed': OfflineDownload
  'offline-auto-changed': AutoSyncStatus
  /** The activity came back to the foreground (permissions may have changed in system settings). */
  resumed: void
}

export interface Bridge {
  readonly isNative: boolean
  getSettings(): Promise<Settings>
  updateSettings(patch: Partial<Settings>): Promise<Settings>
  completeOnboarding(): Promise<Settings>
  getTimeline(range: { start: number; end: number }): Promise<TimelineData>
  getPins(range: { start: number; end: number }): Promise<Pin[]>
  getPinTypes(): Promise<PinType[]>
  addManualPin(input: { text: string; typeId: string }): Promise<void>

  getRuleEditor(): Promise<RuleEditorData>
  saveRule(rule: PinRuleDraft): Promise<void>
  deleteRule(input: { id: string }): Promise<void>
  setRuleEnabled(input: { id: string; enabled: boolean }): Promise<void>
  setRuleHidden(input: { id: string; hidden: boolean }): Promise<void>
  moveRule(input: { id: string; delta: number }): Promise<void>
  savePinType(type: { id: string; name: string; color: string; icon: PinIconName }): Promise<void>
  deletePinType(input: { id: string }): Promise<void>
  getInstalledApps(): Promise<InstalledApp[]>

  getPermissions(): Promise<Permissions>
  requestPermission(input: { kind: PermissionKind }): Promise<Permissions>

  getOfflineMaps(): Promise<OfflineMaps>
  /** Downloads the map around every recorded track now. Wi-Fi only; rejects otherwise. */
  downloadAllOfflineMaps(): Promise<void>
  deleteOfflineMap(input: { id: number }): Promise<void>

  /** Opens the system "save as" picker; `cancelled` when the user backs out of it. */
  exportBackup(input: { password: string }): Promise<{ cancelled: boolean }>
  importBackup(input: { password: string }): Promise<{ cancelled: boolean }>
  cloudBackupConfigured(): Promise<boolean>
  cloudBackupMetadata(): Promise<CloudBackupMetadata | null>
  cloudBackup(input: { password: string; expectedId: string | null }): Promise<CloudBackupMetadata>
  cloudRestore(input: { password: string }): Promise<CloudBackupMetadata>
  cloudDelete(): Promise<void>

  /** Subscribes to a native push event. Returns an unsubscribe fn. */
  on<K extends keyof BridgeEvents>(name: K, cb: (payload: BridgeEvents[K]) => void): () => void
  /** Shorthand for on('data-changed'). */
  onDataChanged(cb: () => void): () => void
}
