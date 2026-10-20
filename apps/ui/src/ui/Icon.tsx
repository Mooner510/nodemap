import type { SVGProps } from 'react'

const PATHS = {
  map: 'M9 4 3 6.5v13L9 17m0-13 6 2.5m-6-2.5V17m6-10.5L21 4v13l-6 2.5m0-13v13M9 17l6 2.5',
  pin: 'M12 21s7-6.2 7-11.5A7 7 0 0 0 5 9.5C5 14.8 12 21 12 21Zm0-8.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  sliders: 'M4 6h9m4 0h3M4 12h3m4 0h9M4 18h11m4 0h1M15 4v4M9 10v4M17 16v4',
  cloud: 'M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 10a4 4 0 0 1 0 8H7Z',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-2.2.1-1.6-1.9-1a6.9 6.9 0 0 0-.6-1.4l.5-2.1-1.2-1.2-2.1.5a6.9 6.9 0 0 0-1.4-.6l-1-1.9h-1.6l-1 1.9c-.5.1-1 .3-1.4.6l-2.100-.5-1.200 1.200.5 2.100c-.3.4-.5.9-.6 1.400l-1.900 1v1.600l1.900 1c.1.500.3 1 .6 1.400l-.5 2.100 1.200 1.200 2.100-.5c.4.300.9.500 1.400.6l1 1.900h1.600l1-1.900c.5-.1 1-.3 1.400-.6l2.100.5 1.200-1.200-.5-2.100c.3-.4.500-.9.600-1.400l1.900-1Z',
  plus: 'M12 5v14M5 12h14',
  locate: 'M12 3v3m0 12v3M3 12h3m12 0h3m-9 4a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z',
  clock: 'M12 7v5l3 2m6-2a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  chevronDown: 'm6 9 6 6 6-6',
  chevronRight: 'm9 6 6 6-6 6',
  chevronLeft: 'm15 6-6 6 6 6',
  phone: 'M5 4h4l2 5-2.500 1.500a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z',
  message: 'M21 12a8 8 0 0 1-11.600 7.100L4 20l1-4.400A8 8 0 1 1 21 12Z',
  bell: 'M6 9a6 6 0 1 1 12 0c0 6 2 7 2 7H4s2-1 2-7Zm4 10a2 2 0 0 0 4 0',
  repeat: 'M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4m14-1v2a3 3 0 0 1-3 3H3',
  star: 'm12 3 2.700 5.600 6.100.8-4.500 4.200 1.100 6.100L12 16.700 6.600 19.700l1.100-6.100L3.200 9.400l6.100-.8L12 3Z',
  home: 'm3 11 9-8 9 8M5 10v10h5v-6h4v6h5V10',
  work: 'M4 8h16v11H4V8Zm5 0V5h6v3M4 13h16',
  x: 'M6 6l12 12M18 6 6 18',
  check: 'm5 12.500 4.500 4.500L19 7.500',
  calendar: 'M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Zm0 4h16M8 3v4m8-4v4',
  route: 'M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm12-10a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM8 17h7a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h7',
  shield: 'M12 3 5 6v5c0 4.500 3 8 7 10 4-2 7-5.500 7-10V6l-7-3Zm-3 9 2 2 4-4',
  battery: 'M3 9h14v6H3V9Zm14 2h3v2h-3',
  phoneDevice: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm3 15h2',
  download: 'M12 4v11m0 0-4-4m4 4 4-4M5 20h14',
  list: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
  eyeOff: 'M3 3l18 18M10.600 6.100A9 9 0 0 1 12 6c5 0 8 6 8 6a14 14 0 0 1-2.400 3M6.600 7.600A14 14 0 0 0 4 12s3 6 8 6a8 8 0 0 0 3.400-.8',
  lock: 'M6 11h12v9H6v-9Zm3 0V8a3 3 0 0 1 6 0v3',
  layers: 'm12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5',
  wifiOff: 'M3 3l18 18M8.500 8.500A10 10 0 0 0 2 11m5 3a6 6 0 0 1 4-1.700M12 18h.01',
  edit: 'M4 20h4L19 9l-4-4L4 16v4Zm9-13 4 4',
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, size = 22, strokeWidth = 1.9, ...rest }: { name: IconName; size?: number; strokeWidth?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden {...rest}>
      <path d={PATHS[name]} />
    </svg>
  )
}

export const PIN_ICON: Record<string, IconName> = {
  PIN: 'pin', PHONE: 'phone', MESSAGE: 'message', NOTIFICATION: 'bell', ROUTINE: 'repeat',
  STAR: 'star', PLACE: 'pin', HOME: 'home', WORK: 'work',
}
