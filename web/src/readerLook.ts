// Reader look: page colours, colour of chosen words, keeping the screen on. Port of Android ReaderLook.kt.
import { signal } from '@preact/signals'
import { useEffect } from 'preact/hooks'
import type { JSX } from 'preact'
import { prefs } from './settings'
import { onTouch } from './stats'

/** [grain]: strength 0..1 of the paper texture over the page colour (as Android v1.127). */
export interface ReaderTheme { key: string; name: string; bg: string; fg: string; dark: boolean; grain?: number }

export const READER_THEMES: ReaderTheme[] = [
  { key: 'white', name: 'Белая', bg: '#FFFFFF', fg: '#1C1B1F', dark: false },
  { key: 'cream', name: 'Кремовая', bg: '#FBF7EE', fg: '#2D2A26', dark: false },
  { key: 'sepia', name: 'Сепия', bg: '#F4ECD8', fg: '#5B4636', dark: false },
  { key: 'mint', name: 'Мятная', bg: '#CCE8CF', fg: '#233626', dark: false },
  { key: 'gray', name: 'Серая', bg: '#E6E6E6', fg: '#222222', dark: false },
  { key: 'dusk', name: 'Сумерки', bg: '#3A3A3C', fg: '#E3E3E3', dark: true },
  { key: 'dark', name: 'Тёмная', bg: '#1E1E1E', fg: '#DADADA', dark: true },
  { key: 'black', name: 'Чёрная', bg: '#000000', fg: '#C8C8C8', dark: true },
  // like FBReader's backgrounds (Android v1.127)
  { key: 'fbPaper', name: 'Бумага', bg: '#F3EBDA', fg: '#33291F', dark: false, grain: 0.6 },
  { key: 'parchment', name: 'Пергамент', bg: '#E8D8B4', fg: '#3D2F1C', dark: false, grain: 0.8 },
  { key: 'linen', name: 'Лён', bg: '#EFEBE3', fg: '#2B2824', dark: false, grain: 0.55 },
  { key: 'sky', name: 'Небесная', bg: '#DCE6EE', fg: '#1E2830', dark: false },
  { key: 'nightBlue', name: 'Ночная синяя', bg: '#18212C', fg: '#C5D0DC', dark: true },
  { key: 'coffee', name: 'Кофейная', bg: '#2B221C', fg: '#E0CFB6', dark: true, grain: 0.5 },
]

const grainCache = new Map<string, string>()
/**
 * Paper texture: a repeated tile of fine dark and light speckles plus short fibres (SVG noise), alpha up to ~18 % at
 * strength 1; '' for none.
 */
export function grainImage(g = 0): string {
  if (!(g > 0)) return ''
  const k = g.toFixed(2)
  let u = grainCache.get(k)
  if (!u) {
    const a = (0.18 * g).toFixed(3)
    // alpha = a * (4·noise − 2): only the top of the noise shows; R → dark speckles, G → light ones, fibres stretched
    const row = (ch: number, s: number) => {
      const v = [0, 0, 0, 0, 0]
      v[ch] = 4 * s
      return `${v.slice(0, 4).join(' ')} ${(-2 * s).toFixed(3)}`
    }
    const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='220' height='220'>`
      + `<filter id='d'><feTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' seed='3' stitchTiles='stitch'/>`
      + `<feColorMatrix values='0 0 0 0 0.16  0 0 0 0 0.12  0 0 0 0 0.08  ${row(0, Number(a))}'/></filter>`
      + `<filter id='l'><feTurbulence type='fractalNoise' baseFrequency='0.7' numOctaves='2' seed='9' stitchTiles='stitch'/>`
      + `<feColorMatrix values='0 0 0 0 1  0 0 0 0 1  0 0 0 0 0.97  ${row(1, Number(a) * 0.8)}'/></filter>`
      + `<filter id='f'><feTurbulence type='fractalNoise' baseFrequency='0.05 0.5' numOctaves='2' seed='5' stitchTiles='stitch'/>`
      + `<feColorMatrix values='0 0 0 0 0.2  0 0 0 0 0.15  0 0 0 0 0.1  ${row(0, Number(a) * 0.4)}'/></filter>`
      + `<rect width='100%' height='100%' filter='url(#d)'/><rect width='100%' height='100%' filter='url(#l)'/>`
      + `<rect width='100%' height='100%' filter='url(#f)'/></svg>`
    u = `url("data:image/svg+xml,${encodeURIComponent(svg)}")`
    grainCache.set(k, u)
  }
  return u
}

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
/** Reading fonts: pref value, label, CSS font-family ('' = the app's). Avenir Next is built into iOS / macOS. */
export const FACES: [string, string, string][] = [
  ['', 'Обычный', ''],
  ['Avenir Next', 'Avenir Next', '"Avenir Next", Avenir, "Segoe UI", Roboto, sans-serif'],
  ['Georgia', 'С засечками', 'Georgia, "Times New Roman", serif'],
]

/** Book mode with "Как в системе": paper colours (as Android v1.121). */
const PAPER: ReaderTheme = { key: 'paper', name: 'Бумага', bg: '#F6EFE0', fg: '#33291F', dark: false, grain: 0.5 }
const PAPER_DARK: ReaderTheme = { key: 'paperDark', name: 'Бумага ночью', bg: '#1E1B17', fg: '#DCD3C3', dark: true, grain: 0.35 }

export function readerStyle(book = false): JSX.CSSProperties {
  const t = themeOf(prefs.readerTheme.value) ?? (book ? (systemDark.value ? PAPER_DARK : PAPER) : undefined)
  const hl = highlightColors(prefs.highlight.value, darkPage())
  const out: Record<string, string> = { '--green': hl.text, '--green-bg': hl.bg, '--pin-gap': (Number(prefs.pinGap.value) || 0) + 'px' }
  const face = FACES.find((f) => f[0] === prefs.readerFace.value)
  if (face?.[2]) out['--reader-face'] = face[2]
  if (t) {
    const mix = (f: number) => `color-mix(in srgb, ${t.fg} ${f * 100}%, ${t.bg})`
    Object.assign(out, t.dark ? ACCENT_DARK : ACCENT_LIGHT, {
      '--surface': t.bg, '--on-surface': t.fg, '--surface-c': mix(0.06), '--surface-ch': mix(0.09), '--surface-chh': mix(0.12),
      '--surface-variant': mix(0.10), '--on-surface-variant': mix(0.72), '--outline': mix(0.45), '--outline-variant': mix(0.18),
      background: t.bg, color: t.fg,
    })
    const img = grainImage(t.grain)
    if (img) out.backgroundImage = img
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
