// Where reading stopped in each lesson and how much of it has been read; kept on this device.
import { signal } from '@preact/signals'

/** [s]: sentence the reader was at (first one on the page); [p]: furthest point read, 0..1; [t]: when [s] changed (ms). */
export interface Position { s: number; p: number; t: number }

const PREFIX = 'pos.'
const key = (lang: string, lesson: number) => `${lang}_${lesson}`

function loadAll(): Record<string, Position> {
  const out: Record<string, Position> = {}
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (!k?.startsWith(PREFIX)) continue
      const v = JSON.parse(localStorage.getItem(k) ?? 'null')
      if (v && typeof v.s === 'number') out[k.slice(PREFIX.length)] = { s: v.s, p: Number(v.p) || 0, t: Number(v.t) || 0 }
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
  put(k, { s: sent, p, t: old && old.s === sent ? old.t : Date.now() })
}

function put(k: string, v: Position) {
  positions.value = { ...positions.value, [k]: v }
  try { localStorage.setItem(PREFIX + k, JSON.stringify(v)) } catch { /* ignore */ }
}

/** Sync format: {"<lang>_<lesson>": {pos, t, read}}. */
export function exportPositions(): Record<string, { pos: number; t: number; read: number }> {
  const out: Record<string, { pos: number; t: number; read: number }> = {}
  for (const [k, v] of Object.entries(positions.value)) out[k] = { pos: v.s, t: v.t, read: v.p }
  return out
}

/** Sync: the position by the later time, the part read = max. */
export function importPositions(o: Record<string, { pos?: number; t?: number; read?: number }>) {
  for (const [k, r] of Object.entries(o)) {
    const old = positions.value[k]
    const t = Number(r.t) || 0
    const newer = !old || t > old.t
    const v = { s: newer ? Number(r.pos) || 0 : old.s, t: newer ? t : old.t, p: Math.max(old?.p ?? 0, Number(r.read) || 0) }
    if (!old || v.s !== old.s || v.t !== old.t || v.p !== old.p) put(k, v)
  }
}

/** "37 %" (rounded down, so 100 % means the end was reached). */
export const percent = (p: number) => `${Math.floor(p * 100 + 1e-9)} %`
