// Where reading stopped in each lesson and how much of it has been read; kept on this device.
import { signal } from '@preact/signals'

/** [s]: sentence the reader was at (first one on the page); [p]: furthest point read, 0..1. */
export interface Position { s: number; p: number }

const PREFIX = 'pos.'
const key = (lang: string, lesson: number) => `${lang}_${lesson}`

function loadAll(): Record<string, Position> {
  const out: Record<string, Position> = {}
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (!k?.startsWith(PREFIX)) continue
      const v = JSON.parse(localStorage.getItem(k) ?? 'null')
      if (v && typeof v.s === 'number') out[k.slice(PREFIX.length)] = { s: v.s, p: Number(v.p) || 0 }
    }
  } catch { /* storage blocked */ }
  return out
}

export const positions = signal<Record<string, Position>>(loadAll())

export const positionOf = (lang: string, lesson: number): Position | undefined => positions.value[key(lang, lesson)]

/** The reader is at sentence [sent]; [read] (0..1) is how far into the lesson the end of the screen is. */
export function savePosition(lang: string, lesson: number, sent: number, read: number) {
  const k = key(lang, lesson)
  const old = positions.value[k]
  const p = Math.min(1, Math.max(old?.p ?? 0, read))
  if (old && old.s === sent && old.p === p) return
  const v = { s: sent, p }
  positions.value = { ...positions.value, [k]: v }
  try { localStorage.setItem(PREFIX + k, JSON.stringify(v)) } catch { /* ignore */ }
}

/** "37 %" (rounded down, so 100 % means the end was reached). */
export const percent = (p: number) => `${Math.floor(p * 100 + 1e-9)} %`
