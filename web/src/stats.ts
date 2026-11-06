// Reading statistics: time and words per day, kept on this device. Port of Android Stats.kt (ReadingStats, ReadingTimer).
import { signal } from '@preact/signals'
import { useEffect } from 'preact/hooks'

interface Data {
  /** "2026-11-06" -> [seconds, words] */
  days: Record<string, [number, number]>
  /** "de_123" -> highest sentence index counted in that lesson (words are counted once). */
  seen: Record<string, number>
}

function load(): Data {
  try {
    const o = JSON.parse(localStorage.getItem('stats') ?? '{}')
    return { days: o.days ?? {}, seen: o.seen ?? {} }
  } catch { return { days: {}, seen: {} } }
}

/** All numbers; replaced on every change so screens showing them re-render. */
export const stats = signal<Data>(load())

function save(d: Data) {
  stats.value = d
  try { localStorage.setItem('stats', JSON.stringify(d)) } catch { /* ignore */ }
}

/** Local date as "YYYY-MM-DD". */
export const dayKey = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

export const dayOf = (d: Date): [number, number] => stats.value.days[dayKey(d)] ?? [0, 0]

function addToday(sec: number, words: number) {
  const d = stats.value
  const k = dayKey(new Date())
  const v = d.days[k] ?? [0, 0]
  save({ ...d, days: { ...d.days, [k]: [v[0] + sec, v[1] + words] } })
}

/** Sentences [from]..[to] (indices in the lesson) are on screen: words not counted before are added to today. */
export function countRead(lang: string, lesson: number, from: number, to: number, words: (i: number) => number) {
  const k = `${lang}_${lesson}`
  const start = Math.max(from, (stats.value.seen[k] ?? -1) + 1)
  if (start > to) return
  let n = 0
  for (let i = start; i <= to; i++) n += words(i)
  const d = stats.value
  save({ ...d, seen: { ...d.seen, [k]: to } })
  addToday(0, n)
}

/** Totals [seconds, words] from [from] to today. */
export function since(from: Date): [number, number] {
  const out: [number, number] = [0, 0]
  const d = new Date(from.getFullYear(), from.getMonth(), from.getDate())
  const end = dayKey(new Date())
  for (;;) {
    const v = dayOf(d)
    out[0] += v[0]; out[1] += v[1]
    if (dayKey(d) === end || d > new Date()) break
    d.setDate(d.getDate() + 1)
  }
  return out
}

/** Days in a row with at least a minute of reading, up to today (today may still be empty). */
export function streak(): number {
  const d = new Date()
  if (dayOf(d)[0] < 60) d.setDate(d.getDate() - 1)
  let n = 0
  while (dayOf(d)[0] >= 60) { n++; d.setDate(d.getDate() - 1) }
  return n
}

// --- activity: the last touch / key press (reading time and keeping the screen on) ---

export let lastTouch = Date.now()
const touched = () => { lastTouch = Date.now(); touchListeners.forEach((f) => f()) }
const touchListeners = new Set<() => void>()
export const onTouch = (f: () => void) => { touchListeners.add(f); return () => { touchListeners.delete(f) } }
for (const ev of ['pointerdown', 'keydown', 'wheel']) document.addEventListener(ev, touched, { passive: true, capture: true })

/**
 * While a lesson is open and in front: every 15 s counts as reading time, unless nothing was touched for
 * 3 minutes (the iPad was put aside).
 */
export function useReadingTimer(active: boolean) {
  useEffect(() => {
    if (!active) return
    const t = setInterval(() => {
      if (document.visibilityState === 'visible' && Date.now() - lastTouch < 180_000) addToday(15, 0)
    }, 15_000)
    return () => clearInterval(t)
  }, [active])
}

export function formatTime(sec: number) {
  const m = Math.floor(sec / 60)
  return m < 60 ? `${m} мин` : `${Math.floor(m / 60)} ч ${String(m % 60).padStart(2, '0')} мин`
}

export function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10, m100 = n % 100
  return m10 === 1 && m100 !== 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? few : many
}

export const wordsText = (n: number) => `${n.toLocaleString('ru-RU')} ${plural(n, 'слово', 'слова', 'слов')}`
