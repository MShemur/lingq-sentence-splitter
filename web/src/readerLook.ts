// Reader look: page colours, colour of chosen words, keeping the screen on. Port of Android ReaderLook.kt.
import { signal } from '@preact/signals'
import { useEffect } from 'preact/hooks'
import type { JSX } from 'preact'
import { prefs } from './settings'
import { onTouch } from './stats'

export interface ReaderTheme { key: string; name: string; bg: string; fg: string; dark: boolean }

export const READER_THEMES: ReaderTheme[] = [
  { key: 'white', name: 'Белая', bg: '#FFFFFF', fg: '#1C1B1F', dark: false },
  { key: 'cream', name: 'Кремовая', bg: '#FBF7EE', fg: '#2D2A26', dark: false },
  { key: 'sepia', name: 'Сепия', bg: '#F4ECD8', fg: '#5B4636', dark: false },
  { key: 'mint', name: 'Мятная', bg: '#CCE8CF', fg: '#233626', dark: false },
  { key: 'gray', name: 'Серая', bg: '#E6E6E6', fg: '#222222', dark: false },
  { key: 'dusk', name: 'Сумерки', bg: '#3A3A3C', fg: '#E3E3E3', dark: true },
  { key: 'dark', name: 'Тёмная', bg: '#1E1E1E', fg: '#DADADA', dark: true },
  { key: 'black', name: 'Чёрная', bg: '#000000', fg: '#C8C8C8', dark: true },
]

/** Colour of chosen words (background) and of the translations above them (text); light and dark pages. */
export interface Highlight { key: string; name: string; bgLight: string; textLight: string; bgDark: string; textDark: string }

export const HIGHLIGHTS: Highlight[] = [
  { key: 'green', name: 'Зелёный', bgLight: 'rgba(67,160,71,0.2)', textLight: '#2E7D32', bgDark: 'rgba(67,160,71,0.33)', textDark: '#81C784' },
  { key: 'yellow', name: 'Жёлтый (LingQ)', bgLight: 'rgba(255,213,79,0.6)', textLight: '#8D6200', bgDark: 'rgba(255,193,7,0.4)', textDark: '#FFD54F' },
  { key: 'blue', name: 'Голубой', bgLight: 'rgba(66,165,245,0.3)', textLight: '#1565C0', bgDark: 'rgba(66,165,245,0.4)', textDark: '#90CAF9' },
  { key: 'pink', name: 'Розовый', bgLight: 'rgba(240,98,146,0.3)', textLight: '#C2185B', bgDark: 'rgba(240,98,146,0.4)', textDark: '#F48FB1' },
  { key: 'purple', name: 'Сиреневый', bgLight: 'rgba(171,71,188,0.3)', textLight: '#7B1FA2', bgDark: 'rgba(171,71,188,0.4)', textDark: '#CE93D8' },
  { key: 'orange', name: 'Оранжевый', bgLight: 'rgba(255,138,101,0.3)', textLight: '#D84315', bgDark: 'rgba(255,138,101,0.4)', textDark: '#FFAB91' },
]

export const KEEP_AWAKE: [string, string][] = [
  ['system', 'Как в системе'],
  ['5', '5 минут после касания'],
  ['10', '10 минут после касания'],
  ['15', '15 минут после касания'],
  ['30', '30 минут после касания'],
  ['always', 'Пока открыт урок'],
]

const darkQuery = window.matchMedia('(prefers-color-scheme: dark)')
export const systemDark = signal(darkQuery.matches)
darkQuery.addEventListener?.('change', (e) => { systemDark.value = e.matches })

export const themeOf = (key: string) => READER_THEMES.find((t) => t.key === key)
export const highlightOf = (key: string) => HIGHLIGHTS.find((h) => h.key === key) ?? HIGHLIGHTS[0]

/** Is the reader page dark (a dark page colour, or the system's dark mode with "Как в системе")? */
export const darkPage = () => themeOf(prefs.readerTheme.value)?.dark ?? systemDark.value

export function highlightColors(key: string, dark: boolean) {
  const h = highlightOf(key)
  return dark ? { bg: h.bgDark, text: h.textDark } : { bg: h.bgLight, text: h.textLight }
}

// accent colours of the app's light and dark schemes: a dark page in a light system gets the dark ones
const ACCENT_LIGHT = {
  '--primary': '#6750a4', '--on-primary': '#ffffff', '--primary-container': '#eaddff', '--on-primary-container': '#21005d',
  '--secondary-container': '#e8def8', '--on-secondary-container': '#1d192b', '--error': '#b3261e',
  '--error-container': '#f9dedc', '--on-error-container': '#410e0b', 'color-scheme': 'light',
}
const ACCENT_DARK = {
  '--primary': '#d0bcff', '--on-primary': '#381e72', '--primary-container': '#4f378b', '--on-primary-container': '#eaddff',
  '--secondary-container': '#4a4458', '--on-secondary-container': '#e8def8', '--error': '#f2b8b5',
  '--error-container': '#8c1d18', '--on-error-container': '#f9dedc', 'color-scheme': 'dark',
}

/** CSS variables for the reader screen: page colours (surfaces mixed from them) and the highlight colour. */
export function readerStyle(): JSX.CSSProperties {
  const t = themeOf(prefs.readerTheme.value)
  const hl = highlightColors(prefs.highlight.value, darkPage())
  const out: Record<string, string> = { '--green': hl.text, '--green-bg': hl.bg, '--pin-gap': (Number(prefs.pinGap.value) || 0) + 'px' }
  if (t) {
    const mix = (f: number) => `color-mix(in srgb, ${t.fg} ${f * 100}%, ${t.bg})`
    Object.assign(out, t.dark ? ACCENT_DARK : ACCENT_LIGHT, {
      '--surface': t.bg, '--on-surface': t.fg, '--surface-c': mix(0.06), '--surface-ch': mix(0.09), '--surface-chh': mix(0.12),
      '--surface-variant': mix(0.10), '--on-surface-variant': mix(0.72), '--outline': mix(0.45), '--outline-variant': mix(0.18),
      background: t.bg, color: t.fg,
    })
  }
  return out as JSX.CSSProperties
}

/** While the reader is open (and in front): the screen stays on for the chosen time after the last touch. */
export function useKeepAwake(active: boolean) {
  const mode = prefs.keepAwake.value
  useEffect(() => {
    const wl = (navigator as any).wakeLock
    const min = Number(mode)
    if (!active || !wl || (mode !== 'always' && !min)) return
    let lock: any = null
    let timer = 0
    let alive = true
    const release = () => { const l = lock; lock = null; l?.release?.().catch(() => {}) }
    const acquire = async () => {
      if (lock || document.visibilityState !== 'visible') return
      try {
        const l = await wl.request('screen')
        if (alive && !lock) {
          lock = l
          l.addEventListener?.('release', () => { if (lock === l) lock = null })
        } else l.release().catch(() => {})
      } catch { /* not allowed (battery saver ...) */ }
    }
    const touched = () => {
      void acquire()
      if (min) { clearTimeout(timer); timer = window.setTimeout(release, min * 60_000) }
    }
    // the lock is dropped by the browser when the page is hidden: take it again on return
    const vis = () => { if (document.visibilityState === 'visible') touched() }
    touched()
    const off = onTouch(touched)
    document.addEventListener('visibilitychange', vis)
    return () => { alive = false; off(); document.removeEventListener('visibilitychange', vis); clearTimeout(timer); release() }
  }, [active, mode])
}
